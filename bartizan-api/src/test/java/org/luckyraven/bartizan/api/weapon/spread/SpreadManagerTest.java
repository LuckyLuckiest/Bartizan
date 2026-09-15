package org.luckyraven.bartizan.api.weapon.spread;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.weapon.dto.SpreadData;
import org.luckyraven.bartizan.api.support.WeaponFixtures;
import org.luckyraven.bartizan.api.weapon.GunWeapon;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link SpreadManager}'s spread accumulation, bounds clamping and reset behaviour (weapons.md W20 — Recoil
 * and spread).
 *
 * <p>Pins Observation #8 (weapons.md): {@code SpreadData.resetTime} is authored in YAML as {@code Time:} ticks
 * (e.g. {@code Time: 5}), but {@link SpreadManager#applySpread} compares it against a millisecond delta
 * ({@code System.currentTimeMillis()}). A shot fired even a few milliseconds after the previous one — which is
 * every real shot, since 5 ticks = 250 ms is far larger than the sub-millisecond gap between two calls in a test
 * (and larger than any human's real trigger cadence being mistaken for "no reset") — resets to
 * {@code Starting_Spread} instead of accumulating. This test proves the reset fires on back-to-back calls even
 * though 5 <i>ticks</i> have obviously not elapsed.
 */
@DisplayName("SpreadManager — spread accumulation, bounds, and the ticks-vs-milliseconds reset bug")
class SpreadManagerTest {

	private static SpreadData spreadData(double start, int resetTimeTicks, double changeBase, boolean resetOnBound,
	                                     double min, double max) {
		SpreadData data = new SpreadData();
		data.setStart(start);
		data.setResetTime(resetTimeTicks);
		data.setChangeBase(changeBase);
		data.setResetOnBound(resetOnBound);
		data.setBoundMinimum(min);
		data.setBoundMaximum(max);
		return data;
	}

	@Test
	@DisplayName("applySpread with no SpreadData configured returns the original vector unchanged")
	void applySpread_noSpreadData_returnsOriginal() {
		GunWeapon weapon = WeaponFixtures.gunWeapon(30, 1);
		SpreadManager manager = new SpreadManager(weapon);
		Vector original = new Vector(1, 0, 0);

		assertSame(original, manager.applySpread(original));
	}

	@Test
	@DisplayName("currentSpread starts at SpreadData.start")
	void currentSpread_startsAtConfiguredStart() {
		GunWeapon weapon = WeaponFixtures.gunWeapon(30, 1);
		weapon.setSpreadData(spreadData(0.05, 5, 0.02, false, 0.0, 0.5));
		SpreadManager manager = new SpreadManager(weapon);

		assertEquals(0.05, manager.getCurrentSpread());
	}

	@Test
	@DisplayName("Observation #8: resetTime authored as 'Time: 5' ticks (250ms) is compared as 5 milliseconds instead")
	void applySpread_resetTimeInTicks_resetsFarEarlierThanIntended() throws InterruptedException {
		GunWeapon weapon = WeaponFixtures.gunWeapon(30, 1);
		// Time: 5 in YAML is meant as "5 ticks" (250ms at 20 TPS) of no-fire before resetting — but the field is
		// compared against System.currentTimeMillis() deltas, i.e. as 5 *milliseconds*.
		weapon.setSpreadData(spreadData(0.0, 5, 0.10, false, 0.0, 1.0));
		SpreadManager manager = new SpreadManager(weapon);

		manager.applySpread(new Vector(0, 0, 1)); // first shot: 0.0 -> updateSpread -> 0.10
		assertEquals(0.10, manager.getCurrentSpread(), 0.0001);

		// Sleep 50ms: far short of the *intended* 250ms (5-tick) reset window, so correct tick semantics would
		// still be accumulating — but comfortably past the 5-millisecond threshold the code actually compares
		// against (and past Windows' coarse System.currentTimeMillis() tick granularity), so the reset fires
		// anyway.
		Thread.sleep(50);

		manager.applySpread(new Vector(0, 0, 1));
		assertEquals(0.10, manager.getCurrentSpread(), 0.0001,
		             "spread reset to Starting_Spread + one Change.Base after only ~50ms — nowhere near the "
		             + "5-tick (250ms) window the YAML author configured — instead of accumulating to 0.20");
	}

	@Test
	@DisplayName("updateSpread clamps at Bounds.Max without resetting when Reset_On_Bound is false")
	void applySpread_clampsAtMaxWithoutReset() {
		GunWeapon weapon = WeaponFixtures.gunWeapon(30, 1);
		// A very large resetTime keeps the millisecond-comparison bug (Observation #8) from firing within this
		// fast-running test, so the bounds-clamping logic can be exercised in isolation.
		weapon.setSpreadData(spreadData(0.0, Integer.MAX_VALUE, 1.0, false, 0.0, 0.5));
		SpreadManager manager = new SpreadManager(weapon);

		manager.applySpread(new Vector(0, 0, 1)); // 0.0 + 1.0 >= 0.5 -> clamp to boundMaximum
		assertEquals(0.5, manager.getCurrentSpread());

		manager.applySpread(new Vector(0, 0, 1)); // stays clamped
		assertEquals(0.5, manager.getCurrentSpread());
	}

	@Test
	@DisplayName("updateSpread resets to Starting_Spread at the bound when Reset_On_Bound is true")
	void applySpread_resetOnBound_resetsToStart() {
		GunWeapon weapon = WeaponFixtures.gunWeapon(30, 1);
		weapon.setSpreadData(spreadData(0.05, Integer.MAX_VALUE, 1.0, true, 0.0, 0.5));
		SpreadManager manager = new SpreadManager(weapon);

		manager.applySpread(new Vector(0, 0, 1)); // 0.05 + 1.0 >= 0.5 -> Reset_On_Bound -> back to Starting_Spread

		assertEquals(0.05, manager.getCurrentSpread());
	}

	@Test
	@DisplayName("applySpread(vector, multiplier) scales the effective spread without disturbing currentSpread's own accumulation")
	void applySpread_withMultiplier_scalesEffectiveSpreadOnly() {
		GunWeapon weapon = WeaponFixtures.gunWeapon(30, 1);
		// Large bound + reset window so only the multiplier scaling is under test, not clamping/reset.
		weapon.setSpreadData(spreadData(0.10, Integer.MAX_VALUE, 0.0, false, 0.0, 10.0));
		SpreadManager manager = new SpreadManager(weapon);

		// multiplier 0.0 collapses the random offset to zero -> direction passes through unchanged (after
		// normalize, still the unit vector it started as).
		Vector zeroed = manager.applySpread(new Vector(0, 0, 1), 0.0);
		assertEquals(new Vector(0, 0, 1), zeroed);

		// currentSpread itself is untouched by the multiplier - still 0.10 (changeBase is 0.0 here).
		assertEquals(0.10, manager.getCurrentSpread(), 0.0001);
	}

	@Test
	@DisplayName("applySpread(vector) with no multiplier argument is equivalent to multiplier 1.0")
	void applySpread_noMultiplierArg_matchesMultiplierOne() {
		// A non-zero starting spread, so the random offset actually matters here - with currentSpread == 0 the
		// multiplier is multiplied against zero either way and the assertion would hold no matter what the
		// no-arg overload actually delegated to. Two managers seeded identically so their Random draws the same
		// sequence, one exercised through the no-arg overload and the other through the explicit multiplier.
		SpreadData data = spreadData(0.2, Integer.MAX_VALUE, 0.0, false, 0.0, 10.0);

		GunWeapon noArgWeapon = WeaponFixtures.gunWeapon(30, 1);
		noArgWeapon.setSpreadData(data);
		SpreadManager noArgManager = new SpreadManager(noArgWeapon, new Random(42));

		GunWeapon explicitWeapon = WeaponFixtures.gunWeapon(30, 1);
		explicitWeapon.setSpreadData(data);
		SpreadManager explicitManager = new SpreadManager(explicitWeapon, new Random(42));

		assertEquals(noArgManager.applySpread(new Vector(0, 0, 1)),
		             explicitManager.applySpread(new Vector(0, 0, 1), 1.0));
	}

	@Test
	@DisplayName("resetSpread manually restores Starting_Spread")
	void resetSpread_restoresStart() {
		GunWeapon weapon = WeaponFixtures.gunWeapon(30, 1);
		weapon.setSpreadData(spreadData(0.02, Integer.MAX_VALUE, 1.0, false, 0.0, 5.0));
		SpreadManager manager = new SpreadManager(weapon);

		manager.applySpread(new Vector(0, 0, 1));
		assertTrue(manager.getCurrentSpread() > 0.02);

		manager.resetSpread();

		assertEquals(0.02, manager.getCurrentSpread());
	}

}
