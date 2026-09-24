package org.luckyraven.bartizan.command;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.luckyraven.bartizan.Bartizan;
import org.luckyraven.keystone.command.argument.Argument;
import org.luckyraven.keystone.command.argument.SubArgument;
import org.luckyraven.keystone.command.argument.types.OptionalArgument;
import org.luckyraven.keystone.util.TriConsumer;
import org.luckyraven.keystone.datastructure.Tree;
import org.luckyraven.bartizan.file.BartizanMessages;
import org.luckyraven.bartizan.util.BartizanChatUtil;
import org.luckyraven.bartizan.weapon.WeaponManager;
import org.luckyraven.bartizan.configuration.WeaponAddon;

import java.util.List;

/**
 * {@code /bartizan weapon give <player> <weapon> [amount]} (weapons-roadmap.md gate {@code HD}) — targets any
 * online player and is usable from console, unlike self-only {@code /bartizan weapon get} (which shares the actual
 * item-building/overflow-drop body via {@link WeaponGiveHelper}, not by copying it). {@code WeaponCommand}'s own
 * {@code user} flag had to flip to {@code false} for this command to be console-reachable at all — see
 * {@code WeaponCommand}'s constructor javadoc.
 */
class WeaponGiveCommand extends SubArgument {

	private final Bartizan       bartizan;
	private final Tree<Argument> tree;
	private final WeaponManager  weaponManager;
	private final WeaponAddon    weaponAddon;

	protected WeaponGiveCommand(Bartizan bartizan, Tree<Argument> tree, Argument parent,
	                            WeaponManager weaponManager,
	                            WeaponAddon weaponAddon) {
		super(bartizan, "give", tree, parent);

		this.bartizan      = bartizan;
		this.tree          = tree;
		this.weaponManager = weaponManager;
		this.weaponAddon   = weaponAddon;

		weaponGive();
	}

	@Override
	protected TriConsumer<Argument, CommandSender, String[]> action() {
		return (argument, sender, args) -> sender.sendMessage(
				BartizanChatUtil.setArguments(BartizanMessages.ARGUMENTS_MISSING.toString(), "<player> <weapon>"));
	}

	private void weaponGive() {
		OptionalArgument player = new OptionalArgument(bartizan, tree, (argument, sender, args) -> sender.sendMessage(
				BartizanChatUtil.setArguments(BartizanMessages.ARGUMENTS_MISSING.toString(), "<weapon>")),
				sender -> Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());

		OptionalArgument weapon = new OptionalArgument(bartizan, tree, (argument, sender, args) ->
				handleGive(sender, args[2], args[3], 1),
				sender -> weaponAddon.getWeaponKeys().stream().toList());

		OptionalArgument amount = new OptionalArgument(bartizan, tree, (argument, sender, args) -> {
			int giveAmount;

			try {
				giveAmount = Integer.parseInt(args[4]);
			} catch (NumberFormatException exception) {
				sender.sendMessage(BartizanChatUtil.commandMessage(BartizanMessages.MUST_BE_NUMBERS.toString()));
				return;
			}

			handleGive(sender, args[2], args[3], giveAmount);
		}, sender -> List.of("<amount>"));

		player.setDisplayName("player");
		weapon.setDisplayName("weapon");
		amount.setDisplayName("amount");

		weapon.addSubArgument(amount);
		player.addSubArgument(weapon);
		this.addSubArgument(player);
	}

	private void handleGive(CommandSender sender, String playerName, String weaponName, int amount) {
		Player target = Bukkit.getPlayerExact(playerName);
		if (target == null) {
			sender.sendMessage(BartizanMessages.PLAYER_NOT_FOUND.toString().replace("%player%", playerName));
			return;
		}

		boolean gave = WeaponGiveHelper.give(weaponManager, target, weaponName.toLowerCase(), amount);
		if (!gave) {
			sender.sendMessage(BartizanMessages.INVALID_WEAPON.toString().replace("%args%", weaponName));
			return;
		}

		target.sendMessage(BartizanMessages.RECEIVED_WEAPON.toString()
		                                                   .replace("%weapon%", weaponName)
		                                                   .replace("%amount%", String.valueOf(amount)));

		// The target already saw RECEIVED_WEAPON above; a self-give (a player giving themselves the weapon) must
		// not also get a second, redundant "gave X to Y" line.
		if (!sender.equals(target)) {
			sender.sendMessage(BartizanMessages.GAVE_WEAPON.toString()
			                                                .replace("%weapon%", weaponName)
			                                                .replace("%amount%", String.valueOf(amount))
			                                                .replace("%player%", target.getName()));
		}
	}

}
