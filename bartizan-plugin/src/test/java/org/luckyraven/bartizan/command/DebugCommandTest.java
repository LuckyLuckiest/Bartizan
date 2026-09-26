package org.luckyraven.bartizan.command;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.Bartizan;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.command.data.InformationManager;
import org.luckyraven.bartizan.weapon.WeaponManager;
import org.luckyraven.keystone.command.Command;
import org.luckyraven.keystone.command.argument.Argument;
import org.mockito.MockedStatic;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * gi=67: {@code /bartizan debug weapon}'s Argument callback only loops over
 * {@code weaponManager.getWeapons().values()}, so right after boot - before any weapon item has been touched, per
 * {@code WeaponManager}'s registry javadoc - the loop body never runs and the command replies with nothing at all.
 */
class DebugCommandTest {

	private static Argument weaponArgument(WeaponManager weaponManager) throws Exception {
		InformationManager informationManager = mock(InformationManager.class);
		when(informationManager.getCommands()).thenReturn(Map.of());

		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
			Server        server        = mock(Server.class);
			PluginManager pluginManager = mock(PluginManager.class);
			when(pluginManager.getPermissions()).thenReturn(Collections.emptySet());
			when(server.getPluginManager()).thenReturn(pluginManager);
			bukkit.when(Bukkit::getServer).thenReturn(server);
			bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);

			DebugCommand command = new DebugCommand(mock(Bartizan.class), informationManager, weaponManager);

			Method initializeArguments = Command.class.getDeclaredMethod("initializeArguments");
			initializeArguments.setAccessible(true);
			initializeArguments.invoke(command);

			return command.getArgument().getNode().getChildren().get(0).getData();
		}
	}

	@Test
	@DisplayName("an empty weapon registry (nothing touched since boot) still replies instead of sending nothing")
	void weaponArgument_emptyRegistry_stillReplies() throws Exception {
		WeaponManager weaponManager = mock(WeaponManager.class);
		when(weaponManager.getWeapons()).thenReturn(Map.of());

		Argument      weapon = weaponArgument(weaponManager);
		CommandSender sender = mock(CommandSender.class);

		weapon.getAction().accept(weapon, sender, new String[]{"debug", "weapon"});

		verify(sender).sendMessage(anyString());
	}

	@Test
	@DisplayName("a non-empty registry still lists every weapon's uuid, unchanged")
	void weaponArgument_nonEmptyRegistry_listsUuids() throws Exception {
		Weapon weapon1 = mock(Weapon.class);
		UUID   uuid    = UUID.randomUUID();
		when(weapon1.getUuid()).thenReturn(uuid);

		Map<UUID, Weapon> weapons = new LinkedHashMap<>();
		weapons.put(uuid, weapon1);

		WeaponManager weaponManager = mock(WeaponManager.class);
		when(weaponManager.getWeapons()).thenReturn(weapons);

		Argument      weapon = weaponArgument(weaponManager);
		CommandSender sender = mock(CommandSender.class);

		weapon.getAction().accept(weapon, sender, new String[]{"debug", "weapon"});

		verify(sender).sendMessage(uuid.toString());
	}

}
