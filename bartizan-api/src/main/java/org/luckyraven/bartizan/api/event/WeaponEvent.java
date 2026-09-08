package org.luckyraven.bartizan.api.event;

import lombok.Getter;
import org.bukkit.event.Event;
import org.luckyraven.bartizan.api.weapon.Weapon;

@Getter
public abstract class WeaponEvent extends Event {

	private final Weapon weapon;

	public WeaponEvent(Weapon weapon) {
		this.weapon = weapon;
	}

}
