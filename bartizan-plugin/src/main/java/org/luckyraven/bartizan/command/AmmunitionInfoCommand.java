package org.luckyraven.bartizan.command;

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
import org.luckyraven.bartizan.util.BartizanChatUtil;
import org.luckyraven.bartizan.api.ammo.Ammunition;
import org.luckyraven.bartizan.ammo.AmmunitionManager;

class AmmunitionInfoCommand extends SubArgument {

	private final Bartizan          bartizan;
	private final Tree<Argument>    tree;
	private final AmmunitionManager ammunitionManager;

	protected AmmunitionInfoCommand(Bartizan bartizan, Tree<Argument> tree, Argument parent,
	                                AmmunitionManager ammunitionManager) {
		super(bartizan, "info", tree, parent);

		this.bartizan          = bartizan;
		this.tree              = tree;
		this.ammunitionManager = ammunitionManager;

		ammunitionInfo();
	}

	@Override
	protected TriConsumer<Argument, CommandSender, String[]> action() {
		return (argument, sender, args) -> {
			Player player = (Player) sender;

			ItemStack itemStack = player.getInventory().getItemInMainHand();

			if (!Ammunition.isAmmunition(itemStack)) {
				player.sendMessage(BartizanMessages.INVALID_AMMO.toString().replace("%args%", itemStack.getType().name()));
				return;
			}

			Ammunition ammunition = Ammunition.getHeldAmmunition(ammunitionManager, itemStack);

			if (ammunition == null) {
				player.sendMessage(BartizanMessages.INVALID_AMMO.toString().replace("%args%", itemStack.getType().name()));
				return;
			}

			sendInfo(player, ammunition);
		};
	}

	private void ammunitionInfo() {
		OptionalArgument name = new OptionalArgument(bartizan, tree, (argument, sender, args) -> {
			Player player = (Player) sender;

			String     ammoName   = args[2];
			Ammunition ammunition = ammunitionManager.getAmmunition(ammoName);

			if (ammunition == null) {
				player.sendMessage(BartizanMessages.INVALID_AMMO.toString().replace("%args%", ammoName));
				return;
			}

			sendInfo(player, ammunition);
		}, sender -> ammunitionManager.getAmmunitionKeys()
				.stream().toList());

		name.setDisplayName("name");

		this.addSubArgument(name);
	}

	private void sendInfo(Player player, Ammunition ammunition) {
		JsonFormatter jsonFormatter = new JsonFormatter();
		player.sendMessage(jsonFormatter.formatToJson(BartizanChatUtil.color(buildInfo(ammunition)), " ".repeat(3)));
	}

	/**
	 * The id is quoted: {@link JsonFormatter#formatToJson} breaks the line at every unquoted comma, and ammo ids
	 * carry one ({@code 7,62}).
	 */
	static String buildInfo(Ammunition ammunition) {
		StringBuilder info = new StringBuilder();
		info.append("&7Name&8: &b\"").append(ammunition.getName()).append('"')
		    .append("\n&7Display Name&8: &b").append(ammunition.getDisplayName())
		    .append("\n&7Material&8: &b").append(ammunition.getMaterial().name())
		    .append("\n&7Custom Model Data&8: &b").append(ammunition.getCustomModelData());

		return info.toString();
	}

}
