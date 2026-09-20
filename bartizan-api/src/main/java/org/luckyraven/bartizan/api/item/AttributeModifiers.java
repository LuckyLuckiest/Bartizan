package org.luckyraven.bartizan.api.item;

import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.luckyraven.bartizan.api.weapon.dto.HandlingData;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Stamps {@code Information.Attributes} / {@code Attributes:} entries onto an item as Bukkit
 * {@link AttributeModifier}s (weapons-roadmap.md gate {@code HL}, §2). Extracted out of {@code Weapon} so
 * {@code Wearable#buildItem} can stamp its own {@code Attributes:} block through the exact same, already-tested
 * code path — each modifier carries a stable {@link UUID} derived from {@code keyPrefix} and the attribute itself,
 * so calling this again (a rebuild of the same item) replaces the previous modifier instead of stacking a duplicate.
 *
 * <p><b>Cross-version identity.</b> The compile floor is Spigot 1.16.5, whose modifiers are identified by
 * {@code UUID + name + EquipmentSlot}; 1.21 replaced that with a {@code NamespacedKey + EquipmentSlotGroup}. The
 * five-argument {@code UUID} constructor is the one shape present on both: 1.21 derives the key
 * {@code minecraft:<uuid>} from it and {@link AttributeModifier#getUniqueId()} round-trips that key back to the
 * same {@code UUID}, so deduplication by id behaves identically on either side.
 */
public final class AttributeModifiers {

	private AttributeModifiers() {
	}

	/**
	 * @param item the item to stamp modifiers onto; a no-op when {@code null}, when it has no {@link ItemMeta}, or
	 *        when {@code entries} is empty.
	 * @param entries the attribute entries to apply.
	 * @param slot the equipment slot the modifiers are scoped to (e.g. {@link EquipmentSlot#HAND} for a weapon, the
	 *        armour piece's own slot for a wearable).
	 * @param keyPrefix the unique-per-item-definition prefix (e.g. {@code "attr_" + weaponName}) each modifier's
	 *        {@link UUID} is derived from; combined with the attribute's own key AND the entry's own list index so
	 *        two different attributes on the same item never collide, and — gate {@code HL} review, §5 — neither do
	 *        two entries for the SAME attribute (e.g. {@code swift}'s own {@code MOVEMENT_SPEED} modifier and an
	 *        explicit {@code Attributes.Movement_Speed}/{@code Information.Attributes} entry): without the index,
	 *        both derived the same id, so the loop's remove-then-add on the second entry deleted the first one's
	 *        modifier instead of adding its own.
	 */
	public static void apply(ItemStack item, List<HandlingData.AttributeEntry> entries, EquipmentSlot slot,
	                         String keyPrefix) {
		if (item == null || entries.isEmpty()) return;

		ItemMeta meta = item.getItemMeta();
		if (meta == null) return;

		for (int i = 0; i < entries.size(); i++) {
			HandlingData.AttributeEntry entry     = entries.get(i);
			Attribute                   attribute = entry.attribute();
			String name = "bartizan:" + (keyPrefix + "_" + i + "_" + attribute.getKey().getKey())
					.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
			UUID   id   = UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));

			// Real ItemMeta#getAttributeModifiers(Attribute) returns null — not an empty collection — when
			// nothing is registered for the attribute yet, i.e. every freshly-built item.
			Collection<AttributeModifier> existingModifiers = meta.getAttributeModifiers(attribute);
			if (existingModifiers != null) {
				for (AttributeModifier existing : existingModifiers) {
					if (existing.getUniqueId().equals(id)) meta.removeAttributeModifier(attribute, existing);
				}
			}

			meta.addAttributeModifier(attribute, new AttributeModifier(id, name, entry.amount(), entry.operation(), slot));
		}

		item.setItemMeta(meta);
	}

}
