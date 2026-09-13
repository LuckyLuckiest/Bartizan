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
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.weapon.WeaponManager;
import org.luckyraven.bartizan.configuration.WeaponAddon;
import org.luckyraven.bartizan.api.weapon.dto.AmmunitionData;
import org.luckyraven.bartizan.api.weapon.dto.ReloadData;

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
			Player player = (Player) sender;

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
			Player player = (Player) sender;

			String weaponName = args[2];
			Weapon weapon     = weaponAddon.getWeapon(weaponName);

			if (weapon == null) {
				player.sendMessage(BartizanMessages.INVALID_WEAPON.toString().replace("%args%", weaponName));
				return;
			}

			sendInfo(player, weapon);
		}, sender -> weaponAddon.getWeaponKeys()
				.stream().toList());

		name.setDisplayName("name");

		this.addSubArgument(name);
	}

	private void sendInfo(Player player, Weapon weapon) {
		JsonFormatter jsonFormatter = new JsonFormatter();
		player.sendMessage(jsonFormatter.formatToJson(BartizanChatUtil.color(buildInfo(weapon)), " ".repeat(3)));
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
			info.append("\n&7Magazine&8: &b").append(weapon.getCurrentMagCapacity())
			    .append("&7/&b").append(ammunitionData.getMaxMagCapacity())
			    .append("\n&7Ammo Type&8: &b").append(ammunitionData.getAmmoType());
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
