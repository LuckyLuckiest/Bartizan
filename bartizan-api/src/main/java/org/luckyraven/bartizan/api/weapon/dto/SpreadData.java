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
	private double  boundMinimum;
	private double  boundMaximum;

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
