package org.luckyraven.bartizan.api.weapon.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.luckyraven.keystone.exception.PluginException;

@Data
@NoArgsConstructor
public class ScopeData implements Cloneable {

	private int     level;
	private boolean scoped;
	// weapons-roadmap.md gate HP - slowness (default, today's behaviour) or spyglass (1.17+ vanilla zoom, guns
	// only; WeaponAddon falls this back to SLOWNESS itself when the server/Material don't support it).
	private ScopeType type = ScopeType.SLOWNESS;
	// weapons-roadmap.md gate HH
	private boolean nightVision;
	private int      zoomStacks   = 1;
	private int      zoomPerStack = 1;
	private int      shootDelayAfterScope;
	// Runtime state - which Zoom_Stacking stage is currently applied, 0 when unscoped.
	private int      currentStack;

	@Override
	public ScopeData clone() {
		try {
			return (ScopeData) super.clone();
		} catch (CloneNotSupportedException exception) {
			throw new PluginException(exception);
		}
	}

	/**
	 * @return the SLOWNESS amplifier for the current zoom stage: {@code Level + currentStack * Increase_Per_Stack},
	 * 		clamped to 5 - vanilla SLOWNESS at amplifier 6+ drops the move-speed multiplier to zero or negative,
	 * 		which can leave the player unable to move at all. {@code Increase_Per_Stack} is floored at 0 here too
	 * 		(the parser already floors it, but this guards direct {@code ScopeData} construction, e.g. tests).
	 */
	public int amplifier() {
		return Math.min(level + currentStack * Math.max(0, zoomPerStack), 5);
	}

	/**
	 * The {@code Zoom_Stacking} state transition behind {@code Weapon#cycleScope} (weapons-roadmap.md gate
	 * {@code HH}) - pure arithmetic, no potions touched, so it is testable without a live Bukkit registry.
	 * Unscoped -> scopes at stack 0 (returns {@code true}); scoped with a further stage available
	 * ({@code currentStack + 1 < zoomStacks}) -> steps to it (returns {@code true}); otherwise unscopes and resets
	 * the stack (returns {@code false}).
	 */
	public boolean advanceZoomStack() {
		if (!scoped) {
			scoped       = true;
			currentStack = 0;
			return true;
		}

		if (currentStack + 1 < zoomStacks) {
			currentStack++;
			return true;
		}

		scoped       = false;
		currentStack = 0;
		return false;
	}

}
