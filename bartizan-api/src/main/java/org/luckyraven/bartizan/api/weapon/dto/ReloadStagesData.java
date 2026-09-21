package org.luckyraven.bartizan.api.weapon.dto;

import lombok.Getter;

/**
 * {@code Reload.Stages} (weapons-roadmap.md gate {@code HO}, staged reload): an ordered {@code open}/{@code
 * insert}/{@code close} split of an {@link InstantReload}'s {@code Reload.Cooldown}, plus the resume window shared
 * by both reload types. Resume is on by default — a weapon file with no {@code Reload.Stages} section still gets
 * {@link #defaults()} (a 60-tick resume window, shares matching the roadmap's example); a file sets
 * {@code Resume_Window: 0} to keep the old restart-from-zero behaviour.
 *
 * <p>{@code NumberedReload} ignores {@link #openShare}/{@link #insertShare}/{@link #closeShare} entirely — its
 * stages (one per shell) are derived from {@code Reload.Cooldown} and the number of insertions at reload time, not
 * from a share — but still reads {@link #resumeWindowTicks} for Phase 2.
 */
@Getter
public class ReloadStagesData {

	private static final double DEFAULT_OPEN_SHARE          = 0.25;
	private static final double DEFAULT_INSERT_SHARE         = 0.6;
	private static final double DEFAULT_CLOSE_SHARE          = 0.15;
	private static final int    DEFAULT_RESUME_WINDOW_TICKS  = 60;

	private final int    resumeWindowTicks;
	private final double openShare;
	private final double insertShare;
	private final double closeShare;

	private ReloadStagesData(int resumeWindowTicks, double openShare, double insertShare, double closeShare) {
		this.resumeWindowTicks = resumeWindowTicks;
		this.openShare         = openShare;
		this.insertShare       = insertShare;
		this.closeShare        = closeShare;
	}

	public static ReloadStagesData defaults() {
		return new ReloadStagesData(DEFAULT_RESUME_WINDOW_TICKS, DEFAULT_OPEN_SHARE, DEFAULT_INSERT_SHARE,
		                            DEFAULT_CLOSE_SHARE);
	}

	/**
	 * @param resumeWindowTicks raw, possibly negative {@code Reload.Stages.Resume_Window} — clamped to 0 (no
	 *                          resume, the pre-{@code HO} restart-from-zero behaviour) rather than rejected.
	 * @param openShare/insertShare/closeShare raw, possibly un-normalised {@code Reload.Stages.*.Share} values —
	 *                                          "shares are normalised, so they need not sum to one" (weapons-roadmap.md
	 *                                          gate {@code HO}). Non-positive input falls back to {@link #defaults()}'s
	 *                                          shares rather than dividing by zero.
	 */
	public static ReloadStagesData of(int resumeWindowTicks, double openShare, double insertShare,
	                                  double closeShare) {
		int clampedResumeWindow = Math.max(0, resumeWindowTicks);
		double sum = openShare + insertShare + closeShare;
		if (sum <= 0) {
			return new ReloadStagesData(clampedResumeWindow, DEFAULT_OPEN_SHARE, DEFAULT_INSERT_SHARE,
			                            DEFAULT_CLOSE_SHARE);
		}

		return new ReloadStagesData(clampedResumeWindow, openShare / sum, insertShare / sum, closeShare / sum);
	}

	/**
	 * @return {@code true} when {@link #resumeWindowTicks} is positive — {@code Resume_Window: 0} (or a negative
	 * 		value) keeps the pre-{@code HO} restart-from-zero behaviour.
	 */
	public boolean canResume() {
		return resumeWindowTicks > 0;
	}

	/**
	 * @return the {@code open} stage's width in {@code Reload.Cooldown} periods, for an {@link InstantReload} of
	 * 		{@code cooldownPeriods} total.
	 */
	public long openTicks(long cooldownPeriods) {
		return Math.round(cooldownPeriods * openShare);
	}

	/**
	 * @return the {@code insert} stage's width — see {@link #openTicks(long)}.
	 */
	public long insertTicks(long cooldownPeriods) {
		return Math.round(cooldownPeriods * insertShare);
	}

	/**
	 * @return the {@code close} stage's width: whatever is left of {@code cooldownPeriods} after {@link
	 * 		#openTicks(long)} and {@link #insertTicks(long)}, so the three always sum exactly to {@code
	 * 		cooldownPeriods} regardless of rounding.
	 */
	public long closeTicks(long cooldownPeriods) {
		return Math.max(0, cooldownPeriods - openTicks(cooldownPeriods) - insertTicks(cooldownPeriods));
	}

}
