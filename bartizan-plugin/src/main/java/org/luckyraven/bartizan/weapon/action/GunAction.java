package org.luckyraven.bartizan.weapon.action;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.luckyraven.keystone.item.ItemBuilder;
import org.luckyraven.keystone.sound.SoundEffect;
import org.luckyraven.keystone.util.ActionBarManager;
import org.luckyraven.bartizan.weapon.WeaponService;
import org.luckyraven.bartizan.api.event.WeaponShootEvent;
import org.luckyraven.bartizan.api.raytrace.WeaponRaytracer;
import org.luckyraven.bartizan.raytrace.WeaponShooting;
import org.luckyraven.bartizan.util.EmptyMagSoundGate;
import org.luckyraven.bartizan.api.weapon.GunWeapon;

public class GunAction {

	private final JavaPlugin      plugin;
	private final WeaponService   weaponService;
	private final GunWeapon       weapon;
	private final WeaponRaytracer raytracer;

	public GunAction(JavaPlugin plugin, WeaponService weaponService, GunWeapon weapon, WeaponRaytracer raytracer) {
		this.plugin        = plugin;
		this.weaponService = weaponService;
		this.weapon        = weapon;
		this.raytracer     = raytracer;
	}

	public void weaponShoot(Player shooter) {
		// update data
		ItemBuilder heldWeapon = weaponService.getHeldWeaponItem(shooter);

		if (heldWeapon == null) {
			return;
		}

		// check the durability of the weapon
		if (weapon.isBroken()) {
			EmptyMagSoundGate.play(plugin, shooter, weapon);
			ActionBarManager.send(shooter, "&cBroken");
			return;
		}

		// consume a bullet
		boolean consumed = weapon.consumeShot();

		// no shot fired
		if (!consumed) {
			// empty magazine sound — gated so burst/auto modes play it only once per press cycle
			EmptyMagSoundGate.play(plugin, shooter, weapon);
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

		WeaponShooting.fire(plugin, raytracer, shooter, weapon);

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

		// shooting sound — echo broadcasts to all players near the shooter's location
		SoundEffect.playSoundsAtLocation(shooter.getLocation(), weapon.getSoundData().getShotCustom(),
		                                        weapon.getSoundData().getShotDefault());
	}

}
