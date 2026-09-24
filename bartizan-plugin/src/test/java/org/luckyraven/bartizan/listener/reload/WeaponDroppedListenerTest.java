package org.luckyraven.bartizan.listener.reload;

import org.bukkit.Bukkit;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.api.weapon.dto.HandlingData;
import org.luckyraven.bartizan.weapon.WeaponService;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Bug docket BZ-EV-09 (Q-drop half): a plain, non-sneak drop of a scoped weapon leaks the same way the off-hand
 * swap does ({@code WeaponSelectiveFireChangeListenerTest} covers that half) - without a cleanup path, the player
 * stays permanently slowed once the weapon leaves the hand.
 */
@DisplayName("WeaponDroppedListener - BZ-EV-09 scope cleanup on an uncancelled drop")
class WeaponDroppedListenerTest {

	@Test
	@DisplayName("a plain drop of a scoped weapon with no Reload_Data (melee/throwable shape) unscopes it before "
			+ "it leaves the hand")
	void plainDrop_scopedWeapon_unscopesBeforeLeavingHand() {
		WeaponService          weaponService = mock(WeaponService.class);
		JavaPlugin             plugin        = mock(JavaPlugin.class);
		WeaponDroppedListener  listener      = new WeaponDroppedListener(plugin, weaponService);

		Player player = mock(Player.class);
		when(player.isSneaking()).thenReturn(false);

		// getHandlingData()/getReloadData() default null on an unstubbed mock: no Cancel.Drop_Item, no ammo - the
		// drop falls all the way through onPlayerDrop uncancelled (the melee/throwable "no Reload_Data" shape).
		Weapon weapon = mock(Weapon.class);

		Item      item      = mock(Item.class);
		ItemStack itemStack = mock(ItemStack.class);
		when(item.getItemStack()).thenReturn(itemStack);
		when(weaponService.validateAndGetWeapon(player, itemStack)).thenReturn(weapon);

		PlayerDropItemEvent event = new PlayerDropItemEvent(player, item);

		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
			bukkit.when(Bukkit::getPluginManager).thenReturn(mock(PluginManager.class));

			listener.onPlayerDrop(event);
			listener.onPlayerDropScopeCleanup(event);
		}

		assertFalse(event.isCancelled(), "a melee/throwable weapon with no Cancel.Drop_Item drops normally");
		verify(weapon).unScope(player, false);
	}

	@Test
	@DisplayName("Information.Cancel.Drop_Item keeps the drop cancelled - the weapon never leaves the hand and "
			+ "must not be unscoped")
	void cancelDropItem_weaponNeverLeavesHand_neverUnscoped() {
		WeaponService          weaponService = mock(WeaponService.class);
		JavaPlugin             plugin        = mock(JavaPlugin.class);
		WeaponDroppedListener  listener      = new WeaponDroppedListener(plugin, weaponService);

		Player player = mock(Player.class);
		when(player.isSneaking()).thenReturn(false);

		Weapon       weapon   = mock(Weapon.class);
		HandlingData handling = new HandlingData();
		handling.setCancel(new HandlingData.Cancel(true, false, false, false));
		when(weapon.getHandlingData()).thenReturn(handling);

		Item      item      = mock(Item.class);
		ItemStack itemStack = mock(ItemStack.class);
		when(item.getItemStack()).thenReturn(itemStack);
		when(weaponService.validateAndGetWeapon(player, itemStack)).thenReturn(weapon);

		PlayerDropItemEvent event = new PlayerDropItemEvent(player, item);

		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
			bukkit.when(Bukkit::getPluginManager).thenReturn(mock(PluginManager.class));

			listener.onPlayerDrop(event);
			listener.onPlayerDropScopeCleanup(event);
		}

		assertTrue(event.isCancelled(), "Cancel.Drop_Item must keep the drop cancelled");
		verify(weapon, never()).unScope(any(), anyBoolean());
	}

}
