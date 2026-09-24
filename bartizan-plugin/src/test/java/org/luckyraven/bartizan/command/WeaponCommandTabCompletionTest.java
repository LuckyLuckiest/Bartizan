package org.luckyraven.bartizan.command;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.Bartizan;
import org.luckyraven.bartizan.command.data.InformationManager;
import org.luckyraven.bartizan.configuration.WeaponAddon;
import org.luckyraven.bartizan.file.WeaponLoader;
import org.luckyraven.bartizan.weapon.WeaponManager;
import org.luckyraven.keystone.command.Command;
import org.luckyraven.keystone.command.CommandTabCompleter;
import org.luckyraven.keystone.persistence.FileHandler;
import org.mockito.MockedStatic;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * {@code /bartizan weapon give <player> <weapon> [amount]} must offer live values at every position through the
 * server-side {@link CommandTabCompleter} — the path every Brigadier free-text node routes back to (Keystone
 * KS-CM-15). Guards the argument-tree shape (sub-arguments attached bottom-up) against the framework's traversal.
 *
 * <p>BZ-CM-03: {@code weapon give}'s name completion must come from {@link WeaponAddon#getWeaponKeys()} (only
 * successfully-parsed weapons), not {@code WeaponLoader.getFiles()} (every {@code .yml} physically present in the
 * weapon folder) — {@code brokenweapon} below stands in for a weapon file that failed to parse: it is still on
 * disk (offered by {@code WeaponLoader}) but never registered (absent from {@code WeaponAddon}), so picking it
 * from tab-completion must not be possible.
 */
class WeaponCommandTabCompletionTest {

	@Test
	void giveOffersLiveValuesAtEveryPosition() throws Exception {
		InformationManager informationManager = mock(InformationManager.class);
		when(informationManager.getCommands()).thenReturn(Map.of());

		FileHandler ak47         = mock(FileHandler.class);
		FileHandler m4a1         = mock(FileHandler.class);
		FileHandler brokenWeapon = mock(FileHandler.class);
		when(ak47.getName()).thenReturn("ak47");
		when(m4a1.getName()).thenReturn("m4a1");
		when(brokenWeapon.getName()).thenReturn("brokenweapon");

		WeaponLoader weaponLoader = mock(WeaponLoader.class);
		doReturn(List.of(ak47, m4a1, brokenWeapon)).when(weaponLoader).getFiles();

		WeaponAddon weaponAddon = mock(WeaponAddon.class);
		Set<String> weaponKeys  = new LinkedHashSet<>(List.of("ak47", "m4a1"));
		when(weaponAddon.getWeaponKeys()).thenReturn(weaponKeys);

		Player steve = mock(Player.class);
		when(steve.getName()).thenReturn("Steve");

		CommandSender sender = mock(CommandSender.class);
		when(sender.hasPermission(anyString())).thenReturn(true);

		org.bukkit.command.Command bukkitCommand = mock(org.bukkit.command.Command.class);

		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
			Server        server        = mock(Server.class);
			PluginManager pluginManager = mock(PluginManager.class);
			when(server.getPluginManager()).thenReturn(pluginManager);
			when(pluginManager.getPermissions()).thenReturn(Collections.emptySet());
			bukkit.when(Bukkit::getServer).thenReturn(server);
			bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);
			bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(steve));

			WeaponCommand weapon = new WeaponCommand(mock(Bartizan.class), informationManager,
			                                         mock(WeaponManager.class), weaponAddon, weaponLoader);

			// Protected on Keystone's Command; the dispatcher calls it once the subclass constructor has returned.
			Method initializeArguments = Command.class.getDeclaredMethod("initializeArguments");
			initializeArguments.setAccessible(true);
			initializeArguments.invoke(weapon);

			CommandTabCompleter completer = new CommandTabCompleter(Map.of("weapon", weapon));

			assertEquals(List.of("get", "give", "help", "info", "list", "skin"),
			             complete(completer, sender, bukkitCommand, "weapon", ""));
			assertEquals(List.of("Steve"),
			             complete(completer, sender, bukkitCommand, "weapon", "give", ""));
			assertEquals(List.of("ak47", "m4a1"),
			             complete(completer, sender, bukkitCommand, "weapon", "give", "Steve", ""));
			assertEquals(List.of("<amount>"),
			             complete(completer, sender, bukkitCommand, "weapon", "give", "Steve", "ak47", ""));
		}
	}

	private static List<String> complete(CommandTabCompleter completer, CommandSender sender,
	                                     org.bukkit.command.Command bukkitCommand, String... args) {
		return completer.onTabComplete(sender, bukkitCommand, "bartizan", args);
	}
}
