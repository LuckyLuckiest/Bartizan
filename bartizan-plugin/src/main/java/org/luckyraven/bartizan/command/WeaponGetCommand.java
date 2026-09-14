package org.luckyraven.bartizan.command;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;
import org.luckyraven.bartizan.Bartizan;
import org.luckyraven.keystone.command.CommandMessages;
import org.luckyraven.keystone.command.argument.Argument;
import org.luckyraven.keystone.command.argument.SubArgument;
import org.luckyraven.keystone.command.argument.types.OptionalArgument;
import org.luckyraven.keystone.util.TriConsumer;
import org.luckyraven.keystone.datastructure.Tree;
import org.luckyraven.bartizan.file.BartizanMessages;
import org.luckyraven.bartizan.file.WeaponLoader;
import org.luckyraven.keystone.persistence.FileHandler;
import org.luckyraven.bartizan.util.BartizanChatUtil;
import org.luckyraven.bartizan.weapon.WeaponManager;

import java.util.List;

/**
 * {@code /bartizan weapon get <weapon> [amount]} (weapons-roadmap.md gate {@code HD}) — self-only, exactly
 * {@code /bartizan weapon give}'s original (pre-{@code HD}) behaviour: gives the weapon to the command's own
 * sender. Shares the item-building/overflow-drop body with the now player-targeting {@code give} via
 * {@link WeaponGiveHelper} rather than duplicating it.
 */
class WeaponGetCommand extends SubArgument {

	private final Bartizan       bartizan;
	private final Tree<Argument> tree;
	private final WeaponManager  weaponManager;
	private final WeaponLoader   weaponLoader;

	protected WeaponGetCommand(Bartizan bartizan, Tree<Argument> tree, Argument parent,
	                           WeaponManager weaponManager,
	                           WeaponLoader weaponLoader) {
		super(bartizan, "get", tree, parent);

		this.bartizan      = bartizan;
		this.tree          = tree;
		this.weaponManager = weaponManager;
		this.weaponLoader  = weaponLoader;

		weaponGet();
	}

	@Override
	protected TriConsumer<Argument, CommandSender, String[]> action() {
		return (argument, sender, args) -> sender.sendMessage(
				BartizanChatUtil.setArguments(BartizanMessages.ARGUMENTS_MISSING.toString(), "<name>"));
	}

	private void weaponGet() {
		OptionalArgument name = new OptionalArgument(bartizan, tree, (argument, sender, args) -> {
			Player player = requirePlayer(sender);
			if (player == null) return;

			handle(player, args[2], 1);
		}, sender -> weaponLoader.getFiles().stream().map(FileHandler::getName).toList());

		OptionalArgument amount = new OptionalArgument(bartizan, tree, (argument, sender, args) -> {
			Player player = requirePlayer(sender);
			if (player == null) return;

			int giveAmount;
			try {
				giveAmount = Integer.parseInt(args[3]);
			} catch (NumberFormatException exception) {
				player.sendMessage(BartizanChatUtil.commandMessage(BartizanMessages.MUST_BE_NUMBERS.toString()));
				return;
			}

			handle(player, args[2], giveAmount);
		}, sender -> List.of("<amount>"));

		name.setDisplayName("name");
		amount.setDisplayName("amount");

		name.addSubArgument(amount);
		this.addSubArgument(name);
	}

	@Nullable
	private Player requirePlayer(CommandSender sender) {
		if (sender instanceof Player player) return player;

		sender.sendMessage(CommandMessages.playerOnly());
		return null;
	}

	private void handle(Player player, String weaponName, int amount) {
		boolean gave = WeaponGiveHelper.give(weaponManager, player, weaponName.toLowerCase(), amount);

		if (gave) {
			String receivedWeapon = BartizanMessages.RECEIVED_WEAPON.toString();
			player.sendMessage(receivedWeapon.replace("%weapon%", weaponName).replace("%amount%", String.valueOf(amount)));
		} else {
			String invalidWeapon = BartizanMessages.INVALID_WEAPON.toString();
			player.sendMessage(invalidWeapon.replace("%args%", weaponName));
		}
	}

}
