package org.luckyraven.bartizan.api.weapon.dto;

import lombok.Builder;
import lombok.Getter;
import org.luckyraven.bartizan.api.weapon.reload.ReloadType;

@Getter
@Builder
public class ReloadData {

	private final int        cooldown;
	private final ReloadType type;
	/**
	 * {@code Reload.Unload_Ammo_On_Reload}: when {@code true} and the magazine isn't already empty, a reload first
	 * returns {@code floor(currentMag / restore)} ammo items to the player before reloading from empty. Skipped
	 * for NPCs and {@code Ammo_Type: none}.
	 */
	private final boolean    unloadAmmoOnReload;
	/**
	 * {@code Reload.Shoot_Delay_After_Reload}: ticks after a completed (non-interrupted) reload during which
	 * shooting is silently refused.
	 */
	private final int        shootDelayAfterReload;
	/**
	 * {@code Reload.Auto_Reload_When_Empty}: when {@code true}, firing on an empty magazine starts a reload
	 * automatically (if the player carries the configured ammo, or its type is {@code none}) instead of just
	 * playing the empty-mag click.
	 */
	private final boolean    autoReloadWhenEmpty;

	@Override
	public String toString() {
		return String.format("ReloadData{cooldown=%d,type=%s}", cooldown, type);
	}

}
