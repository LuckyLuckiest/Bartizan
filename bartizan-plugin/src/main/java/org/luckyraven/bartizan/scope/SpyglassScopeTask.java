package org.luckyraven.bartizan.scope;

import lombok.CustomLog;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;
import org.luckyraven.bartizan.api.raytrace.WeaponRaytracer;
import org.luckyraven.bartizan.api.weapon.GunWeapon;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.effect.EffectRunner;
import org.luckyraven.bartizan.weapon.ScopeToggle;
import org.luckyraven.bartizan.weapon.WeaponService;
import org.luckyraven.bartizan.weapon.action.FullAutoTask;
import org.luckyraven.keystone.bean.BeanLifecycle;
import org.luckyraven.keystone.timer.RepeatingTimer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Polls {@code HumanEntity#isHandRaised()} for every player currently scoped through a {@code Scope.Type: spyglass}
 * weapon (weapons-roadmap.md gate {@code HP}) - Spigot has no "stopped using item" event, so this is the one place
 * that notices a scoped player letting go of right-click. Release, the 1200-tick vanilla use limit, a hotbar
 * change, a swap and a drop all look identical from the server's side (the flag simply drops), so one poll here
 * covers all of them; {@code WeaponQuitCleanupListener}'s death/quit unscope stays as the belt for the two cases
 * this poll can't see coming before the {@link Player} object itself is gone.
 *
 * <p>Also owns the scoped {@code F} fire's {@link FullAutoTask} bookkeeping (see {@link #startAutoFire}) purely
 * because this is the class that has to stop that task the moment the scope drops - keeping starter and stopper
 * in the same place avoids a second shared map.
 */
@CustomLog
public class SpyglassScopeTask implements BeanLifecycle {

	// ponytail: fixed interval, no per-weapon override - matches HudService/StatusEffectService's own ticker shape.
	private static final long TICK_INTERVAL = 2L;

	private final JavaPlugin      plugin;
	private final WeaponService   weaponService;
	private final WeaponRaytracer raytracer;
	private final EffectRunner    effectRunner;

	private final Map<UUID, Weapon>       scoped    = new ConcurrentHashMap<>();
	private final Map<UUID, FullAutoTask> autoTasks = new ConcurrentHashMap<>();

	@Nullable
	private RepeatingTimer timer;

	public SpyglassScopeTask(JavaPlugin plugin, WeaponService weaponService, WeaponRaytracer raytracer,
	                         EffectRunner effectRunner) {
		this.plugin        = plugin;
		this.weaponService = weaponService;
		this.raytracer     = raytracer;
		this.effectRunner  = effectRunner;
	}

	/**
	 * Starts the {@link #tick()} timer. Called once, from {@code WiringConfig}'s bean construction. Idempotent.
	 */
	public void start() {
		if (timer != null) return;

		timer = new RepeatingTimer(plugin, TICK_INTERVAL, ignored -> tick());
		timer.start(false);
	}

	/**
	 * Tracks {@code player} as spyglass-scoped on {@code weapon} - called by {@code WeaponInteract} right after a
	 * successful {@code Type: spyglass} scope-in. Overwrites any previous entry for the same player; a fresh scope
	 * always replaces a stale one.
	 */
	public void register(Player player, Weapon weapon) {
		scoped.put(player.getUniqueId(), weapon);
	}

	/**
	 * The scoped {@code F} fire path for {@code SelectiveFire.AUTO} (weapons-roadmap.md gate {@code HP}): starts a
	 * {@link FullAutoTask} mirroring {@code WeaponInteract#shootFullAuto}'s construction, minus the press-lock/
	 * circumstance gate (F is a discrete key press, not a held button Spigot keeps re-firing) and its own
	 * release-detection watchdog - {@link #tick()} is the release signal here. A no-op while a task for this
	 * weapon is already running; {@link #tick()} stops it once the scope drops.
	 * <p/>
	 * // ponytail: no per-press cooldown/circumstance re-check beyond the caller's own gate - GunAction/
	 * // FullAutoTask already refuse an empty mag, a broken weapon or a denied circumstance on their own tick.
	 */
	public void startAutoFire(GunWeapon weapon, Player player, ItemStack item) {
		UUID weaponUuid = weapon.getUuid();
		if (autoTasks.containsKey(weaponUuid)) return;

		FullAutoTask task = new FullAutoTask(plugin, weaponService, weapon, raytracer, player, item,
		                                     () -> autoTasks.remove(weaponUuid), effectRunner);

		autoTasks.put(weaponUuid, task);

		task.start(false);
		task.run();
	}

	/**
	 * One poll over every tracked spyglass-scoped player. Package-visible so a test can drive it directly without a
	 * real scheduler.
	 */
	void tick() {
		if (scoped.isEmpty()) return;

		for (Map.Entry<UUID, Weapon> entry : scoped.entrySet()) {
			Player player = Bukkit.getPlayer(entry.getKey());
			if (player == null || !player.isHandRaised()) {
				scopeOut(entry.getKey(), player, entry.getValue());
			}
		}
	}

	private void scopeOut(UUID playerId, @Nullable Player player, Weapon weapon) {
		scoped.remove(playerId);

		FullAutoTask task = autoTasks.remove(weapon.getUuid());
		if (task != null) task.stop();

		// No live Player left (quit mid-scope) - WeaponQuitCleanupListener already unscoped it directly.
		if (player == null) return;

		try {
			ScopeToggle.apply(weapon, player, weaponService, effectRunner);
		} catch (Exception exception) {
			log.warn("Spyglass scope-out failed for " + playerId + ": " + exception.getMessage());
		}
	}

	@Override
	public void onShutdown() {
		scoped.clear();
		for (FullAutoTask task : autoTasks.values()) task.stop();
		autoTasks.clear();

		if (timer != null) {
			timer.stop();
			timer = null;
		}
	}

}
