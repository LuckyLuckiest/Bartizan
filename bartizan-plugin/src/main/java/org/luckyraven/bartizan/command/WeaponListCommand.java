package org.luckyraven.bartizan.command;

import org.bukkit.command.CommandSender;
import org.luckyraven.bartizan.Bartizan;
import org.luckyraven.keystone.command.argument.Argument;
import org.luckyraven.keystone.command.argument.SubArgument;
import org.luckyraven.keystone.util.TriConsumer;
import org.luckyraven.keystone.datastructure.Tree;
import org.luckyraven.bartizan.file.BartizanMessages;
import org.luckyraven.bartizan.util.BartizanChatUtil;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.configuration.WeaponAddon;

import java.util.Iterator;
import java.util.Set;

class WeaponListCommand extends SubArgument {

	private final WeaponAddon weaponAddon;

	protected WeaponListCommand(Bartizan bartizan, Tree<Argument> tree, Argument parent, WeaponAddon weaponAddon) {
		super(bartizan, "list", tree, parent);

		this.weaponAddon = weaponAddon;
	}

	@Override
	protected TriConsumer<Argument, CommandSender, String[]> action() {
		return (argument, sender, args) -> {
			Set<String> weapons = weaponAddon.getWeaponKeys();

			sender.sendMessage(BartizanMessages.WEAPON_LIST_HEADER.toString());

			Iterator<String> iterator = weapons.iterator();
			StringBuilder    builder  = new StringBuilder();

			while (iterator.hasNext()) {
				Weapon weapon = weaponAddon.getWeapon(iterator.next());
				if (weapon == null) continue;

				builder.append("&b").append(weapon.getName());
				if (iterator.hasNext()) builder.append("&7, ");
			}

			sender.sendMessage(BartizanChatUtil.color(builder.toString()));
		};
	}

}
