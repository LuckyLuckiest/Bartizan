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
import org.luckyraven.bartizan.file.WeaponLoader;
import org.luckyraven.keystone.persistence.FileHandler;
import org.luckyraven.bartizan.util.BartizanChatUtil;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.weapon.WeaponManager;

import java.util.List;
import java.util.Map;

/**
 * {@code /bartizan weapon give <name> [amount]} (bartizan.md §1.4) — rewrite of
 * {@code W/command/WeaponGiveCommand.java}: {@code User<Player>.sendMessage} calls become
 * {@code player.sendMessage} directly (the only {@code User} method the original called, per §1.4's verified
 * survey), {@code Messages}/{@code GanglandChatUtil} become {@code BartizanMessages}/{@code BartizanChatUtil}.
 */
class WeaponGiveCommand extends SubArgument {

	private final Bartizan       bartizan;
	private final Tree<Argument> tree;
	private final WeaponManager  weaponManager;
	private final WeaponLoader   weaponLoader;

	protected WeaponGiveCommand(Bartizan bartizan, Tree<Argument> tree, Argument parent,
	                            WeaponManager weaponManager,
	                            WeaponLoader weaponLoader) {
		super(bartizan, "give", tree, parent);

		this.bartizan      = bartizan;
		this.tree          = tree;
		this.weaponManager = weaponManager;
		this.weaponLoader  = weaponLoader;

		weaponGive();
	}

	@Override
	protected TriConsumer<Argument, CommandSender, String[]> action() {
		return (argument, sender, args) -> sender.sendMessage(
				BartizanChatUtil.setArguments(BartizanMessages.ARGUMENTS_MISSING.toString(), "<name>"));
	}

	private void weaponGive() {
		Argument name = new OptionalArgument(bartizan, tree, (argument, sender, args) -> {
			Player player = (Player) sender;

			String  weaponName = args[2];
			boolean giveWeapon = giveWeapon(player, weaponName.toLowerCase(), 1);

			if (giveWeapon) {
				String receivedWeapon = BartizanMessages.RECEIVED_WEAPON.toString();
				player.sendMessage(receivedWeapon.replace("%weapon%", weaponName).replace("%amount%", "1"));
			} else {
				String invalidWeapon = BartizanMessages.INVALID_WEAPON.toString();
				player.sendMessage(invalidWeapon.replace("%args%", weaponName));
			}
		}, sender -> {
			return weaponLoader.getFiles()
					.stream().map(FileHandler::getName).toList();
		});

		Argument amount = new OptionalArgument(bartizan, tree, (argument, sender, args) -> {
			Player player = (Player) sender;

			String weaponName = args[2];
			int    weaponAmount;

			try {
				weaponAmount = Integer.parseInt(args[3]);
			} catch (NumberFormatException exception) {
				player.sendMessage(BartizanChatUtil.commandMessage(BartizanMessages.MUST_BE_NUMBERS.toString()));
				return;
			}

			boolean giveWeapon = giveWeapon(player, weaponName.toLowerCase(), weaponAmount);

			if (giveWeapon) {
				String receivedWeapon = BartizanMessages.RECEIVED_WEAPON.toString();
				String replace = receivedWeapon.replace("%weapon%", weaponName)
				                               .replace("%amount%", String.valueOf(weaponAmount));
				player.sendMessage(replace);
			} else {
				String invalidWeapon = BartizanMessages.INVALID_WEAPON.toString();
				player.sendMessage(invalidWeapon.replace("%args%", weaponName));
			}
		}, sender -> List.of("<amount>"));

		name.addSubArgument(amount);
		this.addSubArgument(name);
	}

	private boolean giveWeapon(Player player, String name, int amount) {
		Weapon weapon = weaponManager.getWeapon(player, null, name, true);

		if (weapon == null) return false;

		ItemStack       sampleItem   = weapon.buildItem(player);
		int             maxStackSize = sampleItem.getMaxStackSize();
		int             slots        = (int) Math.ceil(amount / (double) maxStackSize);
		int             amountLeft   = amount;
		PlayerInventory inventory    = player.getInventory();
		ItemStack[]     items        = new ItemStack[slots];

		for (int i = 0; i < slots; i++) {
			int amountGive = Math.min(amountLeft, maxStackSize);

			if (amountGive <= 0) break;

			ItemStack item = weapon.buildItem(player);

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
