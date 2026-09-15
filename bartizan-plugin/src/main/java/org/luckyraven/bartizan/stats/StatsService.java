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
	 * that victim dies. Player victims only: entries are removed only by {@link #recordKill}, which in turn is
	 * only ever reached through {@code WeaponDeathListener.onPlayerDeath} — a player-victim death. Recording a
	 * mob (or any non-player) victim here would sit in the map forever, since nothing ever calls
	 * {@code recordKill} for a mob death to remove it.
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
	 * Kills (for the killer's weapon, plus {@code longestKillDistance} from the recorded distance), a death (for a
	 * player victim), and — for every other player who damaged the same victim within
	 * {@code Stats.Assist_Window_Ticks} of this kill — an assist plus a fired {@link WeaponAssistEvent}.
	 *
	 * <p>{@code weapon} may be {@code null} — {@code WeaponDeathListener} fires {@code WeaponKillEntityEvent} with
	 * a null weapon when a throwable claim's template no longer resolves. Only the kill-credit block is skipped in
	 * that case; the death count and the assist loop (keyed by each attacker's own recorded weapon, never the
	 * killer's) still run.
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

		if (killed instanceof Player killedPlayer) {
			stats(killedPlayer).deaths++;
			markDirty(killedPlayer);
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

	public void onQuit(UUID playerId) {
		save(playerId);
		loaded.remove(playerId);
		dirty.remove(playerId);
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

	private void autosave() {
		for (UUID id : Set.copyOf(dirty)) {
			save(id);
		}
	}

	// ponytail: main-thread synchronous write, one small JSON file per dirty player on quit/autosave/shutdown -
	// fine at this scale; move to an async write if stats file I/O ever shows up in /timings.
	private void save(UUID playerId) {
		if (!dirty.remove(playerId)) return;

		PlayerStats stats = loaded.get(playerId);
		if (stats == null) return;

		if (!statsDir.exists() && !statsDir.mkdirs()) {
			log.warn("could not create stats directory " + statsDir);
			return;
		}

		File file = new File(statsDir, playerId + ".json");
		try (Writer writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
			gson.toJson(stats, writer);
		} catch (IOException exception) {
			log.warn("failed to save stats for " + playerId + ": " + exception.getMessage());
		}
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
