package org.luckyraven.bartizan.npc;

import org.bukkit.entity.LivingEntity;
import org.luckyraven.bartizan.Bartizan;
import org.luckyraven.bartizan.api.npc.NpcWeaponController;
import org.luckyraven.bartizan.api.npc.NpcWeaponFactory;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.effect.EffectRunner;
import org.luckyraven.bartizan.weapon.WeaponManager;

/**
 * Bartizan's sole {@link NpcWeaponFactory} implementation (bartizan.md §1.6(8)). Resolves a fresh, unregistered
 * weapon instance via {@link WeaponManager#createTransientWeapon(String)} — NPC weapons never enter the runtime
 * registry.
 * Constructor matches {@code WiringConfig.npcWeaponFactory(WeaponManager)}'s existing
 * {@code new NpcWeaponFactoryImpl(bartizan, weaponManager)} call exactly (bartizan.md B10 row) — no config-class
 * change needed.
 */
public class NpcWeaponFactoryImpl implements NpcWeaponFactory {

	private final Bartizan      bartizan;
	private final WeaponManager weaponManager;
	private final EffectRunner  effectRunner;

	public NpcWeaponFactoryImpl(Bartizan bartizan, WeaponManager weaponManager, EffectRunner effectRunner) {
		this.bartizan      = bartizan;
		this.weaponManager = weaponManager;
		this.effectRunner  = effectRunner;
	}

	@Override
	public NpcWeaponController create(LivingEntity shooter, String weaponName, double fireRateMultiplier,
	                                  double aimErrorDegrees) {
		Weapon weapon = weaponManager.createTransientWeapon(weaponName);
		if (weapon == null) {
			// Gate-H review M1: an unknown name used to surface as an NPE on the controller's first combat tick.
			throw new IllegalArgumentException("Unknown weapon '" + weaponName + "' - check WeaponItemApi.isValidWeaponName first");
		}
		return new NpcWeaponControllerImpl(bartizan, shooter, weapon, fireRateMultiplier, aimErrorDegrees, effectRunner);
	}

}
