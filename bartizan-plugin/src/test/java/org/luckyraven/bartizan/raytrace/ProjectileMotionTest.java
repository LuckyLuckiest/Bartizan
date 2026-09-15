package org.luckyraven.bartizan.raytrace;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure per-tick flight maths for a stepped slow projectile (weapons-roadmap.md gate {@code HI} part b) —
 * gravity/drag integration, bounce reflection, and the rest threshold.
 */
@DisplayName("ProjectileMotion — gravity/drag integration and bounce reflection (gate HI part b)")
class ProjectileMotionTest {

	@Test
	@DisplayName("gravity subtracts from Y, then drag scales the whole vector")
	void applyGravityAndDrag_gravityThenDrag() {
		Vector result = ProjectileMotion.applyGravityAndDrag(new Vector(1, 0, 0), 0.1, 0.5);

		assertEquals(0.5, result.getX(), 1e-9);
		assertEquals(-0.05, result.getY(), 1e-9, "gravity (-0.1) applied before the 0.5 drag scale");
		assertEquals(0.0, result.getZ(), 1e-9);
	}

	@Test
	@DisplayName("zero gravity and drag leaves velocity unchanged")
	void applyGravityAndDrag_zero_isUnchanged() {
		Vector result = ProjectileMotion.applyGravityAndDrag(new Vector(2, 3, -1), 0.0, 0.0);

		assertEquals(2.0, result.getX(), 1e-9);
		assertEquals(3.0, result.getY(), 1e-9);
		assertEquals(-1.0, result.getZ(), 1e-9);
	}

	@Test
	@DisplayName("reflecting off a flat floor flips only the vertical component")
	void reflect_offFlatFloor() {
		Vector result = ProjectileMotion.reflect(new Vector(1, -2, 0), new Vector(0, 1, 0));

		assertEquals(1.0, result.getX(), 1e-9);
		assertEquals(2.0, result.getY(), 1e-9);
		assertEquals(0.0, result.getZ(), 1e-9);
	}

	@Test
	@DisplayName("bounce reflects then scales by the block's multiplier")
	void bounce_scalesReflectedVelocity() {
		Vector result = ProjectileMotion.bounce(new Vector(0, -4, 0), new Vector(0, 1, 0), 0.5);

		assertEquals(2.0, result.getY(), 1e-9, "reflected 4 -> scaled by 0.5 -> 2");
	}

	@Test
	@DisplayName("atRest is true once speed drops below the threshold")
	void atRest_thresholdBehaviour() {
		assertTrue(ProjectileMotion.atRest(new Vector(0.01, 0, 0)));
		assertFalse(ProjectileMotion.atRest(new Vector(0.2, 0, 0)));
	}

}
