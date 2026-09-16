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
import java.util.List;

class WearableListCommand extends SubArgument {

	private final WearableAddon wearableAddon;

	WearableListCommand(Bartizan bartizan, Tree<Argument> tree, Argument parent, WearableAddon wearableAddon) {
		super(bartizan, "list", tree, parent);

		this.wearableAddon = wearableAddon;
	}

	@Override
	protected TriConsumer<Argument, CommandSender, String[]> action() {
		return (argument, sender, args) -> {
			// External (WS7-D4) entries have no display Name of their own and are not Bartizan's to list.
			List<Wearable> wearables = wearableAddon.getWearables().values().stream()
					.filter(wearable -> !wearable.isExternal()).toList();

			sender.sendMessage(BartizanMessages.WEARABLE_LIST_HEADER.toString());

			Iterator<Wearable> iterator = wearables.iterator();
			StringBuilder      builder  = new StringBuilder();

			while (iterator.hasNext()) {
				Wearable wearable = iterator.next();

				builder.append("&b").append(wearable.getName());
				if (iterator.hasNext()) builder.append("&7, ");
			}

			sender.sendMessage(BartizanChatUtil.color(builder.toString()));
		};
	}

}
