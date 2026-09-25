package org.luckyraven.bartizan.api.weapon.reload;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.support.WeaponFixtures;
import org.luckyraven.bartizan.api.weapon.MeleeWeapon;
import org.luckyraven.keystone.item.ItemBuilder;
import org.mockito.MockedConstruction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

/**
 * BZ-EV-04: {@code Reload.readWeaponUUID} is a copy of {@code WeaponService.getWeaponUUID} that kept the unguarded
 * {@code UUID.fromString} - a corrupted uuid tag on any item ahead of the reloading weapon threw out of the reload's
 * insert stage and hung the reload for good.
 */
@DisplayName("Reload.findWeaponSlot - malformed uuid tag")
class ReloadFindWeaponSlotTest {

	@Test
	@DisplayName("an item with a malformed uuid tag is skipped and the real weapon is still found")
	void malformedUuidTag_skipped() {
		MeleeWeapon   weapon = WeaponFixtures.meleeWeapon(6);
		InstantReload reload = new InstantReload(weapon, weapon.getAmmunitionData().getAmmoType());

		ItemStack corrupted = item();
		ItemStack real      = item();
		PlayerInventory inventory = mock(PlayerInventory.class);
		when(inventory.getContents()).thenReturn(new ItemStack[]{corrupted, real});

		int slot;
		try (MockedConstruction<ItemBuilder> ignored = mockConstruction(ItemBuilder.class, (builder, ctx) -> {
			String tag = ctx.arguments().get(0) == corrupted ? "not-a-uuid" : weapon.getUuid().toString();
			when(builder.getStringTagData(anyString())).thenReturn(tag);
		})) {
			slot = reload.findWeaponSlot(inventory, weapon);
		}

		assertEquals(1, slot);
	}

	private static ItemStack item() {
		ItemStack item = mock(ItemStack.class);
		when(item.getType()).thenReturn(Material.IRON_HOE);
		when(item.getAmount()).thenReturn(1);
		return item;
	}

}
