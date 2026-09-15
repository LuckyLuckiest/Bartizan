package org.luckyraven.bartizan;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;
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
 * Covers the gate-{@code HD} additions to {@link BartizanApiImpl}: {@code getHeldWeapon} delegating to
 * {@code WeaponManager.getHeldWeapon} (the main-then-off-hand probe itself is pinned directly against
 * {@code WeaponService} - see {@code WeaponServiceTest#getHeldWeapon_mainHandEmpty_fallsBackToOffHand}), and
 * {@code isScoping}/{@code isReloading} reading the resolved weapon's state; and the gate-{@code HH} addition,
 * {@code tryReload}, delegating to {@code WeaponManager.tryReload}.
 */
@DisplayName("BartizanApiImpl")
class BartizanApiImplTest {

	private final JavaPlugin      plugin        = mock(JavaPlugin.class);
	private final WeaponManager   weaponManager = mock(WeaponManager.class);
	private final BartizanApiImpl api           = new BartizanApiImpl(plugin, weaponManager, mock(WearableAddon.class),
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
	@DisplayName("getHeldWeapon delegates to WeaponManager.getHeldWeapon")
	void getHeldWeapon_mainHandEmpty_fallsBackToOffHand() {
		Player player = player(mock(ItemStack.class), mock(ItemStack.class));

		Weapon resolvedWeapon = mock(Weapon.class);
		when(weaponManager.getHeldWeapon(player)).thenReturn(resolvedWeapon);

		assertEquals(resolvedWeapon, api.getHeldWeapon(player));
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
		Player player = player(mock(ItemStack.class), mock(ItemStack.class));

		Weapon    weapon    = mock(Weapon.class);
		ScopeData scopeData = new ScopeData();
		scopeData.setLevel(2);
		scopeData.setScoped(true);
		when(weapon.getScopeData()).thenReturn(scopeData);
		when(weaponManager.getHeldWeapon(player)).thenReturn(weapon);

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
		Player player = player(mock(ItemStack.class), mock(ItemStack.class));

		Weapon weapon = mock(Weapon.class);
		when(weapon.isReloading()).thenReturn(true);
		when(weaponManager.getHeldWeapon(player)).thenReturn(weapon);

		assertTrue(api.isReloading(player));
	}

	@Test
	@DisplayName("isReloading is false when the player holds no weapon")
	void isReloading_noWeapon_isFalse() {
		Player player = player(mock(ItemStack.class), mock(ItemStack.class));

		assertFalse(api.isReloading(player));
	}

	@Test
	@DisplayName("tryReload delegates to WeaponManager.tryReload for the held weapon")
	void tryReload_delegatesToWeaponManager() {
		Player player = player(mock(ItemStack.class), mock(ItemStack.class));

		Weapon weapon = mock(Weapon.class);
		when(weaponManager.getHeldWeapon(player)).thenReturn(weapon);
		when(weaponManager.tryReload(plugin, player, weapon)).thenReturn(true);

		assertTrue(api.tryReload(player));
	}

	@Test
	@DisplayName("tryReload is false when the player holds no weapon")
	void tryReload_noWeapon_isFalse() {
		Player player = player(mock(ItemStack.class), mock(ItemStack.class));

		assertFalse(api.tryReload(player));
	}

}
