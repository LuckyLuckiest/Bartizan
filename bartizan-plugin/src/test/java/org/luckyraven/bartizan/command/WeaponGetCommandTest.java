package org.luckyraven.bartizan.command;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.Bartizan;
import org.luckyraven.bartizan.file.BartizanMessages;
import org.luckyraven.bartizan.file.BartizanSettings;
import org.luckyraven.bartizan.file.WeaponLoader;
import org.luckyraven.bartizan.weapon.WeaponManager;
import org.luckyraven.keystone.command.argument.Argument;
import org.luckyraven.keystone.datastructure.Tree;
import org.luckyraven.keystone.message.MessageProvider;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * gi=60: same {@code %command%} placeholder gap as {@link WeaponGiveCommandTest}, in the sibling {@code weapon get}
 * command's {@code amount} sub-argument.
 */
class WeaponGetCommandTest {

	@AfterEach
	void tearDown() throws ReflectiveOperationException {
		Field provider = BartizanMessages.class.getDeclaredField("provider");
		provider.setAccessible(true);
		provider.set(null, null);

		Field moneySymbol = BartizanSettings.class.getDeclaredField("moneySymbol");
		moneySymbol.setAccessible(true);
		moneySymbol.set(null, null);
	}

	@Test
	@DisplayName("a non-numeric amount substitutes %command% instead of showing it literally")
	void nonNumericAmount_substitutesCommandPlaceholder() throws Exception {
		Field moneySymbol = BartizanSettings.class.getDeclaredField("moneySymbol");
		moneySymbol.setAccessible(true);
		moneySymbol.set(null, "$");

		MessageProvider provider = mock(MessageProvider.class);
		when(provider.getString("Errors.Must_Be_Numbers")).thenReturn("&a%command% &7must be numbers.");
		when(provider.getString("Errors.Prefix")).thenReturn("&4Error&7: ");
		when(provider.getString("Commands.Prefix")).thenReturn("&6Bartizan&7: ");
		BartizanMessages.init(provider);

		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
			Server        server        = mock(Server.class);
			PluginManager pluginManager = mock(PluginManager.class);
			when(pluginManager.getPermissions()).thenReturn(Collections.emptySet());
			when(server.getPluginManager()).thenReturn(pluginManager);
			bukkit.when(Bukkit::getServer).thenReturn(server);
			bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);

			Bartizan       bartizan = mock(Bartizan.class);
			Tree<Argument> tree     = new Tree<>();
			Argument       parent   = new Argument(bartizan, "weapon", tree);
			tree.add(parent.getNode());

			WeaponGetCommand command = new WeaponGetCommand(bartizan, tree, parent, mock(WeaponManager.class),
			                                                mock(WeaponLoader.class));

			Argument name   = command.getNode().getChildren().get(0).getData();
			Argument amount = name.getNode().getChildren().get(0).getData();

			Player player = mock(Player.class);
			amount.getAction().accept(amount, player, new String[]{"", "", "ak47", "abc"});

			ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
			verify(player).sendMessage(sent.capture());
			assertFalse(sent.getValue().contains("%command%"),
			            "expected %command% to be substituted, got: " + sent.getValue());
			assertTrue(sent.getValue().contains("abc"), "expected the bad token quoted: " + sent.getValue());
		}
	}

}
