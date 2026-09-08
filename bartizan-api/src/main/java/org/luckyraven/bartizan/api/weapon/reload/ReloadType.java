package org.luckyraven.bartizan.api.weapon.reload;

import lombok.Getter;
import lombok.Setter;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.api.ammo.Ammunition;
import org.luckyraven.bartizan.api.weapon.reload.InstantReload;
import org.luckyraven.bartizan.api.weapon.reload.NumberedReload;

@Getter
public enum ReloadType {

	INSTANT,
	ONE,
	NUM;

	@Setter
	private int amount;

	public static ReloadType getType(String type) {
		return switch (type.toLowerCase()) {
			case "one" -> ONE;
			case "num" -> NUM;
			default -> INSTANT;
		};
	}

	public Reload createInstance(Weapon weapon, Ammunition ammunition) {
		return switch (weapon.getReloadData().getType()) {
			case INSTANT -> new InstantReload(weapon, ammunition);
			case ONE, NUM -> new NumberedReload(weapon, ammunition, amount);
		};
	}

}
