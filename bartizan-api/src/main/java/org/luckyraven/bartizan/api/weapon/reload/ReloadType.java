package org.luckyraven.bartizan.api.weapon.reload;

import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.api.ammo.Ammunition;
import org.luckyraven.bartizan.api.weapon.reload.InstantReload;
import org.luckyraven.bartizan.api.weapon.reload.NumberedReload;

public enum ReloadType {

	INSTANT,
	ONE,
	NUM;

	public static ReloadType getType(String type) {
		return switch (type.toLowerCase()) {
			case "one" -> ONE;
			case "num" -> NUM;
			default -> INSTANT;
		};
	}

	/**
	 * @param amount the per-weapon {@code num-N}/one-shell insertion amount, read off {@link
	 *               org.luckyraven.bartizan.api.weapon.dto.ReloadData#getAmount()} — never shared state on this
	 *               enum constant (BZ-WM-03: it used to be a mutable field here, so every weapon of the same
	 *               {@link ReloadType} silently collided on whichever weapon was parsed last).
	 */
	public Reload createInstance(Weapon weapon, Ammunition ammunition, int amount) {
		return switch (weapon.getReloadData().getType()) {
			case INSTANT -> new InstantReload(weapon, ammunition);
			case ONE, NUM -> new NumberedReload(weapon, ammunition, amount);
		};
	}

}
