package org.luckyraven.bartizan.api.weapon.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.luckyraven.keystone.exception.PluginException;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BiologicalData implements Cloneable {

	private ChargeData   charge;
	private List<String> effectsPerLevel;
	/**
	 * Maximum raytrace distance for the released shot, in blocks.
	 */
	private double       range;
	/**
	 * Damage dealt at charge level 1. Each additional level multiplies this value linearly (level N →
	 * {@code N * baseDamage}).
	 */
	private double       baseDamage;
	/**
	 * The tracked status a hit applies — never {@code null}; a weapon with no {@code Shoot.Status:} block still gets
	 * a minimal default (weapons-roadmap.md gate {@code HB} §2.1).
	 */
	private StatusData   status;
	/**
	 * {@code Shoot.Cumulative_Levels}: when {@code true}, a hit at charge level N applies every potion effect from
	 * levels {@code 1..N} merged (strongest amplifier, longest duration per potion type) instead of just level N's
	 * entry.
	 */
	private boolean      cumulativeLevels;

	@Override
	public BiologicalData clone() {
		try {
			BiologicalData clone = (BiologicalData) super.clone();
			clone.effectsPerLevel = new ArrayList<>(effectsPerLevel);
			clone.charge = charge != null ? charge.clone() : null;
			clone.status = status != null ? status.clone() : null;
			return clone;
		} catch (CloneNotSupportedException exception) {
			throw new PluginException(exception);
		}
	}

}
