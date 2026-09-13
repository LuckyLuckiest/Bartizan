package org.luckyraven.bartizan.command;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.luckyraven.bartizan.Bartizan;
import org.luckyraven.keystone.command.argument.Argument;
import org.luckyraven.keystone.command.argument.SubArgument;
import org.luckyraven.keystone.command.argument.types.OptionalArgument;
import org.luckyraven.keystone.util.TriConsumer;
import org.luckyraven.keystone.datastructure.Tree;
import org.luckyraven.bartizan.file.BartizanMessages;
import org.luckyraven.bartizan.util.BartizanChatUtil;
import org.luckyraven.bartizan.api.ammo.Ammunition;
import org.luckyraven.bartizan.ammo.AmmunitionManager;

import java.util.List;
import java.util.Map;

class AmmunitionGiveCommand extends SubArgument {

	private final Bartizan          bartizan;
	private final Tree<Argument>    tree;
	private final AmmunitionManager ammunitionManager;

	protected AmmunitionGiveCommand(Bartizan bartizan, Tree<Argument> tree, Argument parent,
	                                AmmunitionManager ammunitionManager) {
		super(bartizan, "give", tree, parent);

		this.bartizan          = bartizan;
		this.tree              = tree;
		this.ammunitionManager = ammunitionManager;

		ammunitionGive();
	}

	@Override
	protected TriConsumer<Argument, CommandSender, String[]> action() {
		return (argument, sender, args) -> sender.sendMessage(
				BartizanChatUtil.setArguments(BartizanMessages.ARGUMENTS_MISSING.toString(), "<name>"));
	}

	private void ammunitionGive() {
		OptionalArgument name = new OptionalArgument(bartizan, tree, (argument, sender, args) -> {
			Player player = (Player) sender;

			String  ammoName       = args[2];
			boolean giveAmmunition = giveAmmunition(player, ammoName.toLowerCase(), 1);

			if (giveAmmunition) {
				String gaveAmmo = BartizanMessages.RECEIVED_AMMO.toString();
				player.sendMessage(gaveAmmo.replace("%ammo%", ammoName).replace("%amount%", "1"));
			} else {
				String invalidAmmo = BartizanMessages.INVALID_AMMO.toString();
				player.sendMessage(invalidAmmo.replace("%args%", ammoName));
			}
		}, sender -> {
			return ammunitionManager.getAmmunitionKeys()
					.stream().toList();
		});

		OptionalArgument amount = new OptionalArgument(bartizan, tree, (argument, sender, args) -> {
			Player player = (Player) sender;

			String ammoName = args[2];
			int    ammoAmount;

			try {
				ammoAmount = Integer.parseInt(args[3]);
			} catch (NumberFormatException exception) {
				player.sendMessage(BartizanChatUtil.commandMessage(BartizanMessages.MUST_BE_NUMBERS.toString()));
				return;
			}

			boolean giveAmmunition = giveAmmunition(player, ammoName.toLowerCase(), ammoAmount);

			if (giveAmmunition) {
				String gaveAmmo = BartizanMessages.RECEIVED_AMMO.toString();
				player.sendMessage(gaveAmmo.replace("%ammo%", ammoName).replace("%amount%", String.valueOf(ammoAmount)));
			} else {
				String invalidAmmo = BartizanMessages.INVALID_AMMO.toString();
				player.sendMessage(invalidAmmo.replace("%args%", ammoName));
			}
		}, sender -> List.of("<amount>"));

		name.setDisplayName("name");
		amount.setDisplayName("amount");

		name.addSubArgument(amount);
		this.addSubArgument(name);
	}

	private boolean giveAmmunition(Player player, String name, int amount) {
		Ammunition ammunition = ammunitionManager.getAmmunition(name);

		if (ammunition == null) return false;

		ItemStack       sampleItem   = ammunition.buildItem(player);
		int             maxStackSize = sampleItem.getMaxStackSize();
		int             slots        = (int) Math.ceil(amount / (double) maxStackSize);
		int             amountLeft   = amount;
		PlayerInventory inventory    = player.getInventory();
		ItemStack[]     items        = new ItemStack[slots];

		for (int i = 0; i < items.length; ++i) {
			int amountGive = Math.min(amountLeft, maxStackSize);

			if (amountGive <= 0) break;

			ItemStack item = ammunition.buildItem(player);

			item.setAmount(amountGive);

			items[i] = item;

			amountLeft -= amountGive;
		}

		Map<Integer, ItemStack> left = inventory.addItem(items);

		// make the player drop from their inventory the rest of items
		for (ItemStack item : left.values()) {
			player.getWorld().dropItemNaturally(player.getLocation(), item);
		}

		return true;
	}

}
