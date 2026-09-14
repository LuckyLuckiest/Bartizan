package org.luckyraven.bartizan;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.ammo.AmmunitionManager;
import org.luckyraven.bartizan.api.item.WeaponItemApi;
import org.luckyraven.bartizan.api.npc.NpcWeaponFactory;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.api.weapon.dto.ScopeData;
import org.luckyraven.bartizan.weapon.WeaponManager;
import org.luckyraven.bartizan.wearable.WearableAddon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers the gate-{@code HD} additions to {@link BartizanApiImpl}: {@code getHeldWeapon} checking the main hand
 * then the off hand, and {@code isScoping}/{@code isReloading} reading the resolved weapon's state (mocking the
 * {@code WeaponCatalog} the way its own {@code WeaponManager} does {@code validateAndGetWeapon}).
 */
@DisplayName("BartizanApiImpl")
class BartizanApiImplTest {

	private final WeaponManager   weaponManager = mock(WeaponManager.class);
	private final BartizanApiImpl api           = new BartizanApiImpl(weaponManager, mock(WearableAddon.class),
	                                                                   mock(AmmunitionManager.class),
	                                                                   mock(NpcWeaponFactory.class),
	                                                                   mock(WeaponItemApi.class));

	private static Player player(ItemStack mainHand, ItemStack offHand) {
		Player          player    = mock(Player.class);
		PlayerInventory inventory = mock(PlayerInventory.class);
		when(player.getInventory()).thenReturn(inventory);
		when(inventory.getItemInMainHand()).thenReturn(mainHand);
		when(inventory.getItemInOffHand()).thenReturn(offHand);
		return player;
	}

	@Test
	@DisplayName("getHeldWeapon falls back to the off hand when the main hand is not a weapon")
	void getHeldWeapon_mainHandEmpty_fallsBackToOffHand() {
		ItemStack mainHand = mock(ItemStack.class);
		ItemStack offHand  = mock(ItemStack.class);
		Player    player   = player(mainHand, offHand);

		Weapon offHandWeapon = mock(Weapon.class);
		when(weaponManager.validateAndGetWeapon(player, mainHand)).thenReturn(null);
		when(weaponManager.validateAndGetWeapon(player, offHand)).thenReturn(offHandWeapon);

		assertEquals(offHandWeapon, api.getHeldWeapon(player));
	}

	@Test
	@DisplayName("getHeldWeapon returns null when neither hand holds a weapon")
	void getHeldWeapon_neitherHand_returnsNull() {
		Player player = player(mock(ItemStack.class), mock(ItemStack.class));

		assertEquals(null, api.getHeldWeapon(player));
	}

	@Test
	@DisplayName("isScoping reads the held weapon's ScopeData")
	void isScoping_readsScopeData() {
		ItemStack mainHand = mock(ItemStack.class);
		Player    player   = player(mainHand, mock(ItemStack.class));

		Weapon    weapon    = mock(Weapon.class);
		ScopeData scopeData = new ScopeData(2, true);
		when(weapon.getScopeData()).thenReturn(scopeData);
		when(weaponManager.validateAndGetWeapon(player, mainHand)).thenReturn(weapon);

		assertTrue(api.isScoping(player));

		scopeData.setScoped(false);
		assertFalse(api.isScoping(player));
	}

	@Test
	@DisplayName("isScoping is false when the player holds no weapon")
	void isScoping_noWeapon_isFalse() {
		Player player = player(mock(ItemStack.class), mock(ItemStack.class));

		assertFalse(api.isScoping(player));
	}

	@Test
	@DisplayName("isReloading reads the held weapon's isReloading()")
	void isReloading_readsWeapon() {
		ItemStack mainHand = mock(ItemStack.class);
		Player    player   = player(mainHand, mock(ItemStack.class));

		Weapon weapon = mock(Weapon.class);
		when(weapon.isReloading()).thenReturn(true);
		when(weaponManager.validateAndGetWeapon(player, mainHand)).thenReturn(weapon);

		assertTrue(api.isReloading(player));
	}

	@Test
	@DisplayName("isReloading is false when the player holds no weapon")
	void isReloading_noWeapon_isFalse() {
		Player player = player(mock(ItemStack.class), mock(ItemStack.class));

		assertFalse(api.isReloading(player));
	}

}
