package org.luckyraven.bartizan.api.item;

import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.luckyraven.bartizan.api.weapon.dto.HandlingData;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Stamps {@code Information.Attributes} / {@code Attributes:} entries onto an item as Bukkit
 * {@link AttributeModifier}s (weapons-roadmap.md gate {@code HL}, §2). Extracted out of {@code Weapon} so
 * {@code Wearable#buildItem} can stamp its own {@code Attributes:} block through the exact same, already-tested
 * code path — each modifier is keyed by a stable {@link NamespacedKey} derived from {@code keyPrefix} and the
 * attribute itself, so calling this again (a rebuild of the same item) replaces the previous modifier instead of
 * stacking a duplicate.
 */
public final class AttributeModifiers {

	private AttributeModifiers() {
	}

	/**
	 * @param item the item to stamp modifiers onto; a no-op when {@code null}, when it has no {@link ItemMeta}, or
	 *        when {@code entries} is empty.
	 * @param entries the attribute entries to apply.
	 * @param slotGroup the equipment slot group the modifiers are scoped to (e.g. {@code MAINHAND} for a weapon,
	 *        the armour piece's own slot group for a wearable).
	 * @param keyPrefix the unique-per-item-definition prefix (e.g. {@code "attr_" + weaponName}) each modifier's
	 *        {@link NamespacedKey} is derived from; combined with the attribute's own key AND the entry's own list
	 *        index so two different attributes on the same item never collide, and — gate {@code HL} review, §5 —
	 *        neither do two entries for the SAME attribute (e.g. {@code swift}'s own {@code MOVEMENT_SPEED}
	 *        modifier and an explicit {@code Attributes.Movement_Speed}/{@code Information.Attributes} entry):
	 *        without the index, both derived the same key, so the loop's remove-then-add on the second entry
	 *        deleted the first one's modifier instead of adding its own.
	 */
	public static void apply(ItemStack item, List<HandlingData.AttributeEntry> entries, EquipmentSlotGroup slotGroup,
	                         String keyPrefix) {
		if (item == null || entries.isEmpty()) return;

		ItemMeta meta = item.getItemMeta();
		if (meta == null) return;

		for (int i = 0; i < entries.size(); i++) {
			HandlingData.AttributeEntry entry     = entries.get(i);
			Attribute                   attribute = entry.attribute();
			String    sanitized = (keyPrefix + "_" + i + "_" + attribute.getKey().getKey())
			                     .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
			NamespacedKey key = NamespacedKey.fromString("bartizan:" + sanitized);
			if (key == null) continue;

			// Real ItemMeta#getAttributeModifiers(Attribute) returns null — not an empty collection — when
			// nothing is registered for the attribute yet, i.e. every freshly-built item.
			Collection<AttributeModifier> existingModifiers = meta.getAttributeModifiers(attribute);
			if (existingModifiers != null) {
				for (AttributeModifier existing : existingModifiers) {
					if (existing.getKey().equals(key)) meta.removeAttributeModifier(attribute, existing);
				}
			}

			meta.addAttributeModifier(attribute, new AttributeModifier(key, entry.amount(), entry.operation(), slotGroup));
		}

		item.setItemMeta(meta);
	}

}
