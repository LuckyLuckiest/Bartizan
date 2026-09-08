package org.luckyraven.bartizan.npc;

import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.weapon.GunWeapon;
import org.luckyraven.bartizan.api.weapon.SelectiveFire;
import org.luckyraven.bartizan.api.weapon.dto.ProjectileData;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins bartizan.md §1.6(8)'s NPC firing cadence, ported verbatim from
 * {@code cops-n-crooks/npc/NpcCombatDelegate.java}: SINGLE and BURST trigger exactly once per player call and stay
 * busy for the full scaled cooldown window; AUTO is hold-to-fire, re-triggering itself every
 * {@code max(cooldown, 5)} ticks (feedback_selective_fire_semantics). Also covers {@code tick()}: {@code isBusy()}
 * must go false again after exactly the right number of {@code tick()} calls — the check that catches an NPC that
 * fires once and then never again.
 *
 * <p><b>Bukkit-free by design</b> (bartizan.md B20 watch-out): {@link NpcWeaponControllerImpl} keeps the
 * Bukkit-touching side effects (the actual {@code WeaponShootEvent}/{@code WeaponRaytracer}/{@code SoundEffect}
 * call in {@code fireSingleRound}, and the {@code SequenceTimer} scheduling in {@code performBurstFire}) behind two
 * small package-private hooks, {@code fireRound(GunWeapon)} and {@code scheduleBurst(GunWeapon, int, int)}. This
 * test subclasses the controller and stubs both hooks, so only the pure cadence arithmetic (attack-cooldown scaling,
 * the busy/trigger-once gate, {@code tick()} decrementing) is exercised — no live Bukkit server, no real timer.
 *
 * <p>Never asserts on {@code aimErrorDegrees} — bartizan.md §1.6(8) establishes it has no effect on this path today
 * (it feeds only the vanilla bow/crossbow facing helpers, never {@code fireSingleRound}).
 */
class NpcWeaponCadenceTest {

	private static final double NO_RATE_SCALING = 1.0;

	@Test
	void single_firesOnceAndStaysBusyForScaledCooldown() {
		int perShot  = 3;
		int cooldown = 4;
		GunWeapon gun = mockGun(SelectiveFire.SINGLE, perShot, cooldown);
		RecordingController controller = newController(gun, NO_RATE_SCALING);
		LivingEntity target = mock(LivingEntity.class);

		// One trigger per shot.
		assertTrue(controller.tryFire(target), "first tryFire should fire");
		assertEquals(1, controller.fireRoundCalls, "exactly one round fired for one SINGLE trigger");
		assertTrue(controller.isBusy(), "busy immediately after firing");

		// A second tryFire in the same window must NOT fire again.
		assertFalse(controller.tryFire(target), "a second tryFire within the busy window must return false");
		assertEquals(1, controller.fireRoundCalls, "still only one round fired — no extra trigger");

		// Busy for max(perShot * cooldown, 5) = max(12, 5) = 12 ticks.
		int expectedBusyTicks = Math.max(perShot * cooldown, 5);
		for (int i = 0; i < expectedBusyTicks - 1; i++) {
			controller.tick();
			assertTrue(controller.isBusy(), "still busy after tick #" + (i + 1) + " of " + expectedBusyTicks);
		}
		controller.tick();
		assertFalse(controller.isBusy(), "no longer busy after the full " + expectedBusyTicks + "-tick window");

		// And now a fresh trigger fires again.
		assertTrue(controller.tryFire(target));
		assertEquals(2, controller.fireRoundCalls);
	}

	@Test
	void burst_oneTriggerSchedulesPerShotRoundsAndStaysBusyForTotalTicks() {
		int perShot  = 3;
		int cooldown = 4;
		GunWeapon gun = mockGun(SelectiveFire.BURST, perShot, cooldown);
		RecordingController controller = newController(gun, NO_RATE_SCALING);
		LivingEntity target = mock(LivingEntity.class);

		// One trigger per burst — schedules perShot rounds exactly once.
		assertTrue(controller.tryFire(target), "first tryFire should schedule the burst");
		assertEquals(1, controller.scheduleBurstCalls, "exactly one burst scheduled for one BURST trigger");
		assertEquals(perShot, controller.lastBurstPerShot, "the scheduled burst must cover perShot rounds");
		assertEquals(cooldown, controller.lastBurstCooldown, "the scheduled burst must use the weapon's cooldown");

		// A second tryFire in the same window must NOT schedule another burst.
		assertFalse(controller.tryFire(target), "a second tryFire within the busy window must return false");
		assertEquals(1, controller.scheduleBurstCalls, "still only one burst scheduled — no extra trigger");

		// Busy for perShot * cooldown + cooldown = 3*4 + 4 = 16 ticks.
		int expectedBusyTicks = perShot * cooldown + cooldown;
		for (int i = 0; i < expectedBusyTicks - 1; i++) {
			controller.tick();
			assertTrue(controller.isBusy(), "still busy after tick #" + (i + 1) + " of " + expectedBusyTicks);
		}
		controller.tick();
		assertFalse(controller.isBusy(), "no longer busy after the full " + expectedBusyTicks + "-tick window");
	}

	@Test
	void auto_isHoldToFire_firingOncePerScaledCooldown() {
		int cooldown = 4;
		GunWeapon gun = mockGun(SelectiveFire.AUTO, 1, cooldown);
		RecordingController controller = newController(gun, NO_RATE_SCALING);
		LivingEntity target = mock(LivingEntity.class);

		int expectedInterval = Math.max(cooldown, 5); // 5
		int totalTicks        = expectedInterval * 3;  // three full cycles
		int firedCount         = 0;

		// tryFire is called on EVERY tick (hold-to-fire), not just once — this is what distinguishes AUTO from
		// SINGLE/BURST's one-trigger-per-call semantics.
		for (int i = 0; i < totalTicks; i++) {
			if (controller.tryFire(target)) {
				firedCount++;
			}
			controller.tick();
		}

		assertEquals(3, firedCount, "AUTO must fire exactly once per " + expectedInterval + "-tick window");
		assertEquals(3, controller.fireRoundCalls);
	}

	@Test
	void tick_neverGoesBusyForeverAfterASingleShot() {
		GunWeapon gun = mockGun(SelectiveFire.SINGLE, 1, 6);
		RecordingController controller = newController(gun, NO_RATE_SCALING);
		LivingEntity target = mock(LivingEntity.class);

		assertTrue(controller.tryFire(target));
		assertTrue(controller.isBusy());

		// max(1 * 6, 5) = 6 ticks to clear.
		for (int i = 0; i < 6; i++) {
			controller.tick();
		}
		assertFalse(controller.isBusy(), "a controller that fired once must not stay busy forever");

		// The NPC must be able to fire again — this is the regression the review's B3 finding named: a cop that
		// fires once and never again.
		assertTrue(controller.tryFire(target), "must be able to fire again once the cooldown has cleared");
	}

	// ---------------------------------------------------------------------------------------------------------------

	private static RecordingController newController(GunWeapon gun, double fireRateMultiplier) {
		JavaPlugin   plugin  = mock(JavaPlugin.class);
		LivingEntity shooter = mock(LivingEntity.class);
		// aimErrorDegrees is accepted and stored but must never affect this path (bartizan.md §1.6(8)) — a
		// deliberately nonzero, never-asserted-on value here would catch anyone who wires it in by mistake.
		return new RecordingController(plugin, shooter, gun, fireRateMultiplier, 15.0);
	}

	private static GunWeapon mockGun(SelectiveFire mode, int perShot, int cooldown) {
		GunWeapon gun = mock(GunWeapon.class);
		ProjectileData data = ProjectileData.builder()
				.perShot(perShot)
				.cooldown(cooldown)
				.speed(1)
				.damage(1)
				.consumed(1)
				.distance(10)
				.particle(false)
				.build();

		when(gun.getCurrentSelectiveFire()).thenReturn(mode);
		when(gun.getProjectileData()).thenReturn(data);
		when(gun.isBroken()).thenReturn(false);
		when(gun.isMagazineEmpty()).thenReturn(false);
		when(gun.isReloading()).thenReturn(false);

		return gun;
	}

	/**
	 * Test double: overrides the two Bukkit-touching hooks so the cadence arithmetic in
	 * {@link NpcWeaponControllerImpl} can be exercised with no live Bukkit server and no real timer.
	 */
	private static final class RecordingController extends NpcWeaponControllerImpl {

		int fireRoundCalls;
		int scheduleBurstCalls;
		int lastBurstPerShot = -1;
		int lastBurstCooldown = -1;

		RecordingController(JavaPlugin plugin, LivingEntity shooter, GunWeapon weapon, double fireRateMultiplier,
		                    double aimErrorDegrees) {
			super(plugin, shooter, weapon, fireRateMultiplier, aimErrorDegrees);
		}

		@Override
		boolean fireRound(GunWeapon gun) {
			fireRoundCalls++;
			return true;
		}

		@Override
		void scheduleBurst(GunWeapon gun, int perShot, int cooldown) {
			scheduleBurstCalls++;
			lastBurstPerShot  = perShot;
			lastBurstCooldown = cooldown;
		}
	}

}
