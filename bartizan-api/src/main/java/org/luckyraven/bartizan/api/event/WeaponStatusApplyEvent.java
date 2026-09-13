package org.luckyraven.bartizan.api.event;

import lombok.Getter;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.luckyraven.bartizan.api.weapon.Weapon;

/**
 * Fired by {@code StatusEffectService#apply} before a biological status is (re)applied to a victim
 * (weapons-roadmap.md gate {@code HB}, §2.2). Cancelling suppresses the whole application — no status bookkeeping,
 * no feedback, no potion payload.
 */
@Getter
public class WeaponStatusApplyEvent extends WeaponEvent implements Cancellable {

	private static final HandlerList handler = new HandlerList();

	@Nullable
	private final LivingEntity shooter;
	private final LivingEntity victim;
	private final int          level;

	private boolean cancelled;

	public WeaponStatusApplyEvent(Weapon weapon, @Nullable LivingEntity shooter, LivingEntity victim, int level) {
		super(weapon);

		this.shooter = shooter;
		this.victim  = victim;
		this.level   = level;
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
	@NotNull
	public HandlerList getHandlers() {
		return handler;
	}

}
