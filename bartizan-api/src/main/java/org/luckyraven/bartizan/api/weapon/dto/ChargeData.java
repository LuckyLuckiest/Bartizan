package org.luckyraven.bartizan.api.weapon.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.luckyraven.keystone.exception.PluginException;

/**
 * Shared charge-then-release config — biological weapons (gate {@code HB}) and beam weapons (gate {@code HC}) both
 * gate their release on this (weapons-roadmap.md gate {@code HB} §2.1, gate {@code HC} §3.1).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChargeData implements Cloneable {

	private int     timePerLevel;
	private int     maxLevel;
	private int     minLevelToFire;
	private boolean autoFireAtMax;

	@Override
	public ChargeData clone() {
		try {
			return (ChargeData) super.clone();
		} catch (CloneNotSupportedException exception) {
			throw new PluginException(exception);
		}
	}

}
