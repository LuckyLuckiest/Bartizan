package org.luckyraven.bartizan.item;

import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.luckyraven.keystone.color.Color;
import org.luckyraven.bartizan.util.BartizanChatUtil;
import org.luckyraven.keystone.item.ItemConverter;

import java.util.Arrays;
import java.util.Map;

/**
 * bartizan.md §1.2: lifted from {@code gangland-impl}'s {@code item.ItemAttributes} (lines 12-46). Base class for the
 * three converters — {@code GanglandChatUtil.color} becomes {@link BartizanChatUtil#color}.
 */
public abstract class ItemAttributes implements ItemConverter {

	public void applyAttributes(ItemStack itemStack, Map<String, String> attributes) {
		if (attributes.isEmpty()) return;

		ItemMeta meta = itemStack.getItemMeta();

		if (meta == null) return;

		if (attributes.containsKey("name")) {
			String name = BartizanChatUtil.color(attributes.get("name"));

			meta.setDisplayName(name);
		}

		if (attributes.containsKey("lore")) {
			var lore      = attributes.get("lore");
			var loreLines = Arrays.stream(lore.split(",")).map(String::trim).map(BartizanChatUtil::color).toList();

			meta.setLore(loreLines);
		}

		if (attributes.containsKey("color") && meta instanceof LeatherArmorMeta leatherArmorMeta) {
			try {
				String color        = attributes.get("color");
				var    leatherColor = Color.valueOf(color.toUpperCase()).getBukkitColor();

				leatherArmorMeta.setColor(leatherColor);
			} catch (IllegalArgumentException ignored) { }
		}

		itemStack.setItemMeta(meta);
	}

}
