package org.luckyraven.bartizan.command;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.Bartizan;
import org.luckyraven.bartizan.ammo.AmmunitionManager;
import org.luckyraven.bartizan.api.ammo.Ammunition;
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
 * BZ-CM-01: {@code giveAmmunition} took the parsed amount straight into {@code new ItemStack[slots]} with no range
 * check. A negative amount drove {@code slots} negative (NegativeArraySizeException); a zero amount built a
 * zero-length array and still reported success with nothing handed over.
 */
class AmmunitionGiveCommandTest {

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
	 * Drives the private {@code AmmunitionGiveCommand.giveAmmunition(Player, String, int)} directly (constructing
	 * the command needs the full Keystone argument-tree scaffolding, same as {@link WeaponCommandTabCompletionTest})
	 * and returns the {@code ItemStack[]} handed to {@code PlayerInventory.addItem}.
	 */
	private ItemStack[] give(int amount) throws Exception {
		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
			Server        server        = mock(Server.class);
			PluginManager pluginManager = mock(PluginManager.class);
			when(pluginManager.getPermissions()).thenReturn(Collections.emptySet());
			when(server.getPluginManager()).thenReturn(pluginManager);
			bukkit.when(Bukkit::getServer).thenReturn(server);
			bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);

			Bartizan          bartizan          = mock(Bartizan.class);
			Tree<Argument>    tree              = new Tree<>();
			Argument          parent            = new Argument(bartizan, "ammo", tree);
			tree.add(parent.getNode());
			AmmunitionManager ammunitionManager = mock(AmmunitionManager.class);

			AmmunitionGiveCommand command = new AmmunitionGiveCommand(bartizan, tree, parent, ammunitionManager);

			Ammunition ammo = mock(Ammunition.class);
			when(ammunitionManager.getAmmunition("rifle_ammo")).thenReturn(ammo);

			ItemStack item = mock(ItemStack.class);
			when(item.getMaxStackSize()).thenReturn(64);
			when(ammo.buildItem(any())).thenReturn(item);

			Player          player    = mock(Player.class);
			PlayerInventory inventory = mock(PlayerInventory.class);
			when(player.getInventory()).thenReturn(inventory);
			when(inventory.addItem(any())).thenReturn(new HashMap<>());

			Method giveAmmunition = AmmunitionGiveCommand.class
					.getDeclaredMethod("giveAmmunition", Player.class, String.class, int.class);
			giveAmmunition.setAccessible(true);

			reported = (int) giveAmmunition.invoke(command, player, "rifle_ammo", amount);

			ArgumentCaptor<ItemStack[]> captor = ArgumentCaptor.forClass(ItemStack[].class);
			verify(inventory).addItem(captor.capture());
			return captor.getValue();
		}
	}

}
