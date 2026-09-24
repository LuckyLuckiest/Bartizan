package org.luckyraven.bartizan.weapon.action;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.support.WeaponFixtures;
import org.luckyraven.bartizan.api.weapon.ThrowableWeapon;
import org.luckyraven.bartizan.api.weapon.WeaponType;
import org.luckyraven.bartizan.api.weapon.dto.ThrowableData;
import org.luckyraven.bartizan.effect.EffectRunner;
import org.luckyraven.bartizan.fire.PluginFireRegistry;
import org.luckyraven.bartizan.weapon.WeaponService;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * BZ-FA-03: {@code ThrowableAction.activate()} never called {@code weapon.consumeShot()}, so a throwable authored
 * with {@code Ammunition:}/{@code Reload:} sections had unlimited uses. {@code consumeAmmoIfTracked} is exercised
 * directly (rather than through {@code activate()}) because the rest of {@code activate()} needs a live
 * world/scheduler that a plain unit test can't serve — see the method's own javadoc.
 */
@DisplayName("ThrowableAction — Ammunition/Reload depletion (BZ-FA-03)")
class ThrowableActionAmmoTest {

	@Test
	@DisplayName("a throw depletes one round of a configured magazine and persists it onto the held item")
	void consumeAmmoIfTracked_withReloadConfigured_consumesOneRound() {
		ThrowableWeapon weapon        = WeaponFixtures.throwableWeapon(5);
		WeaponService   weaponService = mock(WeaponService.class);
		Player          player        = mock(Player.class);

		new ThrowableAction(mock(JavaPlugin.class), weapon, mock(PluginFireRegistry.class), mock(EffectRunner.class),
		                    weaponService).consumeAmmoIfTracked(player);

		assertEquals(4, weapon.getCurrentMagCapacity(), "5-round magazine loses one round per throw");
		verify(weaponService).persistHeldWeapon(weapon, player);
	}

	@Test
	@DisplayName("a throwable authored without Ammunition:/Reload: sections is unaffected")
	void consumeAmmoIfTracked_withoutReloadConfigured_consumesNothing() {
		ThrowableData throwableData = new ThrowableData();
		throwableData.setFuseTime(60);
		throwableData.setExplosionRadius(3.0);
		throwableData.setExplosionDamage(6);
		ThrowableWeapon weapon = new ThrowableWeapon(UUID.randomUUID(), "test_grenade", "&fTest Grenade",
		                                             WeaponType.THROWABLE, Material.IRON_HOE, 0, (short) 1, List.of(),
		                                             false, null, throwableData, null, null);
		WeaponService weaponService = mock(WeaponService.class);
		Player        player        = mock(Player.class);

		new ThrowableAction(mock(JavaPlugin.class), weapon, mock(PluginFireRegistry.class), mock(EffectRunner.class),
		                    weaponService).consumeAmmoIfTracked(player);

		assertEquals(0, weapon.getCurrentMagCapacity(), "no Ammunition: section — never tracked in the first place");
		verify(weaponService, never()).persistHeldWeapon(weapon, player);
	}

}
