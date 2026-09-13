package org.luckyraven.bartizan.api.weapon.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.luckyraven.keystone.exception.PluginException;

/**
 * {@code Shoot.Beam} config for a {@code Category: beam} weapon (weapons-roadmap.md gate {@code HC}, §3.1) — burst
 * mode only; {@code Mode: sustained} is gate {@code HN}. The charge-then-release cycle itself is the shared
 * {@link ChargeData}, held separately on {@code BeamWeapon}.
 *
 * <p>The four nested records are immutable, so {@link #clone()} shares them by reference across copies — the same
 * pattern {@code BiologicalData} uses for anything that never mutates at runtime.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BeamData implements Cloneable {

	private double         range;
	private double         width;
	private int            ammoPerLevel;
	private PierceData     pierce;
	private BeamDamageData damage;
	private PreviewData    preview;
	private RenderData     render;
	private boolean        scorchBlocks;

	@Override
	public BeamData clone() {
		try {
			return (BeamData) super.clone();
		} catch (CloneNotSupportedException exception) {
			throw new PluginException(exception);
		}
	}

	/**
	 * @param entities entities the beam can pass through before stopping; {@code -1} means unlimited (lowered to
	 * 		{@link Integer#MAX_VALUE} on the {@code ModifiersData.penetration} it feeds).
	 * @param blocks penetrable blocks the beam can pass through.
	 * @param damageMultiplierPerTarget damage carried into the next target, per penetration (e.g. {@code 0.85} —
	 * 		lowered to a {@code damageReduction} of {@code 1 - damageMultiplierPerTarget} on the penetration modifier).
	 */
	public record PierceData(int entities, int blocks, double damageMultiplierPerTarget) {
	}

	/**
	 * @param base damage at charge level 1.
	 * @param perLevel damage added per level above 1.
	 * @param head bonus damage added on a headshot.
	 * @param knockback velocity magnitude applied along the beam direction on a hit.
	 * @param fireTicks fire ticks applied on a hit ({@code 0} = none).
	 */
	public record BeamDamageData(double base, double perLevel, double head, double knockback, int fireTicks) {
	}

	/**
	 * @param particle particle name drawn while charging.
	 * @param lengthPerLevel preview beam length per charge level, in blocks.
	 * @param interval ticks between preview redraws.
	 * @param guideLine whether a faint dotted line is also drawn out to {@link #range}.
	 */
	public record PreviewData(String particle, double lengthPerLevel, int interval, boolean guideLine) {
	}

	/**
	 * @param coreParticle particle drawn along the beam axis.
	 * @param coreColor {@code #RRGGBB} used when {@code coreParticle} resolves to a {@code DUST}-shaped particle.
	 * @param glowParticle particle drawn offset around the axis.
	 * @param thickness offset radius, in blocks, for {@code glowParticle} (and the dust size for {@code coreParticle}).
	 * @param step spacing, in blocks, between drawn points along the axis.
	 * @param duration ticks the render loop redraws the beam for.
	 */
	public record RenderData(String coreParticle, String coreColor, String glowParticle, double thickness,
	                         double step, int duration) {
	}

}
