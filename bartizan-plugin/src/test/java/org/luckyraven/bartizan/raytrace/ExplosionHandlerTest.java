package org.luckyraven.bartizan.raytrace;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.weapon.dto.ExplosionData;
import org.luckyraven.bartizan.api.weapon.dto.ExplosionData.Exposure;

import java.util.function.BiPredicate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers {@link ExplosionHandler#isEligible} (gate {@code HI-a} review fix, finding 10) — the pure
 * {@code Owner_Immunity}/{@code Exposure} filter, with line-of-sight injected as a {@link BiPredicate} so it
 * needs no running server/world, mirroring {@code DamageRulesTest}/{@code HitZoneTest}'s mocking style.
 */
@DisplayName("ExplosionHandler.isEligible — owner immunity and exposure/line-of-sight gating")
class ExplosionHandlerTest {

	/** Fails the test if the loop ever consults line-of-sight for {@link Exposure#DISTANCE}. */
	private static final BiPredicate<Location, Location> NEVER_CALLED = (a, b) -> {
		throw new AssertionError("hasLineOfSight should not be consulted for Exposure.DISTANCE");
	};

	private LivingEntity entity() {
		return mock(LivingEntity.class);
	}

	@Test
	@DisplayName("Owner_Immunity: shooter targeting itself is never eligible")
	void ownerImmunity_shooterIsTarget_notEligible() {
		ExplosionData data = new ExplosionData();
		data.setOwnerImmunity(true);
		LivingEntity shooter = entity();

		assertFalse(ExplosionHandler.isEligible(data, shooter, shooter, mock(Location.class), NEVER_CALLED));
	}

	@Test
	@DisplayName("Owner_Immunity false (default): shooter can still hit itself")
	void ownerImmunityFalse_shooterIsTarget_eligible() {
		ExplosionData data    = new ExplosionData();
		LivingEntity  shooter = entity();

		assertTrue(ExplosionHandler.isEligible(data, shooter, shooter, mock(Location.class), NEVER_CALLED));
	}

	@Test
	@DisplayName("Exposure.DISTANCE (default): eligible without ever consulting the line-of-sight predicate")
	void distanceExposure_eligible_neverConsultsLineOfSight() {
		ExplosionData data    = new ExplosionData();
		LivingEntity  shooter = entity();
		LivingEntity  target  = entity();

		assertTrue(ExplosionHandler.isEligible(data, shooter, target, mock(Location.class), NEVER_CALLED));
	}

	@Test
	@DisplayName("Exposure.LINE_OF_SIGHT blocked: not eligible")
	void lineOfSight_blocked_notEligible() {
		ExplosionData data = new ExplosionData();
		data.setExposure(Exposure.LINE_OF_SIGHT);
		LivingEntity shooter = entity();
		LivingEntity target  = entity();
		Location     eyeLoc  = mock(Location.class);
		when(target.getEyeLocation()).thenReturn(eyeLoc);

		assertFalse(ExplosionHandler.isEligible(data, shooter, target, mock(Location.class), (centre, eye) -> false));
	}

	@Test
	@DisplayName("Exposure.LINE_OF_SIGHT clear: eligible")
	void lineOfSight_clear_eligible() {
		ExplosionData data = new ExplosionData();
		data.setExposure(Exposure.LINE_OF_SIGHT);
		LivingEntity shooter = entity();
		LivingEntity target  = entity();
		Location     eyeLoc  = mock(Location.class);
		when(target.getEyeLocation()).thenReturn(eyeLoc);

		assertTrue(ExplosionHandler.isEligible(data, shooter, target, mock(Location.class), (centre, eye) -> true));
	}

	@Test
	@DisplayName("null shooter (e.g. environmental explosion): always eligible regardless of Owner_Immunity")
	void nullShooter_alwaysEligible() {
		ExplosionData data = new ExplosionData();
		data.setOwnerImmunity(true);
		LivingEntity target = entity();

		assertTrue(ExplosionHandler.isEligible(data, null, target, mock(Location.class), NEVER_CALLED));
	}

}
