package org.luckyraven.bartizan.api.weapon.modifiers;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.weapon.dto.DropoffStep;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pure-math coverage for {@link DamageMath} (weapons-roadmap.md gate {@code HF}).
 */
@DisplayName("DamageMath — dropoff, percent multiplier, explosion knockback")
class DamageMathTest {

	private static final List<DropoffStep> STEPS =
			List.of(new DropoffStep(20, -5), new DropoffStep(60, -15), new DropoffStep(100, -25));

	@Test
	@DisplayName("dropoff: below the first configured distance applies no delta")
	void dropoff_belowFirstDistance_isZero() {
		assertEquals(0.0, DamageMath.dropoff(STEPS, 5.0), 1e-9);
		assertEquals(0.0, DamageMath.dropoff(STEPS, 19.99), 1e-9);
	}

	@Test
	@DisplayName("dropoff: exact boundary applies that step's delta")
	void dropoff_exactBoundary_appliesStep() {
		assertEquals(-5.0, DamageMath.dropoff(STEPS, 20.0), 1e-9);
		assertEquals(-15.0, DamageMath.dropoff(STEPS, 60.0), 1e-9);
	}

	@Test
	@DisplayName("dropoff: the largest distance <= the shot distance wins")
	void dropoff_pickLargestDistanceBelowOrEqual() {
		assertEquals(-5.0, DamageMath.dropoff(STEPS, 45.0), 1e-9);
		assertEquals(-15.0, DamageMath.dropoff(STEPS, 99.0), 1e-9);
		assertEquals(-25.0, DamageMath.dropoff(STEPS, 500.0), 1e-9);
	}

	@Test
	@DisplayName("dropoff: empty or null list is always zero")
	void dropoff_emptyOrNull_isZero() {
		assertEquals(0.0, DamageMath.dropoff(List.of(), 50.0), 1e-9);
		assertEquals(0.0, DamageMath.dropoff(null, 50.0), 1e-9);
	}

	@Test
	@DisplayName("percentMultiplier: 0 sum -> 1x, positive/negative sums scale linearly")
	void percentMultiplier_scalesLinearly() {
		assertEquals(1.0, DamageMath.percentMultiplier(0), 1e-9);
		assertEquals(1.5, DamageMath.percentMultiplier(50), 1e-9);
		assertEquals(0.8, DamageMath.percentMultiplier(-20), 1e-9);
	}

	@Test
	@DisplayName("percentMultiplier: floored at 0 — a large negative sum never goes negative")
	void percentMultiplier_flooredAtZero() {
		assertEquals(0.0, DamageMath.percentMultiplier(-200), 1e-9);
	}

	@Test
	@DisplayName("explosionKnockbackFactor: full knockback at the blast centre, tapering to 0 at the radius")
	void explosionKnockbackFactor_taper() {
		assertEquals(10.0, DamageMath.explosionKnockbackFactor(10.0, 0.0, 4.0), 1e-9);
		assertEquals(5.0, DamageMath.explosionKnockbackFactor(10.0, 2.0, 4.0), 1e-9);
		assertEquals(0.0, DamageMath.explosionKnockbackFactor(10.0, 4.0, 4.0), 1e-9);
	}

	@Test
	@DisplayName("explosionKnockbackFactor: zero/negative knockback or radius is always 0")
	void explosionKnockbackFactor_zeroInputs() {
		assertEquals(0.0, DamageMath.explosionKnockbackFactor(0.0, 1.0, 4.0), 1e-9);
		assertEquals(0.0, DamageMath.explosionKnockbackFactor(10.0, 1.0, 0.0), 1e-9);
	}

	@Test
	@DisplayName("DropoffStep.parse: malformed entries return null")
	void dropoffStep_parse_malformedReturnsNull() {
		assertNull(DropoffStep.parse(null));
		assertNull(DropoffStep.parse("20"));
		assertNull(DropoffStep.parse("twenty -2"));
		assertNull(DropoffStep.parse("20 -2 extra"));
	}

	@Test
	@DisplayName("DropoffStep.parse: a well-formed entry round-trips")
	void dropoffStep_parse_wellFormed() {
		DropoffStep step = DropoffStep.parse("20 -2");
		assertEquals(20.0, step.distance(), 1e-9);
		assertEquals(-2.0, step.delta(), 1e-9);
	}

}
