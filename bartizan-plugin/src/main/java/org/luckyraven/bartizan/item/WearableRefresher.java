package org.luckyraven.bartizan.item;

import lombok.RequiredArgsConstructor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.luckyraven.keystone.item.ItemBuilder;
import org.luckyraven.keystone.item.ItemRefresher;
import org.luckyraven.bartizan.api.wearable.Wearable;
import org.luckyraven.bartizan.wearable.WearableService;

/**
 * Rebuilds a wearable (vest / helmet / jetpack, etc.) into a factory-fresh copy with full durability and the
 * originally-defined stat block. Keyed by the {@link Wearable#NBT_KEY} tag.
 */
@RequiredArgsConstructor
public class WearableRefresher implements ItemRefresher {

	private final WearableService wearableService;

	@Override
	public boolean canRefresh(ItemStack source) {
		if (source == null || !new ItemBuilder(source).hasNBTTag(Wearable.NBT_KEY)) return false;
		Wearable wearable = wearableService.getWearable(new ItemBuilder(source).getStringTagData(Wearable.NBT_KEY));
		// null (unregistered/foreign tag) still claims - refresh() below no-ops safely; only an external
		// (WS7-D4) entry is excluded, since Wearable#buildItem() throws on its incomplete Material.
		return wearable == null || !wearable.isExternal();
	}

	@Override
	@Nullable
	public ItemStack refresh(ItemStack source, @Nullable Player context) {
		String key = new ItemBuilder(source).getStringTagData(Wearable.NBT_KEY);
		if (key == null || key.isEmpty()) return null;

		Wearable wearable = wearableService.getWearable(key);
		if (wearable == null) return null;

		ItemStack built = context != null ? wearable.buildItem(context) : wearable.buildItem();
		if (built == null) return null;

		built.setAmount(source.getAmount());
		return built;
	}

}
