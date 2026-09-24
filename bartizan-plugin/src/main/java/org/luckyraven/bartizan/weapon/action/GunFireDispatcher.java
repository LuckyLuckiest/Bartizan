package org.luckyraven.bartizan.weapon.action;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.luckyraven.keystone.timer.CountdownTimer;
import org.luckyraven.keystone.timer.SequenceTimer;
import org.luckyraven.bartizan.api.raytrace.WeaponRaytracer;
import org.luckyraven.bartizan.api.weapon.GunWeapon;
import org.luckyraven.bartizan.api.weapon.SelectiveFire;
import org.luckyraven.bartizan.effect.EffectRunner;
import org.luckyraven.bartizan.weapon.WeaponService;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The SINGLE/BURST shot (and burst-sequence) dispatch, extracted out of {@code WeaponInteract} so
 * {@code WeaponSelectiveFireChangeListener}'s scoped {@code F} fire (weapons-roadmap.md gate {@code HP}) fires
 * through the exact same path a trigger click uses, instead of a second copy of it. AUTO is not handled here -
 * both callers construct their own {@link FullAutoTask}, since each owns a different lifecycle for it (a held
 * trigger with a release-detection watchdog vs. the spyglass scope-out poll).
 */
public final class GunFireDispatcher {

	/**
	 * Floor on the fire-rate lock window, in ticks - mirrors {@code WeaponInteract#MIN_PRESS_LOCK_TICKS}.
	 */
	private static final long MIN_PRESS_LOCK_TICKS = 4L;
	private static final long MILLIS_PER_TICK = 50L;

	/**
	 * Shared {@code Projectile.Cooldown} fire-rate gate for SINGLE/BURST, keyed by weapon UUID (weapons-roadmap.md
	 * gate {@code HP} review): {@code WeaponInteract}'s RMB click (via {@link #isLocked}/{@link #lock}, replacing
	 * that class's own copy of this map) and {@code WeaponSelectiveFireChangeListener}'s scoped {@code F} fire both
	 * check and set this same map, so mashing either input can't outrun the weapon's configured cooldown.
	 * {@code WeaponInteract}'s held-trigger release watchdog ({@code pressHoldState}) is a separate, orthogonal
	 * concern that still lives there.
	 */
	private static final Map<UUID, Long> pressLockUntilTick = new ConcurrentHashMap<>();

	/**
	 * Weapons with a pending {@code Weapon_Consumed.Time} countdown (BZ-EV-06): the first shot starts it, later shots
	 * while it runs don't stack another one. The countdown drops its own entry when it ends.
	 */
	private static final Set<UUID> consumeCountdowns = ConcurrentHashMap.newKeySet();

	private GunFireDispatcher() {
	}

	/**
	 * @return {@code true} while {@code weaponUuid}'s fire-rate window (set by the last {@link #lock} call) hasn't
	 * 		elapsed yet.
	 */
	public static boolean isLocked(UUID weaponUuid) {
		Long lockedUntil = pressLockUntilTick.get(weaponUuid);
		return lockedUntil != null && System.currentTimeMillis() < lockedUntil;
	}

	/**
	 * Records {@code weaponUuid}'s fire-rate deadline, {@code lockTicks} ticks from now.
	 */
	public static void lock(UUID weaponUuid, long lockTicks) {
		pressLockUntilTick.put(weaponUuid, System.currentTimeMillis() + lockTicks * MILLIS_PER_TICK);
	}

	/**
	 * Clears {@code weaponUuid}'s fire-rate deadline - called on weapon swap so a fresh selection isn't gated by a
	 * stale lock left over from before the swap.
	 */
	public static void unlock(UUID weaponUuid) {
		pressLockUntilTick.remove(weaponUuid);
	}

	/**
	 * {@code Projectile.Cooldown}-derived fire-rate window, in ticks, floored at {@link #MIN_PRESS_LOCK_TICKS} -
	 * the one formula every {@link #shoot} caller uses to compute how long to {@link #lock} the weapon for.
	 */
	public static long lockTicksFor(GunWeapon weapon) {
		var projectileData = weapon.getProjectileData();
		return Math.max((long) projectileData.getPerShot() * projectileData.getCooldown(), MIN_PRESS_LOCK_TICKS);
	}

	public static void shoot(JavaPlugin plugin, WeaponService weaponService, GunWeapon weapon,
	                         WeaponRaytracer raytracer, EffectRunner effectRunner, Player player) {
		shootInterval(plugin, weaponService, weapon, raytracer, effectRunner, player);

		if (weapon.getCurrentSelectiveFire() != SelectiveFire.BURST) return;

		// BURST: the remaining rounds of the sequence, each spaced by the projectile cooldown.
		int perShot  = weapon.getProjectileData().getPerShot();
		int cooldown = weapon.getProjectileData().getCooldown();

		if (perShot <= 1) return;

		SequenceTimer sequenceTimer = new SequenceTimer(plugin, 1L, 1L);

		for (int i = 1; i < perShot; ++i) {
			sequenceTimer.addIntervalTaskPair(cooldown,
					time -> shootInterval(plugin, weaponService, weapon, raytracer, effectRunner, player));
		}

		sequenceTimer.start(false);
	}

	private static void shootInterval(JavaPlugin plugin, WeaponService weaponService, GunWeapon weapon,
	                                  WeaponRaytracer raytracer, EffectRunner effectRunner, Player player) {
		GunAction gunAction = new GunAction(plugin, weaponService, weapon, raytracer, effectRunner);

		gunAction.weaponShoot(player);

		if (weapon.getWeaponConsumedOnShot() > 0 &&
		    weapon.getCurrentMagCapacity() == weapon.getWeaponConsumedOnShot()) {
			weaponService.replaceHeldWeapon(player, weapon, null);
		}

		int consumeOnTime = weapon.getDurabilityData().getConsumeOnTime();
		if (consumeOnTime <= -1) return;

		UUID weaponUuid = weapon.getUuid();
		if (!consumeCountdowns.add(weaponUuid)) return;

		CountdownTimer timer = new CountdownTimer(plugin, 0L, 0L, consumeOnTime, null, null, time -> {
			consumeCountdowns.remove(weaponUuid);
			weaponService.replaceHeldWeapon(player, weapon, null);
		});

		timer.start(false);
	}

}
