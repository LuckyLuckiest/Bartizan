package org.luckyraven.bartizan.command;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.Bartizan;
import org.luckyraven.bartizan.configuration.WeaponAddon;
import org.luckyraven.bartizan.file.BartizanMessages;
import org.luckyraven.bartizan.file.BartizanSettings;
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
 * gi=60: the {@code amount} sub-argument's {@code NumberFormatException} catch sent {@code Must_Be_Numbers}'s
 * {@code %command%} placeholder unreplaced - drives the real {@code OptionalArgument} action lambda directly via
 * {@code Argument.getAction()}, no framework dispatch needed.
 */
class WeaponGiveCommandTest {

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

			WeaponGiveCommand command = new WeaponGiveCommand(bartizan, tree, parent, mock(WeaponManager.class),
			                                                  mock(WeaponAddon.class));

			Argument player = command.getNode().getChildren().get(0).getData();
			Argument weapon = player.getNode().getChildren().get(0).getData();
			Argument amount = weapon.getNode().getChildren().get(0).getData();

			CommandSender sender = mock(CommandSender.class);
			amount.getAction().accept(amount, sender, new String[]{"", "", "Steve", "ak47", "abc"});

			ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
			verify(sender).sendMessage(sent.capture());
			assertFalse(sent.getValue().contains("%command%"),
			            "expected %command% to be substituted, got: " + sent.getValue());
			assertTrue(sent.getValue().contains("abc"), "expected the bad token quoted: " + sent.getValue());
		}
	}

}
