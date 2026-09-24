package org.luckyraven.bartizan.item;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.testsupport.BukkitRegistryFixture;
import org.luckyraven.bartizan.api.wearable.Wearable;
import org.luckyraven.bartizan.support.PerStackNbtAccessor;
import org.luckyraven.bartizan.wearable.WearableService;
import org.luckyraven.keystone.item.ItemBuilder;
import org.luckyraven.keystone.item.nbt.NbtBridge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * BZ-WE-06: an item tagged {@code wearable:<key>} for a key that is no longer registered (renamed/removed from
 * {@code wearables.yml}, or a foreign plugin's own {@code "wearable"} tag) must not be claimed by this refresher -
 * failing safe rather than silently falling through to a bare {@code ItemStack#clone()} downstream with no
 * diagnostic.
 */
@DisplayName("WearableRefresher#canRefresh (BZ-WE-06)")
class WearableRefresherTest {

	@BeforeAll
	static void bootstrapBukkitRegistry() {
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
	@DisplayName("an unregistered/foreign wearable tag is not claimed")
	void unregisteredTag_isNotClaimed() {
		WearableService    service   = new WearableService();
		WearableRefresher  refresher = new WearableRefresher(service);

		ItemBuilder builder = new ItemBuilder(Material.IRON_CHESTPLATE);
		builder.addTag(Wearable.NBT_KEY, "removed_wearable");
		ItemStack item = builder.build();

		assertFalse(refresher.canRefresh(item), "an unregistered wearable tag must not be claimed");
		assertNull(refresher.refresh(item, null));
	}

}
