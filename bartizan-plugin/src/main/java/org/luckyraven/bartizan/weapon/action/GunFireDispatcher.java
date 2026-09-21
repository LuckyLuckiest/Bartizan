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

/**
 * The SINGLE/BURST shot (and burst-sequence) dispatch, extracted out of {@code WeaponInteract} so
 * {@code WeaponSelectiveFireChangeListener}'s scoped {@code F} fire (weapons-roadmap.md gate {@code HP}) fires
 * through the exact same path a trigger click uses, instead of a second copy of it. AUTO is not handled here -
 * both callers construct their own {@link FullAutoTask}, since each owns a different lifecycle for it (a held
 * trigger with a release-detection watchdog vs. the spyglass scope-out poll).
 */
public final class GunFireDispatcher {

	private GunFireDispatcher() {
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
			weapon.removeWeapon(player, player.getInventory().getHeldItemSlot());
		}

		int consumeOnTime = weapon.getDurabilityData().getConsumeOnTime();
		if (consumeOnTime <= -1) return;

		CountdownTimer timer = new CountdownTimer(plugin, 0L, 0L, consumeOnTime, null, null,
		                                          time -> weapon.removeWeapon(player,
		                                                                      player.getInventory().getHeldItemSlot()));

		timer.start(false);
	}

}
