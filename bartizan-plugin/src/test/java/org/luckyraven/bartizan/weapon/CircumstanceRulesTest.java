package org.luckyraven.bartizan.weapon;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.support.WeaponFixtures;
import org.luckyraven.bartizan.api.weapon.GunWeapon;
import org.luckyraven.bartizan.api.weapon.dto.HandlingData;
import org.luckyraven.bartizan.api.weapon.dto.HandlingData.Circumstance;
import org.luckyraven.bartizan.api.weapon.dto.HandlingData.Rule;
import org.luckyraven.bartizan.api.weapon.dto.ScopeData;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers gate {@code HE} part a: {@code Shoot.Circumstance.<key>: deny | required} evaluation, given a mocked
 * {@link Player} and weapon state, in isolation from {@code WeaponInteract}.
 */
@DisplayName("CircumstanceRules.firstDenied")
class CircumstanceRulesTest {

	@Test
	@DisplayName("no HandlingData configured: never denies")
	void noHandlingData_neverDenies() {
		GunWeapon weapon = WeaponFixtures.gunWeapon(6, 1);
		Player    player = mock(Player.class);

		assertNull(CircumstanceRules.firstDenied(player, weapon));
	}

	@Test
	@DisplayName("no Circumstance rules configured: never denies")
	void noRulesConfigured_neverDenies() {
		GunWeapon weapon = WeaponFixtures.gunWeapon(6, 1);
		weapon.setHandlingData(new HandlingData());
		Player player = mock(Player.class);

		assertNull(CircumstanceRules.firstDenied(player, weapon));
	}

	@Test
	@DisplayName("Sprinting: deny — sprinting player is denied")
	void sneaking_deny_activeIsDenied() {
		GunWeapon weapon = WeaponFixtures.gunWeapon(6, 1);
		HandlingData handling = new HandlingData();
		handling.getCircumstances().put(Circumstance.SPRINTING, Rule.DENY);
		weapon.setHandlingData(handling);

		Player player = mock(Player.class);
		when(player.isSprinting()).thenReturn(true);

		assertEquals(Circumstance.SPRINTING, CircumstanceRules.firstDenied(player, weapon));
	}

	@Test
	@DisplayName("Sprinting: deny — a non-sprinting player passes")
	void sneaking_deny_inactivePasses() {
		GunWeapon weapon = WeaponFixtures.gunWeapon(6, 1);
		HandlingData handling = new HandlingData();
		handling.getCircumstances().put(Circumstance.SPRINTING, Rule.DENY);
		weapon.setHandlingData(handling);

		Player player = mock(Player.class);
		when(player.isSprinting()).thenReturn(false);

		assertNull(CircumstanceRules.firstDenied(player, weapon));
	}

	@Test
	@DisplayName("Zooming: required — an unscoped weapon is denied")
	void zooming_required_inactiveIsDenied() {
		GunWeapon weapon = WeaponFixtures.gunWeapon(6, 1);
		weapon.setScopeData(new ScopeData(4, false));
		HandlingData handling = new HandlingData();
		handling.getCircumstances().put(Circumstance.ZOOMING, Rule.REQUIRED);
		weapon.setHandlingData(handling);

		Player player = mock(Player.class);

		assertEquals(Circumstance.ZOOMING, CircumstanceRules.firstDenied(player, weapon));
	}

	@Test
	@DisplayName("Zooming: required — a scoped-in weapon passes")
	void zooming_required_activePasses() {
		GunWeapon weapon = WeaponFixtures.gunWeapon(6, 1);
		weapon.setScopeData(new ScopeData(4, true));
		HandlingData handling = new HandlingData();
		handling.getCircumstances().put(Circumstance.ZOOMING, Rule.REQUIRED);
		weapon.setHandlingData(handling);

		Player player = mock(Player.class);

		assertNull(CircumstanceRules.firstDenied(player, weapon));
	}

	@Test
	@DisplayName("Ammo_Empty: deny — an empty magazine is denied")
	void ammoEmpty_deny_emptyMagazineIsDenied() {
		GunWeapon weapon = WeaponFixtures.gunWeapon(6, 1);
		weapon.setCurrentMagCapacity(0);
		HandlingData handling = new HandlingData();
		handling.getCircumstances().put(Circumstance.AMMO_EMPTY, Rule.DENY);
		weapon.setHandlingData(handling);

		Player player = mock(Player.class);

		assertEquals(Circumstance.AMMO_EMPTY, CircumstanceRules.firstDenied(player, weapon));
	}

	@Test
	@DisplayName("In_Midair: deny — a grounded player passes (isOnGround true)")
	void inMidair_deny_groundedPasses() {
		GunWeapon weapon = WeaponFixtures.gunWeapon(6, 1);
		HandlingData handling = new HandlingData();
		handling.getCircumstances().put(Circumstance.IN_MIDAIR, Rule.DENY);
		weapon.setHandlingData(handling);

		Player player = mock(Player.class);
		when(player.isOnGround()).thenReturn(true);

		assertNull(CircumstanceRules.firstDenied(player, weapon));
	}

}
