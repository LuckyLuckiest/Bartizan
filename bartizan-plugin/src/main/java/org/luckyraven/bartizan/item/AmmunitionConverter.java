package org.luckyraven.bartizan.item;

import lombok.RequiredArgsConstructor;
import org.bukkit.inventory.ItemStack;
import org.luckyraven.bartizan.api.ammo.Ammunition;
import org.luckyraven.bartizan.ammo.AmmunitionManager;

import java.util.Map;

@RequiredArgsConstructor
public class AmmunitionConverter extends ItemAttributes {

	private final AmmunitionManager ammunitionManager;

	@Override
	public ItemStack convert(String type, String modifier, Map<String, String> attributes) {
		if (modifier == null || modifier.isBlank()) {
			return null;
		}

		Ammunition ammunition = ammunitionManager.getAmmunition(modifier);

		if (ammunition == null) {
			return null;
		}

		// clone the ammunition - buildItem() already sets lore from the Ammunition's own config (ItemBuilder#setLore),
		// so applyAttributes(...) is the only per-instance override left to apply.
		ItemStack itemStack = ammunition.buildItem();

		applyAttributes(itemStack, attributes);

		return itemStack;
	}

}
