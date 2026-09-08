package org.luckyraven.bartizan.api.event;

import lombok.Getter;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.api.event.WeaponEvent;

@Getter
public class WeaponReloadStartEvent extends WeaponEvent {

	private static final HandlerList handler = new HandlerList();

	private final Player player;

	public WeaponReloadStartEvent(Weapon weapon, Player player) {
		super(weapon);

		this.player = player;
	}

	public static HandlerList getHandlerList() {
		return handler;
	}

	@Override
	public @NotNull HandlerList getHandlers() {
		return handler;
	}

}
