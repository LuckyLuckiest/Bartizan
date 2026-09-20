package org.luckyraven.bartizan.api.item;

import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.testsupport.BukkitRegistryFixture;
import org.luckyraven.bartizan.api.weapon.dto.HandlingData;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers {@link AttributeModifiers#apply} directly (weapons-roadmap.md gate {@code HL}, §2) — the shared helper
 * {@code Weapon#applyAttributeModifiers} and {@code Wearable#buildItem} both go through, extracted so a wearable
 * stamps {@code Attributes:} the exact same way a weapon does. {@code WeaponAttributeModifiersTest} (same module,
 * {@code weapon} package) pins the null-{@code getAttributeModifiers} trap through {@code Weapon}'s reflective
 * seam already; this class pins the same behaviour directly against the extracted helper, plus idempotent
 * re-apply and the {@code null}/empty no-op guards.
 */
class AttributeModifiersTest {

	@BeforeAll
	static void bootstrapBukkitRegistry() {
		BukkitRegistryFixture.install();
	}

	private static Attribute mockAttribute(String key) {
		Attribute attribute = mock(Attribute.class);
		when(attribute.getKey()).thenReturn(NamespacedKey.minecraft(key));
		return attribute;
	}

	@Test
	@DisplayName("an empty entry list is a no-op - no ItemMeta is even fetched")
	void apply_emptyEntries_isNoOp() {
		ItemStack item = mock(ItemStack.class);

		AttributeModifiers.apply(item, List.of(), EquipmentSlot.HEAD, "attr_test");

		verify(item, never()).getItemMeta();
	}

	@Test
	@DisplayName("survives a null getAttributeModifiers (every freshly-built item) and dedupes by id on re-apply")
	void apply_nullFirstCall_thenDedupesOnRebuild() {
		Attribute attribute = mockAttribute("armor");
		List<HandlingData.AttributeEntry> entries = List.of(
				new HandlingData.AttributeEntry(attribute, AttributeModifier.Operation.ADD_NUMBER, 4.0));

		ItemStack item = mock(ItemStack.class);
		ItemMeta  meta = mock(ItemMeta.class);
		when(item.getItemMeta()).thenReturn(meta);
		// Real ItemMeta#getAttributeModifiers(Attribute) returns null - not an empty collection - when nothing is
		// registered for the attribute yet, i.e. every freshly-built item.
		when(meta.getAttributeModifiers(attribute)).thenReturn(null);

		AttributeModifiers.apply(item, entries, EquipmentSlot.HEAD, "attr_test");

		ArgumentCaptor<AttributeModifier> addCaptor = ArgumentCaptor.forClass(AttributeModifier.class);
		verify(meta, times(1)).addAttributeModifier(eq(attribute), addCaptor.capture());
		AttributeModifier firstModifier = addCaptor.getValue();
		assertNotNull(firstModifier.getUniqueId());
		assertEquals(EquipmentSlot.HEAD, firstModifier.getSlot());
		assertEquals(4.0, firstModifier.getAmount());

		// Second call (e.g. a rebuild of the same held item): the meta now reports the modifier the first call
		// added. Calling apply() again must replace it by id, not stack a duplicate.
		when(meta.getAttributeModifiers(attribute)).thenReturn(List.of(firstModifier));
		AttributeModifiers.apply(item, entries, EquipmentSlot.HEAD, "attr_test");

		verify(meta, times(1)).removeAttributeModifier(eq(attribute), eq(firstModifier));
		verify(meta, times(2)).addAttributeModifier(eq(attribute), any());
	}

	@Test
	@DisplayName("a null ItemMeta is a no-op")
	void apply_nullItemMeta_isNoOp() {
		Attribute attribute = mockAttribute("armor");
		List<HandlingData.AttributeEntry> entries = List.of(
				new HandlingData.AttributeEntry(attribute, AttributeModifier.Operation.ADD_NUMBER, 4.0));

		ItemStack item = mock(ItemStack.class);
		when(item.getItemMeta()).thenReturn(null);

		AttributeModifiers.apply(item, entries, EquipmentSlot.HEAD, "attr_test");

		verify(item, never()).setItemMeta(any());
	}

	@Test
	@DisplayName("two different attributes on the same item get two independently-keyed modifiers")
	void apply_twoAttributes_bothStamped() {
		Attribute armor      = mockAttribute("armor");
		Attribute knockback  = mockAttribute("knockback_resistance");
		List<HandlingData.AttributeEntry> entries = List.of(
				new HandlingData.AttributeEntry(armor, AttributeModifier.Operation.ADD_NUMBER, 4.0),
				new HandlingData.AttributeEntry(knockback, AttributeModifier.Operation.ADD_NUMBER, 0.1));

		ItemStack item = mock(ItemStack.class);
		ItemMeta  meta = mock(ItemMeta.class);
		when(item.getItemMeta()).thenReturn(meta);
		when(meta.getAttributeModifiers(any(Attribute.class))).thenReturn(null);

		AttributeModifiers.apply(item, entries, EquipmentSlot.CHEST, "attr_hazmat_chest");

		verify(meta, times(1)).addAttributeModifier(eq(armor), any());
		verify(meta, times(1)).addAttributeModifier(eq(knockback), any());
		verify(item).setItemMeta(meta);
	}

	@Test
	@DisplayName("two entries for the SAME attribute (e.g. swift's own bonus + an explicit Movement_Speed) each keep "
			+ "their own modifier - gate HL review, §5")
	void apply_twoEntriesSameAttribute_bothKeepOwnModifier() {
		Attribute speed = mockAttribute("movement_speed");
		List<HandlingData.AttributeEntry> entries = List.of(
				new HandlingData.AttributeEntry(speed, AttributeModifier.Operation.ADD_SCALAR, 0.05),
				new HandlingData.AttributeEntry(speed, AttributeModifier.Operation.ADD_NUMBER, 0.1));

		ItemStack item = mock(ItemStack.class);
		ItemMeta  meta = mock(ItemMeta.class);
		when(item.getItemMeta()).thenReturn(meta);
		when(meta.getAttributeModifiers(speed)).thenReturn(null);

		AttributeModifiers.apply(item, entries, EquipmentSlot.HEAD, "attr_test");

		ArgumentCaptor<AttributeModifier> addCaptor = ArgumentCaptor.forClass(AttributeModifier.class);
		verify(meta, times(2)).addAttributeModifier(eq(speed), addCaptor.capture());
		List<AttributeModifier> added = addCaptor.getAllValues();
		assertNotEquals(added.get(0).getUniqueId(), added.get(1).getUniqueId(),
		                "each entry on the same attribute must own a distinct modifier id");

		// Re-apply (e.g. a rebuild of the same held item) must replace each entry's OWN modifier by its own id -
		// not drop the other one, which is exactly what a shared (attribute-only) key caused before this fix.
		when(meta.getAttributeModifiers(speed)).thenReturn(added);
		AttributeModifiers.apply(item, entries, EquipmentSlot.HEAD, "attr_test");

		verify(meta, times(1)).removeAttributeModifier(eq(speed), eq(added.get(0)));
		verify(meta, times(1)).removeAttributeModifier(eq(speed), eq(added.get(1)));
		verify(meta, times(4)).addAttributeModifier(eq(speed), any());
	}

}
