package org.luckyraven.bartizan.api.weapon.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.luckyraven.keystone.exception.PluginException;
import org.luckyraven.bartizan.api.ammo.Ammunition;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AmmunitionData implements Cloneable {

	private Ammunition ammoType;
	private int        maxMagCapacity;
	private int        consumeRate;
	private int        restore;

	@Override
	public AmmunitionData clone() {
		try {
			return (AmmunitionData) super.clone();
		} catch (CloneNotSupportedException exception) {
			throw new PluginException(exception);
		}
	}

}
