package org.luckyraven.bartizan.command;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.luckyraven.bartizan.Bartizan;
import org.luckyraven.keystone.command.CommandMessages;
import org.luckyraven.keystone.command.argument.Argument;
import org.luckyraven.keystone.command.argument.SubArgument;
import org.luckyraven.keystone.command.argument.types.OptionalArgument;
import org.luckyraven.keystone.util.TriConsumer;
import org.luckyraven.keystone.datastructure.JsonFormatter;
import org.luckyraven.keystone.datastructure.Tree;
import org.luckyraven.bartizan.file.BartizanMessages;
import org.luckyraven.bartizan.util.BartizanChatUtil;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.weapon.WeaponManager;
import org.luckyraven.bartizan.configuration.WeaponAddon;
import org.luckyraven.bartizan.api.ammo.Ammunition;
import org.luckyraven.bartizan.api.weapon.dto.AmmunitionData;
import org.luckyraven.bartizan.api.weapon.dto.ReloadData;

import java.util.List;
import java.util.stream.Collectors;

class WeaponInfoCommand extends SubArgument {

	private final Bartizan      bartizan;
	private final Tree<Argument> tree;
	private final WeaponManager weaponManager;
	private final WeaponAddon   weaponAddon;

	protected WeaponInfoCommand(Bartizan bartizan, Tree<Argument> tree, Argument parent,
	                            WeaponManager weaponManager,
	                            WeaponAddon weaponAddon) {
		super(bartizan, "info", tree, parent);

		this.bartizan      = bartizan;
		this.tree          = tree;
		this.weaponManager = weaponManager;
		this.weaponAddon   = weaponAddon;

		weaponInfo();
	}

	@Override
	protected TriConsumer<Argument, CommandSender, String[]> action() {
		return (argument, sender, args) -> {
			// Reads the sender's own held item, so unlike the name-arg branch below this one genuinely needs a
			// player (WeaponCommand's user flag no longer gates this for us — see its constructor javadoc).
			Player player = requirePlayer(sender);
			if (player == null) return;

			ItemStack itemStack = player.getInventory().getItemInMainHand();
			Weapon    weapon    = weaponManager.validateAndGetWeapon(player, itemStack);

			if (weapon == null) {
				player.sendMessage(BartizanMessages.INVALID_WEAPON.toString().replace("%args%", itemStack.getType().name()));
				return;
			}

			sendInfo(player, weapon);
		};
	}

	private void weaponInfo() {
		OptionalArgument name = new OptionalArgument(bartizan, tree, (argument, sender, args) -> {
			String weaponName = args[2];
			Weapon weapon     = weaponAddon.getWeapon(weaponName);

			if (weapon == null) {
				sender.sendMessage(BartizanMessages.INVALID_WEAPON.toString().replace("%args%", weaponName));
				return;
			}

			sendInfo(sender, weapon);
		}, sender -> weaponAddon.getWeaponKeys()
				.stream().toList());

		name.setDisplayName("name");

		this.addSubArgument(name);
	}

	@Nullable
	private Player requirePlayer(CommandSender sender) {
		if (sender instanceof Player player) return player;

		sender.sendMessage(CommandMessages.playerOnly());
		return null;
	}

	private void sendInfo(CommandSender sender, Weapon weapon) {
		JsonFormatter jsonFormatter = new JsonFormatter();
		sender.sendMessage(jsonFormatter.formatToJson(BartizanChatUtil.color(buildInfo(weapon)), " ".repeat(3)));
	}

	private String buildInfo(Weapon weapon) {
		StringBuilder info = new StringBuilder();
		info.append("&7Name&8: &b").append(weapon.getName())
		    .append("\n&7Display Name&8: &b").append(weapon.getDisplayName())
		    .append("\n&7Category&8: &b").append(weapon.getCategory())
		    .append("\n&7Material&8: &b").append(weapon.getMaterial().name())
		    .append("\n&7Custom Model Data&8: &b").append(weapon.getCustomModelData())
		    .append("\n&7Durability&8: &b").append(weapon.getCurrentDurability())
		    .append("&7/&b").append(weapon.getDurability());

		AmmunitionData ammunitionData = weapon.getAmmunitionData();
		if (ammunitionData != null) {
			List<Ammunition> ammoTypes = ammunitionData.getAmmoTypes();
			String ammoTypeLabel = ammoTypes.isEmpty() ? "none" :
			                       // quoted: JsonFormatter breaks the line at an unquoted comma, and ids carry one (7,62)
			                       ammoTypes.stream().map(ammo -> '"' + ammo.getName() + '"')
			                                .collect(Collectors.joining(", "));

			info.append("\n&7Magazine&8: &b").append(weapon.getCurrentMagCapacity())
			    .append("&7/&b").append(ammunitionData.getMaxMagCapacity())
			    .append("\n&7Ammo Type&8: &b").append(ammoTypeLabel);
		}

		ReloadData reloadData = weapon.getReloadData();
		if (reloadData != null) {
			info.append("\n&7Reload Type&8: &b").append(reloadData.getType());
		}

		if (weapon.getCurrentSelectiveFire() != null) {
			info.append("\n&7Selective Fire&8: &b").append(weapon.getCurrentSelectiveFire());
		}

		return info.toString();
	}

}
