package org.luckyraven.bartizan.hud;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.Bartizan;
import org.luckyraven.bartizan.weapon.WeaponService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BZ-HU-03: PlaceholderAPI calls {@link BartizanExpansion#onPlaceholderRequest} from whatever thread asks (async
 * chat, TAB, scoreboards), so the expansion must only ever take the read-only lookup - never
 * {@code validateAndGetWeapon}, which mints into the registry and overwrites the live weapon from item NBT.
 */
@DisplayName("BartizanExpansion - read-only weapon lookup (BZ-HU-03)")
class BartizanExpansionTest {

	@Test
	@DisplayName("a placeholder request peeks the held weapon and never takes the minting/syncing lookup")
	void onPlaceholderRequest_usesReadOnlyLookup() {
		WeaponService   weaponService = mock(WeaponService.class);
		Player          player        = mock(Player.class);
		PlayerInventory inventory     = mock(PlayerInventory.class);
		ItemStack       held          = mock(ItemStack.class);
		when(player.getInventory()).thenReturn(inventory);
		when(inventory.getItemInMainHand()).thenReturn(held);

		BartizanExpansion expansion = new BartizanExpansion(mock(Bartizan.class), weaponService);

		assertEquals("", expansion.onPlaceholderRequest(player, "ammo_left"));

		verify(weaponService).peekWeapon(held);
		verify(weaponService, never()).validateAndGetWeapon(any(), any());
	}

}
