package org.luckyraven.bartizan.listener;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.combat.CombatEligibility;
import org.luckyraven.bartizan.api.raytrace.WeaponRaytracer;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.api.weapon.dto.HandlingData;
import org.luckyraven.bartizan.api.weapon.modifiers.BlockDamageManager;
import org.luckyraven.bartizan.api.weapon.modifiers.WeaponBlockBreakEvent;
import org.luckyraven.bartizan.effect.EffectRunner;
import org.luckyraven.bartizan.fire.PluginFireRegistry;
import org.luckyraven.bartizan.scope.SpyglassScopeTask;
import org.luckyraven.bartizan.status.StatusEffectService;
import org.luckyraven.bartizan.weapon.WeaponService;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BZ-RT-01: {@code onBlockBreak} cancelled every {@link BlockBreakEvent} fired by a player holding a weapon with
 * {@code Information.Cancel.Break_Blocks} (default {@code true}, no shipped weapon turns it off) — including the
 * synthetic {@link WeaponBlockBreakEvent} {@link BlockDamageManager} now fires for a weapon-caused break, whose
 * player is always the shooter holding that very weapon. So by default Bartizan's own anti-mining listener vetoed
 * every weapon-caused block break against itself. Pins that {@code onBlockBreak} skips a {@link
 * WeaponBlockBreakEvent} outright, while still cancelling a real, hand-mined {@link BlockBreakEvent} exactly as
 * before.
 */
@DisplayName("WeaponInteract.onBlockBreak — BZ-RT-01")
class WeaponInteractBlockBreakTest {

	private final WeaponService weaponService = mock(WeaponService.class);

	private final WeaponInteract listener = new WeaponInteract(mock(JavaPlugin.class), weaponService,
			mock(WeaponRaytracer.class), mock(PluginFireRegistry.class), mock(CombatEligibility.class),
			mock(EffectRunner.class), mock(BlockDamageManager.class), mock(StatusEffectService.class),
			mock(SpyglassScopeTask.class));

	private Player playerHolding(ItemStack item) {
		Player          player    = mock(Player.class);
		PlayerInventory inventory = mock(PlayerInventory.class);
		when(player.getInventory()).thenReturn(inventory);
		when(inventory.getItemInMainHand()).thenReturn(item);
		return player;
	}

	@Test
	@DisplayName("a WeaponBlockBreakEvent (Bartizan's own synthetic break) is never cancelled by this listener")
	void weaponBlockBreakEvent_isSkipped_neverCancelled() {
		ItemStack item   = mock(ItemStack.class);
		Player    player = playerHolding(item);
		Block     block  = mock(Block.class);

		BlockBreakEvent event = new WeaponBlockBreakEvent(block, player);

		listener.onBlockBreak(event);

		assertFalse(event.isCancelled());
		// Confirms the early return - onBlockBreak never even asks whether the held item is a weapon.
		verify(weaponService, never()).isWeapon(item);
	}

	@Test
	@DisplayName("a real, hand-mined BlockBreakEvent is still cancelled while holding a Cancel.Break_Blocks weapon")
	void plainBlockBreakEvent_stillCancelled() {
		ItemStack item   = mock(ItemStack.class);
		Player    player = playerHolding(item);
		Block     block  = mock(Block.class);
		Weapon    weapon = mock(Weapon.class);

		when(weaponService.isWeapon(item)).thenReturn(true);
		when(weaponService.validateAndGetWeapon(player, item)).thenReturn(weapon);
		when(weapon.getHandlingData()).thenReturn(new HandlingData()); // Cancel.breakBlocks defaults to true

		BlockBreakEvent event = new BlockBreakEvent(block, player);

		listener.onBlockBreak(event);

		assertTrue(event.isCancelled());
	}

}
