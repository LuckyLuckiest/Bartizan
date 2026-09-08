package org.luckyraven.bartizan.item;

import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.luckyraven.keystone.item.ItemBuilder;
import org.luckyraven.keystone.item.ItemKind;
import org.luckyraven.keystone.item.ItemSerializer;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.api.weapon.WeaponTag;

/**
 * Extracts the weapon registry name from the canonical weapon NBT tag written by {@code WeaponConverter}.
 */
public final class WeaponItemSerializer implements ItemSerializer {

	@Override
	public ItemKind kind() {
		return BartizanItemKind.WEAPON;
	}

	@Override
	@Nullable
	public String extract(ItemStack stack) {
		return new ItemBuilder(stack).getStringTagData(Weapon.getTagProperName(WeaponTag.WEAPON));
	}
}
