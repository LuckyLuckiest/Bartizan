package org.luckyraven.bartizan.stats;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import lombok.CustomLog;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;
import org.luckyraven.bartizan.api.event.WeaponAssistEvent;
import org.luckyraven.bartizan.api.weapon.BodyZone;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.file.BartizanSettings;
import org.luckyraven.keystone.bean.BeanLifecycle;
import org.luckyraven.keystone.timer.RepeatingTimer;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Per-player weapon statistics (weapons-roadmap.md gate {@code HK}): shots, hits, per-zone hits/headshots, kills,
 * assists, damage dealt and longest kill distance, bucketed per weapon name. Persisted as one Gson flat file per
 * player under {@code plugins/Bartizan/stats/<uuid>.json} rather than a database — the user rejected a database at
 * 0.2.0 (see MEMORY.md "No weapon uuid database"), and Bartizan keeps none.
 *
 * <p>{@link #tickClock} is injected the same way {@code StatusEffectService}'s is, so a test can drive the assist
 * window without a real scheduler. Bukkit-facing {@code @EventHandler}s live in {@link StatsListener} — this class
 * is the plain, unit-testable logic, mirroring the {@code StatusEffectService}/{@code StatusListener} split.
 */
@CustomLog
public class StatsService implements BeanLifecycle {

	private final JavaPlugin   plugin;
	private final LongSupplier tickClock;
	private final File         statsDir;
	private final Gson         gson = new GsonBuilder().setPrettyPrinting().create();

	private final Map<UUID, PlayerStats> loaded = new HashMap<>();
	private final Set<UUID>              dirty  = new HashSet<>();

	/**
	 * {@code victim -> attacker -> last recorded hit}, consumed (the victim's whole inner map removed) the moment
	 * that victim dies. Player victims only: entries are removed by {@link #recordKill} (a weapon-claimed kill) and,
	 * unconditionally for every player death regardless of cause (BZ-HU-04), by {@link #recordDeath} — recording a
	 * mob (or any non-player) victim here would sit in the map forever, since neither is ever reached for a mob
	 * death.
	 *
	 * <p>// ponytail: an entry for a player victim that takes damage but never dies (and is never touched again)
	 * leaks until server restart — bounded in practice by "players currently mid-fight," trivial at real-world
	 * scale; add a sweep (piggybacked on a tick, like {@code WeaponDeathListener}'s throwable-claim map) if that's
	 * ever measured otherwise.
	 */
	private final Map<UUID, Map<UUID, DamageRecord>> recentDamage = new HashMap<>();

	@Nullable
	private RepeatingTimer timer;

	public StatsService(JavaPlugin plugin, LongSupplier tickClock) {
		this.plugin    = plugin;
		this.tickClock = tickClock;
		this.statsDir  = new File(plugin.getDataFolder(), "stats");
	}

	/**
	 * Starts the autosave timer. Called once, from {@code WiringConfig}'s bean construction, same as
	 * {@code StatusEffectService}/{@code HudService}. Idempotent.
	 */
	public void start() {
		if (timer != null) return;

		long intervalTicks = Math.max(1, BartizanSettings.getStatsAutosaveMinutes()) * 60L * 20L;
		timer = new RepeatingTimer(plugin, intervalTicks, ignored -> autosave());
		timer.start(false);
	}

	public void recordShot(Player shooter, String weaponName) {
		if (!BartizanSettings.isStatsEnabled()) return;

		stats(shooter).weapon(weaponName).shots++;
		markDirty(shooter);
	}

	public void recordDamage(Player shooter, Entity victim, String weaponName, double damage,
	                         @Nullable BodyZone zone, double distance) {
		if (!BartizanSettings.isStatsEnabled()) return;

		WeaponStat stat = stats(shooter).weapon(weaponName);
		stat.hits++;
		stat.damageDealt += damage;
		if (zone != null) {
			stat.addZoneHit(zone);
			if (zone == BodyZone.HEAD) stat.headshots++;
		}
		markDirty(shooter);

		// Player victims only — see recentDamage's javadoc: assists are only ever credited (and the entry only
		// ever removed) through a player death, so recording a mob victim here would leak until server restart.
		if (victim instanceof Player) {
			recentDamage.computeIfAbsent(victim.getUniqueId(), ignored -> new HashMap<>())
			            .put(shooter.getUniqueId(), new DamageRecord(tickClock.getAsLong(), weaponName, distance));
		}
	}

	/**
	 * Kills (for the killer's weapon, plus {@code longestKillDistance} from the recorded distance) and — for every
	 * other player who damaged the same victim within {@code Stats.Assist_Window_Ticks} of this kill — an assist
	 * plus a fired {@link WeaponAssistEvent}. The victim's death count is <b>not</b> touched here (BZ-HU-04) —
	 * {@link #recordDeath} is the single, unconditional source of that count, since this method only ever runs when
	 * a Bartizan weapon (or a biological status) actually claimed the kill.
	 *
	 * <p>{@code weapon} may be {@code null} — {@code WeaponDeathListener} fires {@code WeaponKillEntityEvent} with
	 * a null weapon when a throwable claim's template no longer resolves. Only the kill-credit block is skipped in
	 * that case; the assist loop (keyed by each attacker's own recorded weapon, never the killer's) still runs.
	 */
	public void recordKill(@Nullable Weapon weapon, @Nullable Entity killer, Entity killed) {
		if (!BartizanSettings.isStatsEnabled()) return;

		Map<UUID, DamageRecord> attackers  = recentDamage.remove(killed.getUniqueId());
		String                  weaponName = weapon != null ? weapon.getName() : null;

		if (killer instanceof Player killerPlayer && weaponName != null) {
			WeaponStat stat = stats(killerPlayer).weapon(weaponName);
			stat.kills++;

			DamageRecord killerRecord = attackers != null ? attackers.get(killerPlayer.getUniqueId()) : null;
			if (killerRecord != null && killerRecord.distance() > stat.longestKillDistance) {
				stat.longestKillDistance = killerRecord.distance();
			}
			markDirty(killerPlayer);
		}

		if (attackers == null || !(killed instanceof LivingEntity victim)) return;

		long window   = BartizanSettings.getStatsAssistWindowTicks();
		long now      = tickClock.getAsLong();
		UUID killerId = killer != null ? killer.getUniqueId() : null;

		for (Map.Entry<UUID, DamageRecord> entry : attackers.entrySet()) {
			if (entry.getKey().equals(killerId)) continue;
			if (now - entry.getValue().tick() > window) continue;

			Player assister = Bukkit.getPlayer(entry.getKey());
			if (assister == null) continue;

			stats(assister).weapon(entry.getValue().weaponName()).assists++;
			markDirty(assister);

			Bukkit.getPluginManager().callEvent(
					new WeaponAssistEvent(entry.getValue().weaponName(), assister, victim, killer));
		}
	}

	/**
	 * BZ-HU-04: the single, unconditional death counter — called for every {@code PlayerDeathEvent}, regardless of
	 * cause. Previously {@code deaths++} lived only in {@link #recordKill}, which is only ever reached when a
	 * Bartizan weapon or an active biological status claimed the kill, so an ordinary death (fall, drowning, lava,
	 * void, starvation, unarmed PvP, a vanilla/other-plugin mob kill) never incremented it. Also clears the
	 * victim's {@link #recentDamage} entry — a harmless no-op when {@link #recordKill} already consumed it for a
	 * weapon-claimed kill, the only cleanup at all otherwise.
	 */
	public void recordDeath(Player victim) {
		if (!BartizanSettings.isStatsEnabled()) return;

		recentDamage.remove(victim.getUniqueId());
		stats(victim).deaths++;
		markDirty(victim);
	}

	/**
	 * BZ-HU-01: {@link #save} is only trusted to drop the in-memory copy when it actually succeeded — a disk
	 * hiccup (stats directory deleted by an external backup job, permission issue, full disk, file locked by
	 * AV/backup software) at the exact moment a player disconnects must not discard that session's stats. On
	 * failure, {@code loaded}/{@code dirty} are left alone: {@link #stats(Player)} keeps serving the in-memory
	 * copy (newer than whatever is on disk) if they rejoin, and the autosave timer / {@link #onShutdown} retry the
	 * write.
	 */
	public void onQuit(UUID playerId) {
		if (save(playerId)) {
			loaded.remove(playerId);
		}
	}

	@Override
	public void onShutdown() {
		autosave();

		if (timer != null) {
			timer.stop();
			timer = null;
		}
	}

	// -------------------------------------------------------------------------------------------------------
	// Lookup (StatsCommand) - works for an offline player too, by loading their file straight off disk.
	// -------------------------------------------------------------------------------------------------------

	/**
	 * Deliberately never populates {@link #loaded} on a disk read: {@code StatsCommand} calls this for an
	 * arbitrary (possibly offline, possibly never-online) target, and caching every such lookup would leak one
	 * entry per admin query forever. A player who IS online already has a live, up-to-date entry cached via
	 * {@link #stats(Player)} (populated by recordShot/recordDamage/recordKill), so the cache-hit branch below
	 * still returns that instance for them — cache population for gameplay stays entirely on that path.
	 */
	@Nullable
	public PlayerStats lookup(UUID playerId) {
		PlayerStats cached = loaded.get(playerId);
		if (cached != null) return cached;

		return readFile(playerId);
	}

	// -------------------------------------------------------------------------------------------------------
	// Persistence
	// -------------------------------------------------------------------------------------------------------

	private PlayerStats stats(Player player) {
		return loaded.computeIfAbsent(player.getUniqueId(), id -> {
			PlayerStats fromDisk = readFile(id);
			return fromDisk != null ? fromDisk : new PlayerStats();
		});
	}

	private void markDirty(Player player) {
		dirty.add(player.getUniqueId());
	}

	/**
	 * BZ-HU-01: a successful save also evicts an offline player's cache entry — {@code save(UUID)} no longer does
	 * this itself (it must not evict a still-online player, who has no other path back into {@link #loaded}), so
	 * without this an id that keeps failing to fully leave (retried here every cycle) would otherwise stay in
	 * {@link #loaded} forever once it starts succeeding again.
	 */
	private void autosave() {
		for (UUID id : Set.copyOf(dirty)) {
			if (save(id) && Bukkit.getPlayer(id) == null) {
				loaded.remove(id);
			}
		}
	}

	// ponytail: main-thread synchronous write, one small JSON file per dirty player on quit/autosave/shutdown -
	// fine at this scale; move to an async write if stats file I/O ever shows up in /timings.
	/**
	 * BZ-HU-01: returns whether {@code playerId} ends this call with nothing left to save — {@code true} when
	 * there was nothing dirty, or a dirty entry was written successfully; {@code false} only when a write was
	 * attempted and failed, in which case the dirty flag is deliberately left set for a later retry. Callers use
	 * this to decide whether it is safe to drop the in-memory copy.
	 */
	private boolean save(UUID playerId) {
		if (!dirty.contains(playerId)) return true;

		PlayerStats stats = loaded.get(playerId);
		if (stats == null) {
			// Dirty but nothing loaded to persist (shouldn't happen in practice - markDirty always follows a
			// stats() call) - nothing to retry either, so clear the stale flag and treat as success.
			dirty.remove(playerId);
			return true;
		}

		if (!statsDir.exists() && !statsDir.mkdirs()) {
			log.warn("could not create stats directory " + statsDir);
			return false;
		}

		File file = new File(statsDir, playerId + ".json");
		try (Writer writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
			gson.toJson(stats, writer);
		} catch (IOException exception) {
			log.warn("failed to save stats for " + playerId + ": " + exception.getMessage());
			return false;
		}

		dirty.remove(playerId);
		return true;
	}

	@Nullable
	private PlayerStats readFile(UUID playerId) {
		File file = new File(statsDir, playerId + ".json");
		if (!file.exists()) return null;

		try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
			PlayerStats stats = gson.fromJson(reader, PlayerStats.class);
			return stats != null ? stats : new PlayerStats();
		} catch (IOException | JsonParseException exception) {
			log.warn("corrupt or unreadable stats file for " + playerId + ": " + exception.getMessage());
			quarantine(file);
			return null;
		}
	}

	/**
	 * Renames a corrupt/unreadable stats file to {@code <uuid>.json.broken} so a fresh save under the original
	 * name (triggered by this method's caller returning {@code null} -> a brand-new {@code PlayerStats}) never
	 * overwrites it — the broken copy stays on disk for a server owner to inspect or discard.
	 */
	private void quarantine(File file) {
		File broken = new File(file.getParentFile(), file.getName() + ".broken");
		if (!file.renameTo(broken)) {
			log.warn("could not quarantine corrupt stats file " + file);
		}
	}

	private record DamageRecord(long tick, String weaponName, double distance) {
	}

}
