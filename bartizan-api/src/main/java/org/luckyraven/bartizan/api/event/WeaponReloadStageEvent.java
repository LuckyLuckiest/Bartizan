package org.luckyraven.bartizan.api.event;

import lombok.Getter;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.api.event.WeaponEvent;

/**
 * Fired by {@code Reload#enterStage} every time a reload in progress crosses into a new stage (weapons-roadmap.md
 * gate {@code HO}, staged reload) — {@code open}/{@code insert}/{@code close} for an {@code InstantReload}, or
 * {@code open}/one {@code insert} per shell/{@code close} for a {@code NumberedReload}. Never fired for the NPC
 * path (no {@link Player} to report).
 */
@Getter
public class WeaponReloadStageEvent extends WeaponEvent {

	private static final HandlerList handler = new HandlerList();

	private final Player player;
	/** 0-based index of the stage just entered — backs {@code %reload_stage%} (1-based for display). */
	private final int    stageIndex;
	/** Total stage count for this reload attempt — backs {@code %reload_stage_max%}. */
	private final int    stageCount;

	public WeaponReloadStageEvent(Weapon weapon, Player player, int stageIndex, int stageCount) {
		super(weapon);

		this.player     = player;
		this.stageIndex = stageIndex;
		this.stageCount = stageCount;
	}

	public static HandlerList getHandlerList() {
		return handler;
	}

	@Override
	public @NotNull HandlerList getHandlers() {
		return handler;
	}

}
