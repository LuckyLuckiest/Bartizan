package org.luckyraven.bartizan.command.wearable;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.luckyraven.bartizan.Bartizan;
import org.luckyraven.keystone.command.argument.Argument;
import org.luckyraven.keystone.command.argument.SubArgument;
import org.luckyraven.keystone.command.argument.types.OptionalArgument;
import org.luckyraven.keystone.util.TriConsumer;
import org.luckyraven.keystone.datastructure.JsonFormatter;
import org.luckyraven.keystone.datastructure.Tree;
import org.luckyraven.bartizan.file.BartizanMessages;
import org.luckyraven.bartizan.wearable.WearableAddon;
import org.luckyraven.bartizan.api.wearable.Wearable;
import org.luckyraven.bartizan.util.BartizanChatUtil;

import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/**
 * {@code /bartizan wearable info [name]}. Traits are read via {@link Wearable#traits()} /
 * {@link Wearable#traitLevel(String)} (a lower-case string key, not the deleted {@code WearableTrait} enum —
 * bartizan.md §1.6(6)). Mirrors {@code WeaponInfoCommand}/{@code AmmunitionInfoCommand}'s shape: the held-item
 * path ({@link #action()}) and the by-name {@code name} sub-argument both funnel into {@link #sendInfo}.
 */
class WearableInfoCommand extends SubArgument {

	private final Bartizan       bartizan;
	private final Tree<Argument> tree;
	private final WearableAddon  wearableAddon;

	WearableInfoCommand(Bartizan bartizan, Tree<Argument> tree, Argument parent,
	                    WearableAddon wearableAddon) {
		super(bartizan, "info", tree, parent);

		this.bartizan      = bartizan;
		this.tree          = tree;
		this.wearableAddon = wearableAddon;

		wearableInfo();
	}

	@Override
	protected TriConsumer<Argument, CommandSender, String[]> action() {
		return (argument, sender, args) -> {
			Player player = (Player) sender;

			ItemStack itemStack = player.getInventory().getItemInMainHand();

			if (!Wearable.isRegisteredWearable(itemStack)) {
				player.sendMessage(BartizanMessages.WEARABLE_NOT_WEARABLE.toString());
				return;
			}

			String key = Wearable.getWearableKey(itemStack);

			if (key == null) return;

			Wearable wearable = wearableAddon.getWearable(key);

			// An external (WS7-D4) entry has no Material/Name/Lore of its own - treat it as unregistered here,
			// the same as no entry at all.
			if (wearable == null || wearable.isExternal()) {
				player.sendMessage(BartizanMessages.WEARABLE_NOT_REGISTERED.toString().replace("%key%", key));
				return;
			}

			sendInfo(player, wearable);
		};
	}

	private void wearableInfo() {
		OptionalArgument name = new OptionalArgument(bartizan, tree, (argument, sender, args) -> {
			Player player = (Player) sender;

			String   key      = args[2];
			Wearable wearable = wearableAddon.getWearable(key);

			if (wearable == null || wearable.isExternal()) {
				player.sendMessage(BartizanMessages.WEARABLE_NOT_REGISTERED.toString().replace("%key%", key));
				return;
			}

			sendInfo(player, wearable);
		}, sender -> wearableAddon.getWearables().entrySet().stream()
				.filter(entry -> !entry.getValue().isExternal())
				.map(Map.Entry::getKey).toList());

		name.setDisplayName("name");

		this.addSubArgument(name);
	}

	private void sendInfo(Player player, Wearable wearable) {
		JsonFormatter jsonFormatter = new JsonFormatter();
		player.sendMessage(jsonFormatter.formatToJson(BartizanChatUtil.color(buildInfo(wearable)), " ".repeat(3)));
	}

	/**
	 * The joined traits list is quoted when there's more than one: {@link JsonFormatter#formatToJson} breaks a
	 * line at every unquoted comma, same convention {@code AmmunitionInfoCommand.buildInfo} uses for a comma ammo
	 * id.
	 */
	static String buildInfo(Wearable wearable) {
		StringBuilder traitsBuilder = new StringBuilder();
		Set<String>   traits        = wearable.traits();

		if (traits == null || traits.isEmpty()) {
			traitsBuilder.append("&7none");
		} else {
			StringJoiner joiner = new StringJoiner("&7, ");
			for (String traitKey : traits) {
				joiner.add("&b" + traitKey + " &7(" + wearable.traitLevel(traitKey) + ")");
			}
			traitsBuilder.append('"').append(joiner).append('"');
		}

		return "&7Key&8: &b" + wearable.getWearableKey() + "\n&7Name&8: &b" + wearable.getName() +
		       "\n&7Material&8: &b" + wearable.getMaterial().name() + "\n&7Base Reduction&8: &b" +
		       (int) (wearable.getBaseDamageReduction() * 100) + "%" + "\n&7Traits&8: " + traitsBuilder;
	}

}
