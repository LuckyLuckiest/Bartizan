package org.luckyraven.bartizan.importer.wm;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.support.WeaponFixtures;
import org.luckyraven.bartizan.api.testsupport.BukkitRegistryFixture;
import org.luckyraven.bartizan.api.weapon.GunWeapon;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.api.weapon.WeaponTag;
import org.luckyraven.bartizan.support.PerStackNbtAccessor;
import org.luckyraven.bartizan.weapon.WeaponManager;
import org.luckyraven.keystone.item.ItemBuilder;
import org.luckyraven.keystone.item.nbt.NbtBridge;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * BZ-IM-01: {@link WmItemConverterListener#convert} must carry a WeaponMechanics item's {@code ammo-left} into
 * BOTH the registered {@code fresh} {@link Weapon}'s own in-memory {@code currentMagCapacity} AND the rebuilt
 * item's baked display name / {@code AMMO_LEFT} NBT tag - not just patch the tag after the fact, which leaves
 * {@code fresh} (and the tooltip {@link Weapon#buildItem()} already baked from it) stuck at a full magazine
 * until the player's next shot/reload re-syncs everything through {@code WeaponService#setWeaponData}.
 */
@DisplayName("WmItemConverterListener#convert (BZ-IM-01)")
class WmItemConverterListenerTest {

	private static final NamespacedKey WEAPON_TITLE = new NamespacedKey("weaponmechanics", "weapon-title");
	private static final NamespacedKey AMMO_LEFT     = new NamespacedKey("weaponmechanics", "ammo-left");

	@BeforeAll
	static void bootstrapBukkitRegistry() {
		// Weapon#buildItem's ItemBuilder calls route display-name/lore/durability writes through
		// ItemStack.getItemMeta(), which NPEs with no Bukkit.server installed at all - see the fixture javadoc.
		BukkitRegistryFixture.install();
	}

	@BeforeEach
	void setUp() {
		NbtBridge.install(new PerStackNbtAccessor());
	}

	@AfterEach
	void tearDown() {
		NbtBridge.reset();
	}

	@Test
	@DisplayName("a partially-spent WM item converts to a Bartizan item whose display name and AMMO_LEFT tag both "
	             + "show the actual ammo left, and whose registered Weapon instance is synced too - not a full magazine")
	void convert_syncsDisplayNameTagAndInMemoryWeapon_toActualAmmoLeft() throws Exception {
		// A freshly cloned weapon starts with a full 30/30 magazine - exactly what WeaponManager#getWeapon(...,
		// true) would hand back for a brand-new item under test.
		GunWeapon fresh = WeaponFixtures.gunWeapon(30, 1);
		assertEquals(30, fresh.getCurrentMagCapacity(), "sanity: fixture starts at a full magazine");

		WeaponManager weaponManager = mock(WeaponManager.class);
		when(weaponManager.getWeaponTemplate("test_gun")).thenReturn(fresh);
		when(weaponManager.getWeapon(null, null, "test_gun", true)).thenReturn(fresh);

		WmItemConverterListener listener = new WmItemConverterListener(weaponManager);

		ItemStack wmItem = mockWmItem("Test Gun", 12);

		ItemStack result = invokeConvert(listener, wmItem);

		assertEquals(12, fresh.getCurrentMagCapacity(),
		            "the registered fresh Weapon instance must carry the WM item's ammo-left, not stay at a full "
		            + "magazine until the next shot/reload re-syncs it");
		assertTrue(fresh.getChangingDisplayName().contains("«&612&7/&630&8»"),
		          () -> "the baked display name must show the actual ammo left (12/30), not the full magazine "
		                + "(30/30): was '" + fresh.getChangingDisplayName() + "'");

		int taggedAmmoLeft = new ItemBuilder(result).getIntegerTagData(Weapon.getTagProperName(WeaponTag.AMMO_LEFT));
		assertEquals(12, taggedAmmoLeft, "the rebuilt item's own AMMO_LEFT NBT tag must agree with the display name");
	}

	private static ItemStack mockWmItem(String title, int ammoLeft) {
		ItemStack                item = mock(ItemStack.class);
		ItemMeta                 meta = mock(ItemMeta.class);
		PersistentDataContainer  pdc  = mock(PersistentDataContainer.class);

		when(item.hasItemMeta()).thenReturn(true);
		when(item.getItemMeta()).thenReturn(meta);
		when(item.getAmount()).thenReturn(1);
		when(meta.getPersistentDataContainer()).thenReturn(pdc);
		when(pdc.get(WEAPON_TITLE, PersistentDataType.STRING)).thenReturn(title);
		when(pdc.get(AMMO_LEFT, PersistentDataType.INTEGER)).thenReturn(ammoLeft);

		return item;
	}

	private static ItemStack invokeConvert(WmItemConverterListener listener, ItemStack item) throws Exception {
		Method convert = WmItemConverterListener.class.getDeclaredMethod("convert", ItemStack.class);
		convert.setAccessible(true);
		return (ItemStack) convert.invoke(listener, item);
	}

}
