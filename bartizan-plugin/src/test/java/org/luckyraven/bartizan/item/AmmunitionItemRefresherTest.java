package org.luckyraven.bartizan.item;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.ammo.AmmunitionManager;
import org.luckyraven.bartizan.api.ammo.Ammunition;
import org.luckyraven.bartizan.support.PerStackNbtAccessor;
import org.luckyraven.keystone.item.ItemBuilder;
import org.luckyraven.keystone.item.nbt.NbtBridge;
import org.luckyraven.keystone.util.ChatUtil;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * BZ-CF-16: a stack tagged with an ammo id that no longer exists in {@code ammunition.yml} (renamed/removed) must
 * fail safe, never fall through to the Material+display-name heuristic loop below - which can otherwise substitute
 * an unrelated ammo type that merely happens to share the stale item's material and (colored) display name.
 */
@DisplayName("AmmunitionItemRefresher#resolveAmmunition (BZ-CF-16)")
class AmmunitionItemRefresherTest {

	private AmmunitionManager manager;

	@BeforeEach
	void setUp() {
		NbtBridge.install(new PerStackNbtAccessor());
		manager = new AmmunitionManager();
	}

	@AfterEach
	void tearDown() {
		NbtBridge.reset();
	}

	@Test
	@DisplayName("a tagged-but-removed ammo id does not fall through to a coincidental Material+name match")
	void staleAmmoTag_doesNotFallThroughToHeuristicMatch() {
		// A currently-configured ammo type that happens to share the stale item's material and display name -
		// exactly the coincidence the heuristic loop can misfire on.
		Ammunition wrongMatch = new Ammunition("new_type", "&7Iron Scrap", Material.IRON_NUGGET, 0, List.of());
		manager.register("new_type", wrongMatch);

		ItemStack source = mock(ItemStack.class);
		ItemMeta  meta   = mock(ItemMeta.class);
		when(source.getType()).thenReturn(Material.IRON_NUGGET);
		when(source.getAmount()).thenReturn(1);
		when(source.getItemMeta()).thenReturn(meta);
		when(meta.hasDisplayName()).thenReturn(true);
		when(meta.getDisplayName()).thenReturn(ChatUtil.color(wrongMatch.getDisplayName()));

		// Tagged, but no ammunition.yml entry named "removed_type" exists any more.
		new ItemBuilder(source).addTag(Ammunition.NBT_KEY, "removed_type");

		AmmunitionItemRefresher refresher = new AmmunitionItemRefresher(manager);

		assertFalse(refresher.canRefresh(source),
		           "a removed ammo id must not claim the item via the Material+name heuristic");
		assertNull(refresher.refresh(source, null), "refresh() must not substitute the unrelated ammo type either");
	}

}
