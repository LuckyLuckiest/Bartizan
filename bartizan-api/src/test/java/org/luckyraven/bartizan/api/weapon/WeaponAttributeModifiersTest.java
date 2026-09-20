package org.luckyraven.bartizan.api.weapon;

import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.support.WeaponFixtures;
import org.luckyraven.bartizan.api.testsupport.BukkitRegistryFixture;
import org.luckyraven.bartizan.api.weapon.dto.HandlingData;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins {@code Weapon#applyAttributeModifiers}'s handling of {@code Information.Attributes} (weapons-roadmap.md
 * gate {@code HE-a} review finding 1): real Bukkit {@code ItemMeta#getAttributeModifiers(Attribute)} returns
 * {@code null} — not an empty collection — when the meta has no modifiers for that attribute yet, which is exactly
 * the state of every freshly-built item, so this threw an NPE on the very first {@code buildItem()} of any weapon
 * configuring {@code Attributes:} (e.g. the shipped {@code awp.yml}).
 *
 * <p>Invoked via reflection against a hand-mocked {@link ItemStack}/{@link ItemMeta} rather than through the
 * public {@code buildItem()} — {@code BukkitRegistryFixture}'s stand-in {@code ItemFactory} returns a {@code null}
 * {@code ItemMeta} for every stack (by design, for the display-name/lore/durability paths other tests exercise),
 * which would skip this method's body entirely (the {@code meta == null} guard) and never reach the buggy line.
 * Mocking {@code ItemMeta} directly needs no Bukkit server for that part, but {@code mock(Attribute.class)} still
 * does — Mockito's inline mock maker loads the target type with {@code Class.forName(name, true, loader)}, which
 * runs {@code Attribute}'s own static initialiser (its {@code MOVEMENT_SPEED} etc. constants resolve through
 * {@code Bukkit.getRegistry(...)}) — see {@link BukkitRegistryFixture}'s javadoc trap (1).
 */
class WeaponAttributeModifiersTest {

	@BeforeAll
	static void bootstrapBukkitRegistry() {
		BukkitRegistryFixture.install();
	}

	@Test
	@DisplayName("buildItem's attribute-modifier application survives a null getAttributeModifiers and dedupes by key")
	void applyAttributeModifiers_nullFirstCall_thenDedupesOnRebuild() throws Exception {
		Attribute attribute = mock(Attribute.class);
		when(attribute.getKey()).thenReturn(NamespacedKey.minecraft("movement_speed"));

		GunWeapon weapon = WeaponFixtures.gunWeapon(30, 1);
		HandlingData handling = new HandlingData();
		handling.setAttributes(List.of(
				new HandlingData.AttributeEntry(attribute, AttributeModifier.Operation.ADD_SCALAR, -0.1)));
		weapon.setHandlingData(handling);

		Method applyAttributeModifiers = Weapon.class.getDeclaredMethod("applyAttributeModifiers", ItemStack.class);
		applyAttributeModifiers.setAccessible(true);

		ItemStack item = mock(ItemStack.class);
		ItemMeta  meta = mock(ItemMeta.class);
		when(item.getItemMeta()).thenReturn(meta);
		// Real ItemMeta#getAttributeModifiers(Attribute) returns null — not an empty collection — when nothing is
		// registered for the attribute yet, i.e. every freshly-built item. Stubbed explicitly: Mockito's own
		// default answer for a Collection-returning method is an EMPTY collection, which would NOT reproduce the
		// bug (the whole point of this test).
		when(meta.getAttributeModifiers(attribute)).thenReturn(null);
		applyAttributeModifiers.invoke(weapon, item); // must not throw NPE

		ArgumentCaptor<AttributeModifier> addCaptor = ArgumentCaptor.forClass(AttributeModifier.class);
		verify(meta, times(1)).addAttributeModifier(eq(attribute), addCaptor.capture());
		AttributeModifier firstModifier = addCaptor.getValue();
		assertNotNull(firstModifier.getUniqueId());

		// Second call (e.g. updateWeaponData rebuilding the same held item): the meta now reports the modifier the
		// first call added. The fix must remove it by id before re-adding, not stack a duplicate.
		when(meta.getAttributeModifiers(attribute)).thenReturn(List.of(firstModifier));
		applyAttributeModifiers.invoke(weapon, item);

		verify(meta, times(1)).removeAttributeModifier(eq(attribute), eq(firstModifier));
		verify(meta, times(2)).addAttributeModifier(eq(attribute), any());
	}

}
