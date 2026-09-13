package org.luckyraven.bartizan.api.event;

import lombok.Getter;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.luckyraven.bartizan.api.weapon.Weapon;

/**
 * Fired by {@code BeamAction} immediately before a beam's ray is cast (weapons-roadmap.md gate {@code HC}, §3).
 * Cancelling suppresses the shot entirely; ammo already consumed for the shot is not refunded.
 *
 * <p><strong>Deviation from the roadmap:</strong> weapons-roadmap.md §3.2 describes this event as carrying "level
 * and the ordered target list". The beam ray streams hits one at a time through
 * {@code RaytraceRequest#getImpactHandler()} rather than pre-computing a target list before firing, so there is no
 * target list to carry — this event fires <em>before</em> the ray with only {@link #level}, {@link #origin} and
 * {@link #direction}.
 */
@Getter
public class WeaponBeamFireEvent extends WeaponEvent implements Cancellable {

	private static final HandlerList handler = new HandlerList();

	private final Player   player;
	private final int      level;
	private final Location origin;
	private final Vector   direction;

	private boolean cancelled;

	public WeaponBeamFireEvent(Weapon weapon, Player player, int level, Location origin, Vector direction) {
		super(weapon);

		this.player    = player;
		this.level     = level;
		this.origin    = origin;
		this.direction = direction;
	}

	public static HandlerList getHandlerList() {
		return handler;
	}

	@Override
	public boolean isCancelled() {
		return cancelled;
	}

	@Override
	public void setCancelled(boolean cancel) {
		this.cancelled = cancel;
	}

	@Override
	public @NotNull HandlerList getHandlers() {
		return handler;
	}

}
