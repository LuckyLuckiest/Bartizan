package org.luckyraven.bartizan.command.wearable;

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
import org.luckyraven.bartizan.wearable.WearableAddon;
import org.luckyraven.bartizan.api.wearable.Wearable;
import org.luckyraven.bartizan.util.BartizanChatUtil;

import java.util.List;
import java.util.Map;

/**
 * {@code /bartizan wearable give <name> [amount]}. Argument indices are {@code args[2]}/{@code args[3]} — one
 * level shallower than Gangland's {@code /glw item wearable give} ({@code args[3]}/{@code args[4]}) because
 * {@code wearable} is now a top-level command, not nested under {@code item} (bartizan.md §1.1).
 */
class WearableGiveCommand extends SubArgument {

	// ponytail: same cap as WeaponGiveHelper.MAX_AMOUNT (36 slots * 64) - an unbounded amount lets
	// `wearable give <name> 2000000000` allocate an ItemStack[] large enough to OOM the server.
	private static final int MAX_AMOUNT = 2304;

	private final Bartizan       bartizan;
	private final Tree<Argument> tree;
	private final WearableAddon  wearableAddon;

	WearableGiveCommand(Bartizan bartizan, Tree<Argument> tree, Argument parent,
	                    WearableAddon wearableAddon) {
		super(bartizan, "give", tree, parent);

		this.bartizan      = bartizan;
		this.tree          = tree;
		this.wearableAddon = wearableAddon;

		wearableGive();
	}

	@Override
	protected TriConsumer<Argument, CommandSender, String[]> action() {
		return (argument, sender, args) -> sender.sendMessage(
				BartizanChatUtil.setArguments(BartizanMessages.ARGUMENTS_MISSING.toString(), "<name>"));
	}

	private void wearableGive() {
		OptionalArgument name = new OptionalArgument(bartizan, tree, (argument, sender, args) -> {
			Player player = (Player) sender;

			String itemName = args[2];
			int    given    = giveWearable(player, itemName, 1);

			if (given > 0) {
				player.sendMessage(BartizanMessages.WEARABLE_GAVE.toString()
				                                                 .replace("%name%", itemName)
				                                                 .replace("%amount%", String.valueOf(given)));
			} else {
				player.sendMessage(BartizanMessages.WEARABLE_INVALID.toString().replace("%name%", itemName));
			}
		}, sender -> {
			return wearableAddon.getWearables().entrySet().stream()
					.filter(entry -> !entry.getValue().isExternal())
					.map(Map.Entry::getKey).toList();
		});

		OptionalArgument amount = new OptionalArgument(bartizan, tree, (argument, sender, args) -> {
			Player player = (Player) sender;

			String itemName = args[2];
			int    itemAmount;

			try {
				itemAmount = Integer.parseInt(args[3]);
			} catch (NumberFormatException exception) {
				player.sendMessage(BartizanMessages.MUST_BE_NUMBERS.toString());
				return;
			}

			int given = giveWearable(player, itemName, itemAmount);

			if (given > 0) {
				player.sendMessage(BartizanMessages.WEARABLE_GAVE.toString()
				                                                 .replace("%name%", itemName)
				                                                 .replace("%amount%", String.valueOf(given)));
			} else {
				player.sendMessage(BartizanMessages.WEARABLE_INVALID.toString().replace("%name%", itemName));
			}
		}, sender -> List.of("<amount>"));

		name.setDisplayName("name");
		amount.setDisplayName("amount");

		name.addSubArgument(amount);
		this.addSubArgument(name);
	}

	/**
	 * @return how many were actually handed over (the amount clamped to {@code [1, MAX_AMOUNT]}), or {@code 0} for an
	 * 		unknown or external wearable - the success message reports this, never the raw argument (BZ-CM-01).
	 */
	private int giveWearable(Player player, String name, int amount) {
		Wearable wearable = wearableAddon.getWearable(name);

		// An external (WS7-D4) entry was never built through Bartizan - the registrant's own give path handles it.
		if (wearable == null || wearable.isExternal()) return 0;

		amount = Math.max(1, Math.min(amount, MAX_AMOUNT));

		ItemStack       sampleItem   = wearable.buildItem(player);
		int             maxStackSize = sampleItem.getMaxStackSize();
		int             slots        = (int) Math.ceil(amount / (double) maxStackSize);
		int             amountLeft   = amount;
		PlayerInventory inventory    = player.getInventory();
		ItemStack[]     items        = new ItemStack[slots];

		for (int i = 0; i < items.length; ++i) {
			int amountGive = Math.min(amountLeft, maxStackSize);

			if (amountGive <= 0) break;

			ItemStack item = wearable.buildItem(player);

			item.setAmount(amountGive);

			items[i] = item;

			amountLeft -= amountGive;
		}

		Map<Integer, ItemStack> left = inventory.addItem(items);

		for (ItemStack item : left.values()) {
			player.getWorld().dropItemNaturally(player.getLocation(), item);
		}

		return amount;
	}

}
