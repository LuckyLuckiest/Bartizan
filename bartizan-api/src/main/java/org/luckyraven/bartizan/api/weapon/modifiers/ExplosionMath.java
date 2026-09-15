package org.luckyraven.bartizan.api.weapon.modifiers;

import org.bukkit.util.Vector;
import org.luckyraven.bartizan.api.weapon.dto.ExplosionData.Shape;

/**
 * Pure shape-aware AOE falloff math for gate {@code HI-a} (explosion parity) — shared by
 * {@code ExplosionHandler}'s damage and block-damage loops. Kept free of Bukkit world access so it stays
 * unit-testable without a running server, mirroring {@link DamageMath}.
 */
public final class ExplosionMath {

	private ExplosionMath() {
	}

	/**
	 * Falloff from the blast centre. {@code offset} is the victim's position minus the blast centre. The distance
	 * metric depends on {@code shape} — Euclidean length for {@link Shape#SPHERE}/{@link Shape#FLAT}, Chebyshev
	 * (max absolute axis component) for {@link Shape#CUBE}. {@link Shape#SPHERE}/{@link Shape#CUBE} taper linearly
	 * from {@code damage} at zero distance to {@code 0} at {@code radius} (the pre-{@code HI-a} rocket behaviour,
	 * e.g. {@code SteppedProjectileTask#falloffDamage}); {@link Shape#FLAT} deals the full {@code damage} anywhere
	 * inside {@code radius} and {@code 0} outside (the pre-{@code HI-a} grenade behaviour).
	 */
	public static double damageAt(Shape shape, double radius, double damage, Vector offset) {
		if (damage <= 0 || radius <= 0) return 0.0;

		double distance = distance(shape, offset);
		if (distance >= radius) return 0.0;

		return shape == Shape.FLAT ? damage : damage * (1 - (distance / radius));
	}

	/**
	 * @return {@code true} if {@code offset} (a position relative to the blast centre) falls within a sphere of
	 * 		{@code radius}.
	 */
	public static boolean sphereContains(double radius, Vector offset) {
		return offset.lengthSquared() <= radius * radius;
	}

	/**
	 * @return {@code true} if {@code offset} (a position relative to the blast centre) falls within a cube of
	 * 		side {@code 2 * radius} centred on the origin.
	 */
	public static boolean cubeContains(double radius, Vector offset) {
		return chebyshevDistance(offset) <= radius;
	}

	private static double distance(Shape shape, Vector offset) {
		return shape == Shape.CUBE ? chebyshevDistance(offset) : offset.length();
	}

	private static double chebyshevDistance(Vector offset) {
		return Math.max(Math.abs(offset.getX()), Math.max(Math.abs(offset.getY()), Math.abs(offset.getZ())));
	}

}
