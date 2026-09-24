package org.luckyraven.bartizan.api.weapon;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.support.WeaponFixtures;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Pins the BZ-WM-11 fix: {@link Weapon#applyPush(Player)} dereferenced {@code recoilData} with no null guard,
 * unlike every other {@code recoilData} caller ({@link org.luckyraven.bartizan.api.weapon.recoil.RecoilManager
 * #applyRecoil} and the six action classes that call {@code applyPush} all check {@code getRecoilData() != null}
 * first). {@code recoilData} is only populated when a weapon YAML has a {@code Shoot.Recoil:} section — as every
 * {@code WeaponFixtures} factory intentionally leaves it, matching an admin-authored file that omits it — so a
 * consumer plugin calling {@code applyPush} directly (the whole point of the api/plugin split) got an immediate
 * NPE with no {@code @Nullable} annotation to warn them at compile time.
 */
@DisplayName("Weapon.applyPush — null recoilData guard")
class WeaponPushTest {

	@Test
	@DisplayName("applyPush is a no-op, not an NPE, when the weapon has no Shoot.Recoil: section at all")
	void applyPush_noRecoilData_noopNotNpe() {
		GunWeapon weapon = WeaponFixtures.gunWeapon(30, 1); // no setRecoilData call — matches an admin YAML with
		                                                     // no `Shoot.Recoil:` section
		Player player = mock(Player.class);

		assertDoesNotThrow(() -> weapon.applyPush(player));

		// The null guard must fire before any grounded/sneaking check touches the player at all.
		verifyNoInteractions(player);
	}

}
