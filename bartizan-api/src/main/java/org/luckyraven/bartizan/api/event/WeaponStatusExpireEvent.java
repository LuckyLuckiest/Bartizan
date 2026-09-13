package org.luckyraven.bartizan.api.event;

import lombok.Getter;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.luckyraven.bartizan.api.weapon.Weapon;

/**
 * Fired by {@code StatusEffectService} whenever a victim's biological status stops being active — naturally
 * ({@link Reason#EXPIRED}), consumed away ({@link Reason#CURED}), on death, or on quit (weapons-roadmap.md gate
 * {@code HB}, §2.2). Not cancellable — the status is already gone by the time this fires.
 */
@Getter
public class WeaponStatusExpireEvent extends WeaponEvent {

	private static final HandlerList handler = new HandlerList();

	private final LivingEntity victim;
	private final Reason       reason;

	public WeaponStatusExpireEvent(Weapon weapon, LivingEntity victim, Reason reason) {
		super(weapon);

		this.victim = victim;
		this.reason = reason;
	}

	public static HandlerList getHandlerList() {
		return handler;
	}

	@Override
	@NotNull
	public HandlerList getHandlers() {
		return handler;
	}

	public enum Reason {
		EXPIRED,
		CURED,
		DEATH,
		QUIT
	}

}
