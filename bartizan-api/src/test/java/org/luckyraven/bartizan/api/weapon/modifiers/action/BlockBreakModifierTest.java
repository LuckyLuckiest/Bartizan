package org.luckyraven.bartizan.api.weapon.modifiers.action;

import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.weapon.modifiers.BreakMode;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * BZ-RT-13: {@code BlockDamageManager.applyDamage} divides by {@code hitsRequired} on every hit
 * ({@code (currentHits * MAX_DAMAGE_STAGE) / hitsRequired}), so a modifier built with {@code hitsRequired <= 0}
 * (e.g. a {@code Break_Blocks} entry like {@code "GLASS-0"}) threw {@code ArithmeticException} on the very first
 * hit. The compact constructor clamps at construction — the one place every caller (the config parser, and
 * {@code BeamAction}/{@code ExplosionHandler}'s hardcoded-{@code 1} call sites) routes through.
 */
class BlockBreakModifierTest {

	@Test
	@DisplayName("hitsRequired of 0 is clamped to 1")
	void zeroHits_clampedToOne() {
		BlockBreakModifier modifier = new BlockBreakModifier(Set.of(Material.GLASS), 0, BreakMode.RESTORE);

		assertEquals(1, modifier.hitsRequired());
	}

	@Test
	@DisplayName("a negative hitsRequired is clamped to 1")
	void negativeHits_clampedToOne() {
		BlockBreakModifier modifier = new BlockBreakModifier(Set.of(Material.GLASS), -5, BreakMode.RESTORE);

		assertEquals(1, modifier.hitsRequired());
	}

	@Test
	@DisplayName("a positive hitsRequired passes through unchanged")
	void positiveHits_unchanged() {
		BlockBreakModifier modifier = new BlockBreakModifier(Set.of(Material.GLASS), 5, BreakMode.RESTORE);

		assertEquals(5, modifier.hitsRequired());
	}

}
