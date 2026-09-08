package org.luckyraven.bartizan.command.wearable;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.luckyraven.bartizan.Bartizan;
import org.luckyraven.keystone.command.argument.Argument;
import org.luckyraven.keystone.command.argument.SubArgument;
import org.luckyraven.keystone.util.TriConsumer;
import org.luckyraven.keystone.datastructure.JsonFormatter;
import org.luckyraven.keystone.datastructure.Tree;
import org.luckyraven.bartizan.file.BartizanMessages;
import org.luckyraven.bartizan.wearable.WearableAddon;
import org.luckyraven.bartizan.api.wearable.Wearable;
import org.luckyraven.bartizan.util.BartizanChatUtil;

import java.util.Set;
import java.util.StringJoiner;

/**
 * {@code /bartizan wearable info}. Traits are read via {@link Wearable#traits()} / {@link Wearable#traitLevel(String)}
 * (a lower-case string key, not the deleted {@code WearableTrait} enum — bartizan.md §1.6(6)).
 */
class WearableInfoCommand extends SubArgument {

	private final WearableAddon wearableAddon;

	WearableInfoCommand(Bartizan bartizan, Tree<Argument> tree, Argument parent,
	                    WearableAddon wearableAddon) {
		super(bartizan, "info", tree, parent);

		this.wearableAddon = wearableAddon;
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

			if (wearable == null) {
				player.sendMessage(BartizanMessages.WEARABLE_NOT_REGISTERED.toString().replace("%key%", key));
				return;
			}

			StringBuilder traitsBuilder = new StringBuilder();
			Set<String>   traits        = wearable.traits();

			if (traits == null || traits.isEmpty()) {
				traitsBuilder.append("&7none");
			} else {
				StringJoiner joiner = new StringJoiner("&7, ");
				for (String traitKey : traits) {
					joiner.add("&b" + traitKey + " &7(" + wearable.traitLevel(traitKey) + ")");
				}
				traitsBuilder.append(joiner);
			}

			String info = "&7Key&8: &b" + wearable.getWearableKey() + "\n&7Name&8: &b" + wearable.getName() +
			              "\n&7Material&8: &b" + wearable.getMaterial().name() + "\n&7Base Reduction&8: &b" +
			              (int) (wearable.getBaseDamageReduction() * 100) + "%" + "\n&7Traits&8: " + traitsBuilder;

			JsonFormatter jsonFormatter = new JsonFormatter();

			player.sendMessage(jsonFormatter.formatToJson(BartizanChatUtil.color(info), " ".repeat(3)));
		};
	}

}
