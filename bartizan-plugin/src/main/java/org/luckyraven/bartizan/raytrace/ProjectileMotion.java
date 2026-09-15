package org.luckyraven.bartizan.raytrace;

import org.bukkit.util.Vector;

/**
 * Pure per-tick flight maths for a stepped slow projectile (weapons-roadmap.md gate {@code HI} part b) — gravity
 * and drag integration plus the bounce reflection {@code SteppedProjectileTask} uses for
 * {@code Projectile.Bouncy}. No Bukkit world access, so it's unit-testable without mocking anything (see
 * {@code ProjectileMotionTest}).
 */
public final class ProjectileMotion {

	/** Below this speed (blocks/tick), a bounced projectile is considered at rest — rolling is skipped entirely. */
	public static final double REST_THRESHOLD = 0.05;

	private ProjectileMotion() {
	}

	/**
	 * Applies one tick of gravity (subtracted from the vertical component) then drag (a fractional speed loss) to
	 * {@code velocity}. {@code gravity}/{@code drag} of {@code 0.0} leaves the vector unchanged.
	 */
	public static Vector applyGravityAndDrag(Vector velocity, double gravity, double drag) {
		Vector next = velocity.clone();
		next.setY(next.getY() - gravity);
		return next.multiply(1 - drag);
	}

	/**
	 * Reflects {@code velocity} off a surface with the given unit {@code normal} — the same maths
	 * {@code WeaponRaytracerImpl} uses for bullet ricochet.
	 */
	public static Vector reflect(Vector velocity, Vector normal) {
		double dot = velocity.dot(normal);
		return velocity.clone().subtract(normal.clone().multiply(2 * dot));
	}

	/**
	 * Reflects {@code velocity} off {@code normal} and scales the result by the struck block's bounce
	 * {@code multiplier} ({@code Projectile.Bouncy}).
	 */
	public static Vector bounce(Vector velocity, Vector normal, double multiplier) {
		return reflect(velocity, normal).multiply(multiplier);
	}

	/**
	 * True once a bounced projectile is slow enough that simulating further rolling isn't worth it.
	 * <p>
	 * // ponytail: a naive speed-threshold rest check, no actual rolling/settling simulation — upgrade if a weapon
	 * ever needs a bounced projectile to visibly roll to a stop instead of just freezing.
	 */
	public static boolean atRest(Vector velocity) {
		return velocity.length() < REST_THRESHOLD;
	}

}
