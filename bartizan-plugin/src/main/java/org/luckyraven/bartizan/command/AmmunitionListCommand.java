package org.luckyraven.bartizan.command;

import org.bukkit.command.CommandSender;
import org.luckyraven.bartizan.Bartizan;
import org.luckyraven.keystone.command.argument.Argument;
import org.luckyraven.keystone.command.argument.SubArgument;
import org.luckyraven.keystone.util.TriConsumer;
import org.luckyraven.keystone.datastructure.Tree;
import org.luckyraven.bartizan.file.BartizanMessages;
import org.luckyraven.bartizan.util.BartizanChatUtil;
import org.luckyraven.bartizan.api.ammo.Ammunition;
import org.luckyraven.bartizan.ammo.AmmunitionManager;

import java.util.Iterator;
import java.util.Set;

class AmmunitionListCommand extends SubArgument {

	private final AmmunitionManager ammunitionManager;

	protected AmmunitionListCommand(Bartizan bartizan, Tree<Argument> tree, Argument parent,
	                                AmmunitionManager ammunitionManager) {
		super(bartizan, "list", tree, parent);

		this.ammunitionManager = ammunitionManager;
	}

	@Override
	protected TriConsumer<Argument, CommandSender, String[]> action() {
		return (argument, sender, args) -> {
			Set<String> ammunition = ammunitionManager.getAmmunitionKeys();

			sender.sendMessage(BartizanMessages.AMMO_LIST_HEADER.toString());

			Iterator<String> iterator = ammunition.iterator();
			StringBuilder    builder  = new StringBuilder();

			while (iterator.hasNext()) {
				Ammunition ammo = ammunitionManager.getAmmunition(iterator.next());
				if (ammo == null) continue;

				builder.append("&b").append(ammo.getName());
				if (iterator.hasNext()) builder.append("&7, ");
			}

			sender.sendMessage(BartizanChatUtil.color(builder.toString()));
		};
	}

}
