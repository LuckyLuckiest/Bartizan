package org.luckyraven.bartizan.item;

import lombok.RequiredArgsConstructor;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.luckyraven.keystone.item.ItemBuilder;
import org.luckyraven.keystone.item.ItemKind;
import org.luckyraven.keystone.item.ItemSerializer;
import org.luckyraven.bartizan.api.wearable.Wearable;
import org.luckyraven.bartizan.wearable.WearableService;

/**
 * Extracts the wearable registry name from {@link Wearable#NBT_KEY}.
 */
@RequiredArgsConstructor
public final class WearableItemSerializer implements ItemSerializer {

	private final WearableService wearableService;

	/**
	 * Registered alongside {@link org.luckyraven.bartizan.api.BartizanItemPredicates#WEARABLE} at
	 * {@code BartizanItemVocabulary} (WS7-D4, Important-3 fix): excludes an externally-registered key so a foreign
	 * plugin's own item is never claimed by Bartizan's priority-0 serializer - the registrant's own serializer
	 * claims it instead.
	 */
	public boolean claims(ItemStack stack) {
		String key = new ItemBuilder(stack).getStringTagData(Wearable.NBT_KEY);
		if (key == null) return false;
		Wearable wearable = wearableService.getWearable(key);
		return wearable == null || !wearable.isExternal();
	}

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
