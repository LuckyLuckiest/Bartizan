package org.luckyraven.bartizan.api.wearable;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link Wearable#traitMaxLevel(String)} — the cap {@code WearableService#traitLevel} applies per piece and to the
 * total when summing a trait (e.g. {@code sealed}) across a full set of armor, so neither one over-configured
 * piece nor several stacked pieces can sum past the trait's intended ceiling (weapons-roadmap.md gate {@code HB}
 * review item 6).
 */
@DisplayName("Wearable.traitMaxLevel")
class WearableTraitMaxLevelTest {

	@Test
	@DisplayName("a known trait returns its configured TRAIT_TABLE max level")
	void knownTrait_returnsConfiguredMax() {
		assertEquals(3, Wearable.traitMaxLevel("sealed"));
		assertEquals(4, Wearable.traitMaxLevel("reinforced"));
	}

	@Test
	@DisplayName("an unknown trait key caps at 0")
	void unknownTrait_capsAtZero() {
		assertEquals(0, Wearable.traitMaxLevel("not_a_real_trait"));
	}

}
