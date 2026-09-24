package org.luckyraven.bartizan.weapon;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.configuration.WeaponAddon;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Lifecycle duties of the weapon registry bean.
 */
@DisplayName("WeaponManager lifecycle")
class WeaponManagerTest {

	/**
	 * BZ-WM-13: {@code /bartizan reload} wiped the registry while a reload's SequenceTimer kept running on the
	 * discarded instance; the next lookup minted a fresh, non-reloading one, so a second reload could run in parallel
	 * (ammo lost, and a refund dupe with {@code Unload_Ammo_On_Reload}).
	 */
	@Test
	@DisplayName("onPreClear stops every in-flight reload before the registry is wiped (BZ-WM-13)")
	void onPreClear_stopsInFlightReloads() {
		WeaponManager manager   = new WeaponManager(mock(WeaponAddon.class));
		Weapon        reloading = mock(Weapon.class);
		Weapon        idle      = mock(Weapon.class);
		when(reloading.isReloading()).thenReturn(true);
		manager.getWeapons().put(UUID.randomUUID(), reloading);
		manager.getWeapons().put(UUID.randomUUID(), idle);

		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
			bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of());

			manager.onPreClear();
		}

		verify(reloading).stopReloading();
		verify(idle, never()).stopReloading();
	}

	/**
	 * BZ-WM-12: scope and reload SLOWNESS/NIGHT_VISION run for Integer.MAX_VALUE ticks, and a server stop disables
	 * plugins before kicking players, so the quit cleanup never ran - the effects were saved into player data and
	 * outlived the restart.
	 */
	@Test
	@DisplayName("onShutdown stops reloads and unscopes every online player's held weapon (BZ-WM-12)")
	void onShutdown_stopsReloadsAndUnscopesOnlinePlayers() {
		WeaponManager manager   = spy(new WeaponManager(mock(WeaponAddon.class)));
		Weapon        reloading = mock(Weapon.class);
		Weapon        scoped    = mock(Weapon.class);
		when(reloading.isReloading()).thenReturn(true);
		manager.getWeapons().put(UUID.randomUUID(), reloading);

		ItemStack       mainHand  = mock(ItemStack.class);
		ItemStack       offHand   = mock(ItemStack.class);
		PlayerInventory inventory = mock(PlayerInventory.class);
		when(inventory.getItemInMainHand()).thenReturn(mainHand);
		when(inventory.getItemInOffHand()).thenReturn(offHand);
		Player player = mock(Player.class);
		when(player.getInventory()).thenReturn(inventory);
		doReturn(scoped).when(manager).peekWeapon(mainHand);
		doReturn(null).when(manager).peekWeapon(offHand);

		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
			bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(player));

			manager.onShutdown();
		}

		verify(reloading).stopReloading();
		verify(scoped).unScope(player, false);
	}

}
