package org.luckyraven.bartizan.command.wearable;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.Bartizan;
import org.luckyraven.bartizan.api.wearable.Wearable;
import org.luckyraven.bartizan.wearable.WearableAddon;
import org.luckyraven.keystone.command.argument.Argument;
import org.luckyraven.keystone.datastructure.Tree;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BZ-CM-01: {@code giveWearable} took the parsed amount straight into {@code new ItemStack[slots]} with no range
 * check, the same defect as {@code AmmunitionGiveCommand.giveAmmunition} (see
 * {@code AmmunitionGiveCommandTest}) — a negative amount threw {@code NegativeArraySizeException}, a zero amount
 * reported success while giving nothing.
 */
class WearableGiveCommandTest {

	/** What the helper reported handing over on the last {@link #give} - the amount the success message quotes. */
	private int reported;

	@Test
	@DisplayName("a negative amount is clamped instead of throwing NegativeArraySizeException")
	void negativeAmount_isClampedNotThrown() throws Exception {
		ItemStack[] given = give(-100);

		assertEquals(1, given.length, "a clamped negative amount must still hand over one stack");
		assertEquals(1, reported, "the success message must quote the clamped amount, not -100 (BZ-CM-01)");
	}

	@Test
	@DisplayName("a huge amount reports the clamped amount actually handed over")
	void hugeAmount_reportsClampedAmount() throws Exception {
		give(2_000_000_000);

		assertEquals(2304, reported, "the success message must quote the clamped amount (BZ-CM-01)");
	}

	@Test
	@DisplayName("a zero amount is clamped to 1 instead of silently giving nothing while reporting success")
	void zeroAmount_isClampedToOne() throws Exception {
		ItemStack[] given = give(0);

		assertEquals(1, given.length, "a clamped-to-1 give must hand over one stack, not zero");
	}

	/**
	 * Drives the private {@code WearableGiveCommand.giveWearable(Player, String, int)} directly and returns the
	 * {@code ItemStack[]} handed to {@code PlayerInventory.addItem}.
	 */
	private ItemStack[] give(int amount) throws Exception {
		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
			Server        server        = mock(Server.class);
			PluginManager pluginManager = mock(PluginManager.class);
			when(pluginManager.getPermissions()).thenReturn(Collections.emptySet());
			when(server.getPluginManager()).thenReturn(pluginManager);
			bukkit.when(Bukkit::getServer).thenReturn(server);
			bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);

			Bartizan       bartizan      = mock(Bartizan.class);
			Tree<Argument> tree          = new Tree<>();
			Argument       parent        = new Argument(bartizan, "wearable", tree);
			tree.add(parent.getNode());
			WearableAddon  wearableAddon = mock(WearableAddon.class);

			WearableGiveCommand command = new WearableGiveCommand(bartizan, tree, parent, wearableAddon);

			Wearable wearable = mock(Wearable.class);
			when(wearableAddon.getWearable("chestplate")).thenReturn(wearable);
			when(wearable.isExternal()).thenReturn(false);

			ItemStack item = mock(ItemStack.class);
			when(item.getMaxStackSize()).thenReturn(1);
			when(wearable.buildItem(any())).thenReturn(item);

			Player          player    = mock(Player.class);
			PlayerInventory inventory = mock(PlayerInventory.class);
			when(player.getInventory()).thenReturn(inventory);
			when(inventory.addItem(any())).thenReturn(new HashMap<>());

			Method giveWearable = WearableGiveCommand.class
					.getDeclaredMethod("giveWearable", Player.class, String.class, int.class);
			giveWearable.setAccessible(true);

			reported = (int) giveWearable.invoke(command, player, "chestplate", amount);

			ArgumentCaptor<ItemStack[]> captor = ArgumentCaptor.forClass(ItemStack[].class);
			verify(inventory).addItem(captor.capture());
			return captor.getValue();
		}
	}

}
