package org.luckyraven.bartizan.weapon;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.configuration.WeaponAddon;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

		manager.onPreClear();

		verify(reloading).stopReloading();
		verify(idle, never()).stopReloading();
	}

}
