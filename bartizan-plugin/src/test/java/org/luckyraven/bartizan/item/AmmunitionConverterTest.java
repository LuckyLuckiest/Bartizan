package org.luckyraven.bartizan.item;

import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.ammo.AmmunitionManager;
import org.luckyraven.bartizan.api.ammo.Ammunition;
import org.luckyraven.bartizan.file.BartizanSettings;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BZ-CF-15: {@code convert()} must not overwrite the {@link ItemMeta} that {@link ItemAttributes#applyAttributes}
 * just wrote a name/lore/color override onto with a snapshot captured <em>before</em> that call ran. A real
 * server's {@code ItemStack#getItemMeta()} returns a fresh, detached copy on every call — never the same instance a
 * previous {@code setItemMeta()} was given — so two mock {@link ItemMeta} instances (one per call) reproduce that
 * here.
 */
@DisplayName("AmmunitionConverter#convert (BZ-CF-15)")
class AmmunitionConverterTest {

	@BeforeAll
	static void primeMoneySymbol() throws ReflectiveOperationException {
		// applyAttributes -> BartizanChatUtil.color() substitutes %money_symbol%, set only by BartizanSettings#init()
		// - not available in a plain unit test (same trap WeaponPlaceholdersTest documents).
		Field field = BartizanSettings.class.getDeclaredField("moneySymbol");
		field.setAccessible(true);
		field.set(null, "$");
	}

	@Test
	@DisplayName("a name attribute override survives convert(): setItemMeta is called exactly once, with the meta "
	             + "applyAttributes wrote to - never again with an earlier, pre-attribute snapshot")
	void convert_doesNotOverwriteAppliedAttributesWithAStaleMetaSnapshot() {
		AmmunitionManager manager = mock(AmmunitionManager.class);
		Ammunition        ammo    = mock(Ammunition.class);
		ItemStack         built   = mock(ItemStack.class);

		ItemMeta firstSnapshot  = mock(ItemMeta.class);
		ItemMeta secondSnapshot = mock(ItemMeta.class);
		when(built.getItemMeta()).thenReturn(firstSnapshot, secondSnapshot);
		// This ammo type has no configured Lore: block - the guard the dropped fallback block keyed off.
		when(firstSnapshot.hasLore()).thenReturn(false);
		when(secondSnapshot.hasLore()).thenReturn(false);

		when(manager.getAmmunition("rare_round")).thenReturn(ammo);
		when(ammo.buildItem()).thenReturn(built);
		when(ammo.getLore()).thenReturn(List.of());

		AmmunitionConverter converter = new AmmunitionConverter(manager);
		ItemStack           result    = converter.convert("ammunition", "rare_round", Map.of("name", "&6Rare Round"));

		assertEquals(built, result);

		ArgumentCaptor<ItemMeta> setCaptor = ArgumentCaptor.forClass(ItemMeta.class);
		verify(built, times(1)).setItemMeta(setCaptor.capture());
		verify(setCaptor.getValue()).setDisplayName(anyString());
	}

}
