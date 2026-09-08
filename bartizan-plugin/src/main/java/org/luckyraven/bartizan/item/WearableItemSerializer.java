package org.luckyraven.bartizan.item;

import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.luckyraven.keystone.item.ItemBuilder;
import org.luckyraven.keystone.item.ItemKind;
import org.luckyraven.keystone.item.ItemSerializer;
import org.luckyraven.bartizan.api.wearable.Wearable;

/**
 * Extracts the wearable registry name from {@link Wearable#NBT_KEY}.
 */
public final class WearableItemSerializer implements ItemSerializer {

	@Override
	public ItemKind kind() {
		return BartizanItemKind.WEARABLE;
	}

	@Override
	@Nullable
	public String extract(ItemStack stack) {
		return new ItemBuilder(stack).getStringTagData(Wearable.NBT_KEY);
	}
}
