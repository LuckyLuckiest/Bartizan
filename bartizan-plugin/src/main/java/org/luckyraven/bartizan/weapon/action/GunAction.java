package org.luckyraven.bartizan.weapon.action;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.luckyraven.keystone.item.ItemBuilder;
import org.luckyraven.bartizan.weapon.WeaponService;
import org.luckyraven.bartizan.api.event.WeaponShootEvent;
import org.luckyraven.bartizan.api.raytrace.WeaponRaytracer;
import org.luckyraven.bartizan.api.weapon.dto.EffectHook;
import org.luckyraven.bartizan.effect.EffectContext;
import org.luckyraven.bartizan.effect.EffectRunner;
import org.luckyraven.bartizan.raytrace.WeaponShooting;
import org.luckyraven.bartizan.util.EmptyMagSoundGate;
import org.luckyraven.bartizan.api.weapon.GunWeapon;

public class GunAction {

	private final JavaPlugin      plugin;
	private final WeaponService   weaponService;
	private final GunWeapon       weapon;
	private final WeaponRaytracer raytracer;
	private final EffectRunner    effectRunner;

	public GunAction(JavaPlugin plugin, WeaponService weaponService, GunWeapon weapon, WeaponRaytracer raytracer,
	                 EffectRunner effectRunner) {
		this.plugin        = plugin;
		this.weaponService = weaponService;
		this.weapon        = weapon;
		this.raytracer     = raytracer;
		this.effectRunner  = effectRunner;
	}

	public void weaponShoot(Player shooter) {
		// update data
		ItemBuilder heldWeapon = weaponService.getHeldWeaponItem(shooter);

		if (heldWeapon == null) {
			return;
		}

		// check the durability of the weapon
		if (weapon.isBroken()) {
			EmptyMagSoundGate.play(plugin, shooter, weapon, effectRunner);

			EffectContext denyCtx = EffectContext.builder().weapon(weapon).source(shooter).denyReason("Broken").build();
			effectRunner.run(weapon, EffectHook.ON_DENY, denyCtx);
			return;
		}

		// consume a bullet
		boolean consumed = weapon.consumeShot();

		// no shot fired
		if (!consumed) {
			// empty magazine sound — gated so burst/auto modes play it only once per press cycle
			EmptyMagSoundGate.play(plugin, shooter, weapon, effectRunner);
			return;
		}

		// All projectile types now flow through the unified raytracer via WeaponShooting.
		// Hitscan (BULLET, SPREAD) uses fireInstant; slow visual projectiles (ROCKET, FLARE)
		// use a per-tick SteppedProjectileTask that drives a cosmetic Bukkit entity.
		WeaponShootEvent shootEvent = new WeaponShootEvent(weapon, shooter);
		Bukkit.getPluginManager().callEvent(shootEvent);

		if (shootEvent.isCancelled()) {
			weapon.addAmmunition(1);
			return;
		}

		boolean hitEntity = WeaponShooting.fire(plugin, raytracer, shooter, weapon, effectRunner);

		weapon.updateWeaponData(heldWeapon);

		// change durability of the weapon
		short durabilityOnShot = weapon.getDurabilityData().getOnShot();
		if (durabilityOnShot > (short) 0) {
			weapon.decreaseDurability(heldWeapon, durabilityOnShot);
		}

		weapon.updateWeapon(shooter, heldWeapon, shooter.getInventory().getHeldItemSlot());

		if (weapon.getRecoilData() != null) {
			weapon.getRecoil().applyRecoil(shooter);
			weapon.applyPush(shooter);
		}

		// shooting feedback — echoes to nearby players via the configured On_Shoot effects
		EffectContext ctx = EffectContext.shot(weapon, shooter, shooter.getEyeLocation().getDirection()).build();
		effectRunner.run(weapon, EffectHook.ON_SHOOT, ctx);

		// ON_MISS: only for hitscan rays — a slow projectile's hit resolves later, asynchronously.
		if (!hitEntity && WeaponShooting.isHitscan(weapon.getProjectileData().getType())) {
			effectRunner.run(weapon, EffectHook.ON_MISS, ctx);
		}
	}

}
