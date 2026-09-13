package org.luckyraven.bartizan.weapon.action;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.support.WeaponFixtures;
import org.luckyraven.bartizan.api.weapon.GunWeapon;
import org.luckyraven.bartizan.api.weapon.ProjectileState;
import org.luckyraven.bartizan.api.weapon.dto.BeamData;
import org.luckyraven.bartizan.api.weapon.dto.ModifiersData;
import org.luckyraven.bartizan.api.weapon.modifiers.ModifierHandler;
import org.luckyraven.bartizan.api.weapon.modifiers.action.PenetrationModifier;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Covers {@link BeamAction}'s pure arithmetic only — {@link BeamAction#affordableLevel} and
 * {@link BeamAction#damageForLevel}, plus the real pierce-damage path a beam's {@code Pierce} lowers into: a
 * {@link PenetrationModifier} driven through {@link ModifierHandler#handleEntityPenetration} (weapons-roadmap.md
 * gate {@code HC}, §3). Everything else in {@code BeamAction} needs a live Bukkit world/scheduler and is exercised
 * through {@code WeaponAddonTest} loading {@code arc_lance.yml}/{@code ray_gun.yml} instead.
 */
@DisplayName("BeamAction — pure arithmetic")
class BeamActionTest {

	@Test
	@DisplayName("affordableLevel clamps to what the magazine can afford, 0 when even level 1 is unaffordable")
	void affordableLevel_clampsToMagazine() {
		assertEquals(4, BeamAction.affordableLevel(10, 2, 4), "10 ammo / 2 per level affords level 4 (needs 8)");
		assertEquals(2, BeamAction.affordableLevel(5, 2, 4), "5 ammo / 2 per level affords only level 2 (needs 4)");
		assertEquals(0, BeamAction.affordableLevel(1, 2, 4), "1 ammo can't even afford level 1 (needs 2)");
	}

	@Test
	@DisplayName("damageForLevel: Base + Per_Level * (level - 1)")
	void damageForLevel_scalesLinearly() {
		BeamData.BeamDamageData data = new BeamData.BeamDamageData(6.0, 5.0, 4.0, 0.8, 0);

		assertEquals(6.0, BeamAction.damageForLevel(data, 1));
		assertEquals(11.0, BeamAction.damageForLevel(data, 2));
		assertEquals(21.0, BeamAction.damageForLevel(data, 4));
	}

	@Test
	@DisplayName("a lowered Pierce (0.85 per-target multiplier, damageReduction = 0.15) reduces damage across "
			+ "successive ModifierHandler.handleEntityPenetration calls, matching what BeamWeaponParser.lowerPierce "
			+ "actually produces")
	void loweredPierce_reducesDamageAcrossSuccessiveHits() {
		GunWeapon weapon = WeaponFixtures.gunWeapon(30, 1);
		ModifiersData modifiers = new ModifiersData();
		modifiers.setPenetration(new PenetrationModifier(0, Integer.MAX_VALUE, 0.15));
		weapon.setModifiersData(modifiers);

		ProjectileState state = new ProjectileState(weapon, 10.0);
		assertEquals(10.0, state.getCurrentDamage(), 1e-9, "no penetration applied yet");

		ModifierHandler.handleEntityPenetration(state);
		assertEquals(8.5, state.getCurrentDamage(), 1e-9, "first target: 10.0 * 0.85");

		ModifierHandler.handleEntityPenetration(state);
		assertEquals(7.225, state.getCurrentDamage(), 1e-9, "second target: 8.5 * 0.85");
	}

}
