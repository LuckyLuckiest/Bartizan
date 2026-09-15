package org.luckyraven.bartizan.api.weapon;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.support.WeaponFixtures;
import org.luckyraven.bartizan.api.weapon.dto.ScopeData;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Covers gate {@code HH}'s {@code Zoom_Stacking} state machine. {@link ScopeData#advanceZoomStack()}/{@link
 * ScopeData#amplifier()} are exercised directly - pure arithmetic, no potions touched - rather than through
 * {@link Weapon#cycleScope}/{@link Weapon#scope}, since {@code XPotion}'s static init touches Bukkit's live
 * potion registry and is not available in a plain unit test (see {@code BiologicalActionTest} for the same
 * constraint confirmed elsewhere in this codebase). {@link Weapon#cycleScope} is covered only for its
 * scopeless-weapon no-op path, which returns before ever touching a potion.
 */
@DisplayName("ScopeData.advanceZoomStack (Zoom_Stacking)")
class WeaponScopeTest {

	@Test
	@DisplayName("no Zoom_Stacking configured (zoomStacks=1): one press scopes in, the next unscopes")
	void advanceZoomStack_singleStack_scopesInThenUnscopes() {
		ScopeData scopeData = new ScopeData();
		scopeData.setLevel(4);

		assertTrue(scopeData.advanceZoomStack(), "first press scopes in");
		assertTrue(scopeData.isScoped());
		assertEquals(0, scopeData.getCurrentStack());
		assertEquals(4, scopeData.amplifier());

		assertFalse(scopeData.advanceZoomStack(), "second press unscopes - no further stage available");
		assertFalse(scopeData.isScoped());
		assertEquals(0, scopeData.getCurrentStack());
	}

	@Test
	@DisplayName("Zoom_Stacking configured: steps through every stage before unscoping")
	void advanceZoomStack_multipleStacks_stepsThroughStagesThenUnscopes() {
		// Kept under the amplifier()=5 cap (see amplifier_highLevelOrStacking_clampedTo5) so this test isolates
		// the stage-progression arithmetic from the clamp.
		ScopeData scopeData = new ScopeData();
		scopeData.setLevel(2);
		scopeData.setZoomStacks(3);
		scopeData.setZoomPerStack(1);

		assertTrue(scopeData.advanceZoomStack());
		assertEquals(0, scopeData.getCurrentStack());
		assertEquals(2, scopeData.amplifier());

		assertTrue(scopeData.advanceZoomStack());
		assertEquals(1, scopeData.getCurrentStack());
		assertEquals(3, scopeData.amplifier());

		assertTrue(scopeData.advanceZoomStack());
		assertEquals(2, scopeData.getCurrentStack());
		assertEquals(4, scopeData.amplifier());

		assertFalse(scopeData.advanceZoomStack(), "past the last stage, the next press unscopes");
		assertFalse(scopeData.isScoped());
		assertEquals(0, scopeData.getCurrentStack());
	}

	@Test
	@DisplayName("amplifier() is clamped to 5 - vanilla SLOWNESS 6+ can freeze the player")
	void amplifier_highLevelOrStacking_clampedTo5() {
		ScopeData scopeData = new ScopeData();
		scopeData.setLevel(10);

		assertEquals(5, scopeData.amplifier(), "Level alone above the cap must still clamp");

		scopeData.setLevel(4);
		scopeData.setZoomStacks(5);
		scopeData.setZoomPerStack(3);

		assertTrue(scopeData.advanceZoomStack());
		assertTrue(scopeData.advanceZoomStack());
		assertEquals(5, scopeData.amplifier(), "Level + currentStack * Increase_Per_Stack above the cap must clamp");
	}

	@Test
	@DisplayName("cycleScope on a scopeless weapon is a no-op that reports false")
	void cycleScope_scopelessWeapon_isNoOpFalse() {
		GunWeapon weapon = WeaponFixtures.gunWeapon(6, 1);
		Player    player = mock(Player.class);

		assertFalse(weapon.cycleScope(player));
	}

}
