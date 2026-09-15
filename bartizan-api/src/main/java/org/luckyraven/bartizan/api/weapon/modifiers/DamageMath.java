package org.luckyraven.bartizan.api.weapon.modifiers;

import org.luckyraven.bartizan.api.weapon.dto.DropoffStep;

import java.util.List;

/**
 * Pure damage-math helpers for gate {@code HF} (damage parity). Kept free of Bukkit types so they stay unit-testable
 * without a running server — {@code WeaponRaytracerImpl} and friends are the Bukkit-bound callers.
 */
public final class DamageMath {

	private DamageMath() {
	}

	/**
	 * Resolves the {@code Damage.Dropoff} delta for a shot travelling {@code distance} blocks: the entry with the
	 * largest {@link DropoffStep#distance()} that is still {@code <=} {@code distance} applies its
	 * {@link DropoffStep#delta()}. Zero when {@code steps} is empty or {@code distance} is below every configured
	 * step's distance.
	 */
	public static double dropoff(List<DropoffStep> steps, double distance) {
		if (steps == null || steps.isEmpty()) return 0.0;

		double bestDistance = Double.NEGATIVE_INFINITY;
		double delta        = 0.0;

		for (DropoffStep step : steps) {
			if (step.distance() <= distance && step.distance() > bestDistance) {
				bestDistance = step.distance();
				delta        = step.delta();
			}
		}

		return delta;
	}

	/**
	 * Converts a summed {@code Damage_Modifiers} percent (e.g. {@code -15} for -15 %) into a damage multiplier,
	 * floored at {@code 0} so a large negative sum can never push damage below zero.
	 */
	public static double percentMultiplier(double percentSum) {
		return Math.max(0.0, 1.0 + percentSum / 100.0);
	}

	/**
	 * Linear falloff scalar for an explosion's {@code Knockback} field: full {@code knockback} at the blast centre,
	 * tapering to {@code 0} at {@code radius}. Mirrors {@link ExplosionMath#damageAt}'s sphere-shape falloff curve
	 * so a caller applies it along the direction from the blast centre to the victim.
	 */
	public static double explosionKnockbackFactor(double knockback, double distance, double radius) {
		if (knockback <= 0 || radius <= 0 || distance >= radius) return 0.0;
		return knockback * (1 - (distance / radius));
	}

}
