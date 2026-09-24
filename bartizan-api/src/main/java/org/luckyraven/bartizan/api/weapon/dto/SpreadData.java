package org.luckyraven.bartizan.api.weapon.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.luckyraven.keystone.exception.PluginException;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SpreadData implements Cloneable {

	private double  start;
	private int     resetTime;
	private double  changeBase;
	private boolean resetOnBound;
	// BZ-WM-09: these used to default to primitive double's 0.0, which is a live, reachable bound (not
	// "unconfigured"). SpreadManager.updateSpread clamps against them unconditionally, so a weapon that
	// configures Spread.Change.Base without a matching Change.Bounds block had its spread silently pinned to 0
	// after the very first shot — the opposite of the configured inaccuracy. "Unbounded" defaults mean the clamp
	// branches in updateSpread simply never trigger until a weapon YAML actually sets a Bounds block.
	private double  boundMinimum = -Double.MAX_VALUE;
	private double  boundMaximum = Double.MAX_VALUE;

	// Spread.Modify_Spread_When: percent deltas (e.g. -50, 100) applied on top of currentSpread while the given
	// condition holds, summed then applied as one multiplier by SpreadManager.applySpread(Vector, double). 0 (the
	// default) is a no-op for that condition.
	private double  zoomingModifier;
	private double  sneakingModifier;
	private double  sprintingModifier;
	private double  inMidairModifier;
	private double  swimmingModifier;

	@Override
	public SpreadData clone() {
		try {
			return (SpreadData) super.clone();
		} catch (CloneNotSupportedException exception) {
			throw new PluginException(exception);
		}
	}

}
