package org.luckyraven.bartizan.item;

import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.luckyraven.keystone.item.ItemBuilder;
import org.luckyraven.keystone.item.ItemKind;
import org.luckyraven.keystone.item.ItemSerializer;
import org.luckyraven.bartizan.api.ammo.Ammunition;

/**
 * Extracts the ammunition registry name from {@link Ammunition#NBT_KEY}.
 */
public final class AmmunitionItemSerializer implements ItemSerializer {

	@Override
	public ItemKind kind() {
		return BartizanItemKind.AMMUNITION;
	}

	@Override
	@Nullable
	public String extract(ItemStack stack) {
		return new ItemBuilder(stack).getStringTagData(Ammunition.NBT_KEY);
	}
}
