package org.luckyraven.bartizan.api.weapon;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.support.WeaponFixtures;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers gate {@code HG} item 5: {@link Weapon#isShootLocked()}/{@link Weapon#setShootLockedUntilMillis(long)},
 * the transient post-reload shoot-delay gate {@code Reload.Shoot_Delay_After_Reload} sets and {@code
 * GunAction#weaponShoot} checks.
 */
@DisplayName("Weapon shoot-lock (Reload.Shoot_Delay_After_Reload)")
class WeaponShootLockTest {

	@Test
	@DisplayName("a fresh weapon is never shoot-locked")
	void freshWeapon_notLocked() {
		GunWeapon weapon = WeaponFixtures.gunWeapon(6, 1);

		assertFalse(weapon.isShootLocked());
	}

	@Test
	@DisplayName("a future deadline locks shooting; a past deadline does not")
	void deadlineControlsLock() {
		GunWeapon weapon = WeaponFixtures.gunWeapon(6, 1);

		weapon.setShootLockedUntilMillis(System.currentTimeMillis() + 60_000L);
		assertTrue(weapon.isShootLocked());

		weapon.setShootLockedUntilMillis(System.currentTimeMillis() - 1L);
		assertFalse(weapon.isShootLocked());
	}

}
