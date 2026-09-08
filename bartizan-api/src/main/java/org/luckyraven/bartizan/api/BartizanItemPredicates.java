package org.luckyraven.bartizan.api;

import org.bukkit.inventory.ItemStack;
import org.luckyraven.bartizan.api.wearable.Wearable;
import org.luckyraven.keystone.item.ItemBuilder;

import java.util.function.Predicate;

/**
 * The {@code WEARABLE} predicate lifted from Gangland's {@code gangland-impl}
 * {@code item.ItemPredicates:25-45} (bartizan.md §1.1 group D). {@code WEAPON}/{@code AMMUNITION} live in
 * {@code item.WeaponItemPredicates} on the plugin side, not here.
 */
public final class BartizanItemPredicates {

	public static final Predicate<ItemStack> WEARABLE = stack -> hasTag(stack, Wearable.NBT_KEY);

	private BartizanItemPredicates() {
	}

	private static boolean hasTag(ItemStack stack, String tag) {
		if (stack == null) {
			return false;
		}
		return new ItemBuilder(stack).hasNBTTag(tag);
	}

}
