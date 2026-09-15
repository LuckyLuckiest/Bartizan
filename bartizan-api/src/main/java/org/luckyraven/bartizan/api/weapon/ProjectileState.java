package org.luckyraven.bartizan.api.weapon;

import lombok.Getter;
import lombok.Setter;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.api.weapon.modifiers.action.RicochetModifier;
import org.luckyraven.bartizan.api.weapon.GunWeapon;

/**
 * Tracks the runtime state of a projectile for modifier calculations.
 */
@Getter
@Setter
public class ProjectileState {

	private final Weapon weapon;
	private final double baseDamage;

	private int    blocksPenetrated;
	private int    entitiesPenetrated;
	private int    bounceCount;
	private double currentDamageMultiplier;

	/**
	 * Gate {@code HI-a}: 0 for an original projectile/throw, 1 for a cluster/airstrike sub-munition
	 * {@code ExplosionHandler} spawns via {@code WeaponShooting#launch}. Gates further cluster/airstrike
	 * recursion — {@code SteppedProjectileTask#onImpact} reads this back off the surrounding
	 * {@code RaytraceContext}'s state.
	 */
	private int depth;

	public ProjectileState(GunWeapon weapon) {
		this(weapon, weapon.getProjectileData().getDamage());
	}

	/**
	 * Constructs a projectile state for any weapon type with an explicit base damage. Used by non-gun weapon actions
	 * (incendiary, biological, melee, throwable) that don't carry a {@code ProjectileData} object but still want to
	 * drive the unified raytracer.
	 */
	public ProjectileState(Weapon weapon, double baseDamage) {
		this.weapon                  = weapon;
		this.baseDamage              = baseDamage;
		this.blocksPenetrated        = 0;
		this.entitiesPenetrated      = 0;
		this.bounceCount             = 0;
		this.currentDamageMultiplier = 1.0;
	}

	/**
	 * Calculates the current damage after all modifier reductions.
	 */
	public double getCurrentDamage() {
		return baseDamage * currentDamageMultiplier;
	}

	/**
	 * Applies penetration damage reduction.
	 */
	public void applyPenetrationReduction(double reduction) {
		currentDamageMultiplier *= (1.0 - reduction);
	}

	/**
	 * Applies ricochet damage reduction.
	 */
	public void applyRicochetReduction(double retention) {
		currentDamageMultiplier *= retention;
	}

	/**
	 * Checks if the projectile can still penetrate blocks.
	 */
	public boolean canPenetrateBlock() {
		var modifiers = weapon.getModifiersData();
		if (!modifiers.hasPenetration()) return false;
		return blocksPenetrated < modifiers.getPenetration().penetrateBlocks();
	}

	/**
	 * Checks if the projectile can still penetrate entities.
	 */
	public boolean canPenetrateEntity() {
		var modifiers = weapon.getModifiersData();
		if (!modifiers.hasPenetration()) return false;
		return entitiesPenetrated < modifiers.getPenetration().penetrateEntities();
	}

	/**
	 * Checks if the projectile can still ricochet.
	 */
	public boolean canRicochet() {
		var modifiers = weapon.getModifiersData();
		if (!modifiers.hasRicochet()) return false;

		// Check against the maximum bounces from all ricochet modifiers
		int maxBounces = modifiers.getRicochets()
				.stream().mapToInt(RicochetModifier::maxBounces).max().orElse(0);

		return bounceCount < maxBounces;
	}

}
