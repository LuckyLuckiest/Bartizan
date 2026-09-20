package org.luckyraven.bartizan.api.weapon.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers weapons-roadmap.md gate {@code HO}'s "stage derivation" required test for an {@code InstantReload}:
 * {@link ReloadStagesData}'s open/insert/close tick split always sums exactly to the cooldown it was derived from,
 * defaults to the roadmap's example shares (0.25/0.6/0.15) when a file has no {@code Reload.Stages} section, and
 * normalises whatever {@code Share} values a file does configure.
 */
@DisplayName("ReloadStagesData")
class ReloadStagesDataTest {

	@Test
	@DisplayName("defaults(): resume is on (60 ticks) and shares match the roadmap's example")
	void defaults_resumeOnWithRoadmapShares() {
		ReloadStagesData stages = ReloadStagesData.defaults();

		assertEquals(60, stages.getResumeWindowTicks());
		assertTrue(stages.canResume());
		assertEquals(0.25, stages.getOpenShare(), 1e-9);
		assertEquals(0.6, stages.getInsertShare(), 1e-9);
		assertEquals(0.15, stages.getCloseShare(), 1e-9);
	}

	@Test
	@DisplayName("Resume_Window: 0 keeps the pre-HO restart-from-zero behaviour")
	void zeroResumeWindow_cannotResume() {
		ReloadStagesData stages = ReloadStagesData.of(0, 0.25, 0.6, 0.15);

		assertFalse(stages.canResume());
	}

	@Test
	@DisplayName("open/insert/close ticks always sum to the cooldown, for a cooldown the default shares split evenly")
	void ticks_sumToCooldown_evenSplit() {
		ReloadStagesData stages = ReloadStagesData.defaults();

		assertEquals(1, stages.openTicks(4));
		assertEquals(2, stages.insertTicks(4));
		assertEquals(1, stages.closeTicks(4));
		assertEquals(4, stages.openTicks(4) + stages.insertTicks(4) + stages.closeTicks(4));
	}

	@Test
	@DisplayName("open/insert/close ticks always sum to the cooldown, even a cooldown too small to split cleanly")
	void ticks_sumToCooldown_smallCooldown() {
		ReloadStagesData stages = ReloadStagesData.defaults();

		long cooldown = 1;
		long sum      = stages.openTicks(cooldown) + stages.insertTicks(cooldown) + stages.closeTicks(cooldown);

		assertEquals(cooldown, sum);
	}

	@Test
	@DisplayName("of(): shares are normalised, so they need not sum to one")
	void of_normalisesShares() {
		ReloadStagesData stages = ReloadStagesData.of(60, 1, 1, 1);

		assertEquals(1.0 / 3.0, stages.getOpenShare(), 1e-9);
		assertEquals(1.0 / 3.0, stages.getInsertShare(), 1e-9);
		assertEquals(1.0 / 3.0, stages.getCloseShare(), 1e-9);
	}

	@Test
	@DisplayName("of(): non-positive shares fall back to the roadmap's example instead of dividing by zero")
	void of_zeroShares_fallsBackToDefaults() {
		ReloadStagesData stages = ReloadStagesData.of(60, 0, 0, 0);

		assertEquals(0.25, stages.getOpenShare(), 1e-9);
		assertEquals(0.6, stages.getInsertShare(), 1e-9);
		assertEquals(0.15, stages.getCloseShare(), 1e-9);
	}

}
