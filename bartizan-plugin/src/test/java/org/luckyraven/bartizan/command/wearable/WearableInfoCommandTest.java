package org.luckyraven.bartizan.command.wearable;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.Bartizan;
import org.luckyraven.bartizan.api.wearable.Wearable;
import org.luckyraven.bartizan.file.BartizanMessages;
import org.luckyraven.bartizan.file.BartizanSettings;
import org.luckyraven.bartizan.util.BartizanChatUtil;
import org.luckyraven.bartizan.wearable.WearableAddon;
import org.luckyraven.keystone.command.argument.Argument;
import org.luckyraven.keystone.datastructure.JsonFormatter;
import org.luckyraven.keystone.datastructure.Tree;
import org.luckyraven.keystone.message.MessageProvider;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * gi=61: {@code /bartizan wearable info <name>} advertised a name argument in {@code commands.json} (matching
 * {@code WeaponInfoCommand}/{@code AmmunitionInfoCommand}) that never existed on the argument tree, so a lookup
 * by name fell through to the framework's generic wrong-arguments rejection instead of this command.
 */
class WearableInfoCommandTest {

	@AfterEach
	void tearDown() throws ReflectiveOperationException {
		Field provider = BartizanMessages.class.getDeclaredField("provider");
		provider.setAccessible(true);
		provider.set(null, null);

		Field moneySymbol = BartizanSettings.class.getDeclaredField("moneySymbol");
		moneySymbol.setAccessible(true);
		moneySymbol.set(null, null);
	}

	private static void primeMoneySymbol() throws ReflectiveOperationException {
		Field moneySymbol = BartizanSettings.class.getDeclaredField("moneySymbol");
		moneySymbol.setAccessible(true);
		moneySymbol.set(null, "$");
	}

	/** Bukkit.getPluginManager() is needed only for SubArgument's constructor-time permission registration. */
	private static WearableInfoCommand build(Bartizan bartizan, Tree<Argument> tree, Argument parent,
	                                         WearableAddon wearableAddon) {
		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
			Server        server        = mock(Server.class);
			PluginManager pluginManager = mock(PluginManager.class);
			when(pluginManager.getPermissions()).thenReturn(Collections.emptySet());
			when(server.getPluginManager()).thenReturn(pluginManager);
			bukkit.when(Bukkit::getServer).thenReturn(server);
			bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);

			return new WearableInfoCommand(bartizan, tree, parent, wearableAddon);
		}
	}

	@Test
	@DisplayName("info registers a by-name sub-argument, mirroring the sibling *Info commands")
	void constructor_addsNameSubArgument() {
		Bartizan       bartizan      = mock(Bartizan.class);
		Tree<Argument> tree          = new Tree<>();
		Argument       parent        = new Argument(bartizan, "wearable", tree);
		tree.add(parent.getNode());
		WearableAddon  wearableAddon = mock(WearableAddon.class);

		WearableInfoCommand command = build(bartizan, tree, parent, wearableAddon);

		assertEquals(1, command.getNode().getChildren().size(),
		             "expected one 'name' sub-argument, matching WeaponInfoCommand/AmmunitionInfoCommand");
	}

	@Test
	@DisplayName("a registered name resolves without needing a held item")
	void nameArgument_registeredKey_sendsInfo() throws Exception {
		primeMoneySymbol();

		Bartizan       bartizan      = mock(Bartizan.class);
		Tree<Argument> tree          = new Tree<>();
		Argument       parent        = new Argument(bartizan, "wearable", tree);
		tree.add(parent.getNode());
		WearableAddon  wearableAddon = mock(WearableAddon.class);

		Wearable wearable = mock(Wearable.class);
		when(wearable.getWearableKey()).thenReturn("police_vest");
		when(wearable.getName()).thenReturn("&7Police Vest&r");
		when(wearable.getMaterial()).thenReturn(Material.IRON_CHESTPLATE);
		when(wearable.getBaseDamageReduction()).thenReturn(0.1);
		when(wearable.traits()).thenReturn(Set.of());
		when(wearable.isExternal()).thenReturn(false);
		when(wearableAddon.getWearable("police_vest")).thenReturn(wearable);

		WearableInfoCommand command = build(bartizan, tree, parent, wearableAddon);
		Argument             name   = command.getNode().getChildren().get(0).getData();

		Player player = mock(Player.class);
		name.getAction().accept(name, player, new String[]{"", "", "police_vest"});

		verify(player).sendMessage(anyString());
	}

	@Test
	@DisplayName("an unregistered name reports WEARABLE_NOT_REGISTERED instead of a generic wrong-arguments error")
	void nameArgument_unregisteredKey_reportsNotRegistered() throws Exception {
		primeMoneySymbol();

		MessageProvider provider = mock(MessageProvider.class);
		when(provider.getString("Errors.Prefix")).thenReturn("&4Error&7: ");
		when(provider.getString("Errors.Wearable.Not_Registered")).thenReturn("&cWearable '%key%' is not registered.");
		BartizanMessages.init(provider);

		Bartizan       bartizan      = mock(Bartizan.class);
		Tree<Argument> tree          = new Tree<>();
		Argument       parent        = new Argument(bartizan, "wearable", tree);
		tree.add(parent.getNode());
		WearableAddon  wearableAddon = mock(WearableAddon.class);
		when(wearableAddon.getWearable("missing")).thenReturn(null);

		WearableInfoCommand command = build(bartizan, tree, parent, wearableAddon);
		Argument             name   = command.getNode().getChildren().get(0).getData();

		Player        player = mock(Player.class);
		CommandSender sender = player;
		name.getAction().accept(name, sender, new String[]{"", "", "missing"});

		verify(player).sendMessage(anyString());
	}

	/**
	 * gi=63: {@link JsonFormatter#formatToJson} breaks a line at every unquoted comma - the traits joiner in
	 * {@link WearableInfoCommand#buildInfo} joins with {@code "&7, "}, so two or more traits split one wearable's
	 * Traits line into several.
	 */
	@Test
	@DisplayName("multiple traits survive the info formatter on one line")
	void multipleTraits_stayOnOneLine() throws Exception {
		primeMoneySymbol();

		Wearable wearable = mock(Wearable.class);
		when(wearable.getWearableKey()).thenReturn("police_vest");
		when(wearable.getName()).thenReturn("&7Police Vest&r");
		when(wearable.getMaterial()).thenReturn(Material.IRON_CHESTPLATE);
		when(wearable.getBaseDamageReduction()).thenReturn(0.1);
		when(wearable.traits()).thenReturn(new LinkedHashSet<>(List.of("fire_resist", "speed")));
		when(wearable.traitLevel("fire_resist")).thenReturn(1);
		when(wearable.traitLevel("speed")).thenReturn(2);

		String rendered = new JsonFormatter().formatToJson(
				BartizanChatUtil.color(WearableInfoCommand.buildInfo(wearable)), " ".repeat(3));

		// Key / Name / Material / Base Reduction / Traits = 5 lines when the traits clause stays on one line.
		assertEquals(5, rendered.lines().count(), rendered);
	}

}
