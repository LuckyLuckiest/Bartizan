package org.luckyraven.bartizan.api.weapon.modifiers;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.weapon.dto.ExplosionData.Shape;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-math coverage for {@link ExplosionMath} (gate {@code HI-a}) — pins the sphere falloff curve
 * {@code SteppedProjectileTask#falloffDamage} used before the unified {@code ExplosionHandler}, and the new cube
 * (Chebyshev) shape.
 */
@DisplayName("ExplosionMath — sphere/cube falloff and containment")
class ExplosionMathTest {

	@Test
	@DisplayName("sphere: full damage at the centre")
	void sphere_atCentre_isFullDamage() {
		assertEquals(50.0, ExplosionMath.damageAt(Shape.SPHERE, 4.0, 50.0, new Vector(0, 0, 0)), 1e-9);
	}

	@Test
	@DisplayName("sphere: linear taper by Euclidean distance")
	void sphere_taperIsLinearByDistance() {
		assertEquals(25.0, ExplosionMath.damageAt(Shape.SPHERE, 4.0, 50.0, new Vector(2, 0, 0)), 1e-9);
		assertEquals(12.5, ExplosionMath.damageAt(Shape.SPHERE, 4.0, 50.0, new Vector(3, 0, 0)), 1e-9);
	}

	@Test
	@DisplayName("sphere: outside the radius (including diagonally) is zero")
	void sphere_outsideRadius_isZero() {
		assertEquals(0.0, ExplosionMath.damageAt(Shape.SPHERE, 4.0, 50.0, new Vector(4, 0, 0)), 1e-9);
		// 3-4-5 triangle: length 5 even though every axis component is < 4.
		assertEquals(0.0, ExplosionMath.damageAt(Shape.SPHERE, 4.0, 50.0, new Vector(3, 4, 0)), 1e-9);
	}

	@Test
	@DisplayName("cube: full damage at the centre, taper by Chebyshev (max axis) distance")
	void cube_taperIsLinearByChebyshevDistance() {
		assertEquals(50.0, ExplosionMath.damageAt(Shape.CUBE, 4.0, 50.0, new Vector(0, 0, 0)), 1e-9);
		// max(|3|, |1|, |1|) = 3 -> 3/4 of the way to the radius, regardless of the other axes.
		assertEquals(12.5, ExplosionMath.damageAt(Shape.CUBE, 4.0, 50.0, new Vector(3, 1, 1)), 1e-9);
	}

	@Test
	@DisplayName("cube: a point outside on any single axis is zero even if Euclidean distance is smaller")
	void cube_outsideOnAnyAxis_isZero() {
		// Euclidean distance here is 4 (would still count for a sphere at exactly the boundary), but the cube's
		// Chebyshev distance is also 4 -> at the boundary -> zero either way; push one axis past the radius.
		assertEquals(0.0, ExplosionMath.damageAt(Shape.CUBE, 4.0, 50.0, new Vector(4.5, 0, 0)), 1e-9);
	}

	@Test
	@DisplayName("flat: full damage anywhere inside the radius, zero outside (gate HI-a grenade parity)")
	void flat_fullDamageInsideRadius_zeroOutside() {
		assertEquals(50.0, ExplosionMath.damageAt(Shape.FLAT, 4.0, 50.0, new Vector(0, 0, 0)), 1e-9);
		assertEquals(50.0, ExplosionMath.damageAt(Shape.FLAT, 4.0, 50.0, new Vector(3, 0, 0)), 1e-9);
		assertEquals(0.0, ExplosionMath.damageAt(Shape.FLAT, 4.0, 50.0, new Vector(4, 0, 0)), 1e-9);
	}

	@Test
	@DisplayName("zero/negative damage or radius is always zero")
	void zeroInputs_isZero() {
		assertEquals(0.0, ExplosionMath.damageAt(Shape.SPHERE, 0.0, 50.0, new Vector(0, 0, 0)), 1e-9);
		assertEquals(0.0, ExplosionMath.damageAt(Shape.SPHERE, 4.0, 0.0, new Vector(0, 0, 0)), 1e-9);
	}

	@Test
	@DisplayName("sphereContains / cubeContains boundaries")
	void containsBoundaries() {
		assertTrue(ExplosionMath.sphereContains(4.0, new Vector(4, 0, 0)));
		assertFalse(ExplosionMath.sphereContains(4.0, new Vector(3, 3, 0)));

		assertTrue(ExplosionMath.cubeContains(4.0, new Vector(4, 4, 4)));
		assertFalse(ExplosionMath.cubeContains(4.0, new Vector(4.1, 0, 0)));
	}

}
