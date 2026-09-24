package org.luckyraven.bartizan.api.weapon.modifiers;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;

/**
 * The {@link BlockBreakEvent} {@link BlockDamageManager} fires for a weapon-caused break (BZ-RT-01). It exists so a
 * <em>third-party</em> protection plugin (WorldGuard, GriefPrevention, ...) gets the same veto chance a hand-mined
 * block gets. It is a distinct subclass so Bartizan's own {@code WeaponInteract.onBlockBreak} listener — which
 * cancels every {@link BlockBreakEvent} fired by a player holding a weapon configured with
 * {@code Information.Cancel.Break_Blocks} (default {@code true}) — can recognize and skip its own synthetic event
 * instead of vetoing it against itself.
 */
public class WeaponBlockBreakEvent extends BlockBreakEvent {

	public WeaponBlockBreakEvent(Block block, Player player) {
		super(block, player);
	}

}
