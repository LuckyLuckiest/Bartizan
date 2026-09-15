package org.luckyraven.bartizan.api.raytrace;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.support.WeaponFixtures;
import org.luckyraven.bartizan.api.weapon.GunWeapon;
import org.luckyraven.bartizan.api.weapon.ProjectileState;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Pins the {@code hitEntity}/{@code terminalHit} distinction added by the HI-b review (finding #4): a penetrating
 * entity hit (a weapon with {@code Modifiers.Penetration}'s {@code Pierce_Entities}) sets {@code hitEntity} — the
 * flag {@code WeaponRaytracerImpl.fireInstant} reads to decide whether {@code EffectHook.ON_MISS} should fire — but
 * must NOT set {@code terminalHit}, the flag {@code SteppedProjectileTask} keys off to end a rocket/flare's flight.
 * Otherwise a piercing rocket would stop dead at the first entity it was configured to fly through.
 */
@DisplayName("RaytraceContext — hitEntity and terminalHit are independent flags")
class RaytraceContextTest {

	private RaytraceContext newContext() {
		GunWeapon    weapon  = WeaponFixtures.gunWeapon(30, 1);
		LivingEntity shooter = mock(LivingEntity.class);

		RaytraceRequest request = RaytraceRequest.builder()
		                                         .shooter(shooter)
		                                         .weapon(weapon)
		                                         .origin(new Location(null, 0, 0, 0))
		                                         .direction(new Vector(0, 0, 1))
		                                         .build();

		return new RaytraceContext(request, new ProjectileState(weapon, 10.0));
	}

	@Test
	@DisplayName("both flags default to false")
	void bothFlags_defaultFalse() {
		RaytraceContext ctx = newContext();

		assertFalse(ctx.isHitEntity());
		assertFalse(ctx.isTerminalHit());
	}

	@Test
	@DisplayName("hitEntity can be set (a penetrating hit) without terminalHit following")
	void hitEntity_doesNotImplyTerminalHit() {
		RaytraceContext ctx = newContext();

		ctx.setHitEntity(true);

		assertTrue(ctx.isHitEntity());
		assertFalse(ctx.isTerminalHit(), "a penetrating hit must not look terminal to SteppedProjectileTask");
	}

	@Test
	@DisplayName("terminalHit can be set independently (a non-penetrating hit)")
	void terminalHit_setsIndependently() {
		RaytraceContext ctx = newContext();

		ctx.setTerminalHit(true);

		assertTrue(ctx.isTerminalHit());
	}

}
