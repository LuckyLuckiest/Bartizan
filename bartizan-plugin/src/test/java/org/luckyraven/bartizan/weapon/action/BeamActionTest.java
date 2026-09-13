package org.luckyraven.bartizan.weapon.action;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.weapon.dto.BeamData;
import org.luckyraven.bartizan.api.weapon.modifiers.action.PenetrationModifier;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Covers {@link BeamAction}'s pure arithmetic only — {@link BeamAction#affordableLevel} and
 * {@link BeamAction#damageForLevel}, plus {@link PenetrationModifier#calculateDamage} at the penetration indices a
 * multi-target beam pierce actually produces (weapons-roadmap.md gate {@code HC}, §3). Everything else in
 * {@code BeamAction} needs a live Bukkit world/scheduler and is exercised through {@code WeaponAddonTest} loading
 * {@code arc_lance.yml}/{@code ray_gun.yml} instead.
 */
@DisplayName("BeamAction — pure arithmetic")
class BeamActionTest {

	@Test
	@DisplayName("affordableLevel clamps to what the magazine can afford, 0 when even level 1 is unaffordable")
	void affordableLevel_clampsToMagazine() {
		assertEquals(4, BeamAction.affordableLevel(10, 2, 4), "10 ammo / 2 per level affords level 4 (needs 8)");
		assertEquals(2, BeamAction.affordableLevel(5, 2, 4), "5 ammo / 2 per level affords only level 2 (needs 4)");
		assertEquals(0, BeamAction.affordableLevel(1, 2, 4), "1 ammo can't even afford level 1 (needs 2)");
		assertEquals(4, BeamAction.affordableLevel(0, 0, 4), "ammoPerLevel <= 0 is treated as free");
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
	@DisplayName("PenetrationModifier.calculateDamage at penetration index 0/1/2 for a 0.85 per-target multiplier "
			+ "(damageReduction = 1 - 0.85 = 0.15)")
	void penetrationModifier_appliesMultiplierPerIndex() {
		PenetrationModifier modifier = new PenetrationModifier(0, Integer.MAX_VALUE, 0.15);

		assertEquals(10.0, modifier.calculateDamage(10.0, 0), 1e-9);
		assertEquals(8.5, modifier.calculateDamage(10.0, 1), 1e-9);
		assertEquals(7.225, modifier.calculateDamage(10.0, 2), 1e-9);
	}

}
