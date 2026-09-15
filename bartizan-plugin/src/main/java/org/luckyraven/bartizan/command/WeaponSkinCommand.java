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
import org.luckyraven.keystone.datastructure.Tree;
import org.luckyraven.bartizan.file.BartizanMessages;
import org.luckyraven.bartizan.util.BartizanChatUtil;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.api.weapon.dto.SkinsData;
import org.luckyraven.bartizan.weapon.WeaponManager;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code /bartizan weapon skin <name|default>} (weapons-roadmap.md gate {@code HJ}) — applies one of the held
 * weapon's {@code Skins.Named} entries, or clears the selection back to the state-driven default with the literal
 * {@code default}. Self-only, mirroring {@code /bartizan weapon info}'s held-item branch. Per-skin permissions are
 * deliberately unimplemented — // ponytail: add a {@code bartizan.skin.<name>} check if skins ever need gating.
 */
class WeaponSkinCommand extends SubArgument {

	private final Bartizan       bartizan;
	private final Tree<Argument> tree;
	private final WeaponManager  weaponManager;

	protected WeaponSkinCommand(Bartizan bartizan, Tree<Argument> tree, Argument parent, WeaponManager weaponManager) {
		super(bartizan, "skin", tree, parent);

		this.bartizan      = bartizan;
		this.tree          = tree;
		this.weaponManager = weaponManager;

		weaponSkin();
	}

	@Override
	protected TriConsumer<Argument, CommandSender, String[]> action() {
		return (argument, sender, args) -> sender.sendMessage(
				BartizanChatUtil.setArguments(BartizanMessages.ARGUMENTS_MISSING.toString(), "<name>"));
	}

	private void weaponSkin() {
		OptionalArgument name = new OptionalArgument(bartizan, tree, (argument, sender, args) -> {
			Player player = requirePlayer(sender);
			if (player == null) return;

			handle(player, args[2]);
		}, this::tabComplete);

		name.setDisplayName("name");
		this.addSubArgument(name);
	}

	private List<String> tabComplete(CommandSender sender) {
		if (!(sender instanceof Player player)) return List.of("default");

		Weapon    weapon = weaponManager.validateAndGetWeapon(player, player.getInventory().getItemInMainHand());
		SkinsData skins  = weapon != null ? weapon.getSkinsData() : null;
		if (skins == null) return List.of("default");

		List<String> names = new ArrayList<>(skins.namedKeys());
		names.add("default");
		return names;
	}

	@Nullable
	private Player requirePlayer(CommandSender sender) {
		if (sender instanceof Player player) return player;

		sender.sendMessage(CommandMessages.playerOnly());
		return null;
	}

	private void handle(Player player, String skinName) {
		ItemStack item   = player.getInventory().getItemInMainHand();
		Weapon    weapon = weaponManager.validateAndGetWeapon(player, item);

		if (weapon == null) {
			player.sendMessage(BartizanMessages.INVALID_WEAPON.toString().replace("%args%", item.getType().name()));
			return;
		}

		String requested = skinName.equalsIgnoreCase("default") ? null : skinName;

		if (!weapon.setSelectedSkin(requested)) {
			player.sendMessage(BartizanMessages.SKIN_UNKNOWN.toString().replace("%skin%", skinName));
			return;
		}

		weaponManager.persistHeldWeapon(weapon, player);

		player.sendMessage(BartizanMessages.SKIN_APPLIED.toString().replace("%skin%", skinName));
	}

}
