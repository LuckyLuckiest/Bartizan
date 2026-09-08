package org.luckyraven.bartizan.command.wearable;

import org.bukkit.command.CommandSender;
import org.luckyraven.bartizan.Bartizan;
import org.luckyraven.keystone.command.argument.Argument;
import org.luckyraven.keystone.command.argument.SubArgument;
import org.luckyraven.keystone.util.TriConsumer;
import org.luckyraven.keystone.datastructure.Tree;
import org.luckyraven.bartizan.file.BartizanMessages;
import org.luckyraven.bartizan.wearable.WearableAddon;
import org.luckyraven.bartizan.api.wearable.Wearable;
import org.luckyraven.bartizan.util.BartizanChatUtil;

import java.util.Iterator;
import java.util.Map;

class WearableListCommand extends SubArgument {

	private final WearableAddon wearableAddon;

	WearableListCommand(Bartizan bartizan, Tree<Argument> tree, Argument parent, WearableAddon wearableAddon) {
		super(bartizan, "list", tree, parent);

		this.wearableAddon = wearableAddon;
	}

	@Override
	protected TriConsumer<Argument, CommandSender, String[]> action() {
		return (argument, sender, args) -> {
			Map<String, Wearable> wearables = wearableAddon.getWearables();

			sender.sendMessage(BartizanMessages.WEARABLE_LIST_HEADER.toString());

			Iterator<Map.Entry<String, Wearable>> iterator = wearables.entrySet().iterator();
			StringBuilder                         builder  = new StringBuilder();

			while (iterator.hasNext()) {
				Wearable wearable = iterator.next().getValue();

				builder.append("&b").append(wearable.getName());
				if (iterator.hasNext()) builder.append("&7, ");
			}

			sender.sendMessage(BartizanChatUtil.color(builder.toString()));
		};
	}

}
