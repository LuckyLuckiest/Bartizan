package org.luckyraven.bartizan.api.event;

import lombok.Getter;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.api.event.WeaponEvent;

@Getter
public class WeaponReloadCompleteEvent extends WeaponEvent {

	private static final HandlerList handler = new HandlerList();

	private final Player  player;
	/**
	 * {@code true} when this completion was raised by a swap-cancelled reload ({@code Reload#endReloading(Player,
	 * boolean)} called with {@code true} from {@code InstantReload}/{@code NumberedReload#stopReloading}) rather
	 * than a normal reload finishing — {@code WeaponReloadListener#onReloadEnd} skips {@code ON_RELOAD_END} in
	 * that case so a cancelled reload doesn't also play the reload-complete feedback.
	 */
	private final boolean interrupted;

	public WeaponReloadCompleteEvent(Weapon weapon, Player player) {
		this(weapon, player, false);
	}

	public WeaponReloadCompleteEvent(Weapon weapon, Player player, boolean interrupted) {
		super(weapon);

		this.player      = player;
		this.interrupted = interrupted;
	}

	public static HandlerList getHandlerList() {
		return handler;
	}

	@Override
	public @NotNull HandlerList getHandlers() {
		return handler;
	}

}
