package org.luckyraven.bartizan.api.event;

import lombok.Getter;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.api.event.WeaponEvent;

/**
 * Fired by {@code ChargeController} on every charge-level increment for a charge-then-release weapon (biological,
 * gate {@code HB}; beam, gate {@code HC}). Not cancellable — the level has already been applied by the time this
 * fires.
 */
@Getter
public class WeaponChargeLevelEvent extends WeaponEvent {

	private static final HandlerList handler = new HandlerList();

	private final Player player;
	private final int    level;
	private final int    maxLevel;

	public WeaponChargeLevelEvent(Weapon weapon, Player player, int level, int maxLevel) {
		super(weapon);

		this.player   = player;
		this.level    = level;
		this.maxLevel = maxLevel;
	}

	public static HandlerList getHandlerList() {
		return handler;
	}

	@Override
	public @NotNull HandlerList getHandlers() {
		return handler;
	}

}
