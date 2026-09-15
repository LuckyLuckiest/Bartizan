package org.luckyraven.bartizan.api.event;

import lombok.Getter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Fired by {@code stats.StatsService} when a player who damaged {@code victim} within
 * {@code settings.yml Stats.Assist_Window_Ticks} of a kill they did not land themselves is credited with an assist
 * (weapons-roadmap.md gate {@code HK}). Not cancellable — the assist bookkeeping (the counter on the assister's
 * {@code WeaponStat}) has already happened by the time this fires.
 *
 * <p>Carries {@code weaponName} rather than a {@link org.luckyraven.bartizan.api.weapon.Weapon} — unlike
 * {@link WeaponEvent}'s other members, an assist is credited off a recorded hit that may be several ticks old, and
 * the weapon template it names is not otherwise needed here.
 */
@Getter
public class WeaponAssistEvent extends Event {

	private static final HandlerList handler = new HandlerList();

	private final String       weaponName;
	private final Player       assister;
	private final LivingEntity victim;

	@Nullable
	private final Entity killer;

	public WeaponAssistEvent(String weaponName, Player assister, LivingEntity victim, @Nullable Entity killer) {
		this.weaponName = weaponName;
		this.assister   = assister;
		this.victim     = victim;
		this.killer     = killer;
	}

	public static HandlerList getHandlerList() {
		return handler;
	}

	@Override
	@NotNull
	public HandlerList getHandlers() {
		return handler;
	}

}
