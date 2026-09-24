package org.luckyraven.bartizan.weapon.action;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.raytrace.WeaponRaytracer;
import org.luckyraven.bartizan.api.support.WeaponFixtures;
import org.luckyraven.bartizan.api.weapon.GunWeapon;
import org.luckyraven.bartizan.api.weapon.SelectiveFire;
import org.luckyraven.bartizan.api.weapon.WeaponType;
import org.luckyraven.bartizan.api.weapon.dto.AmmunitionData;
import org.luckyraven.bartizan.api.weapon.dto.ProjectileData;
import org.luckyraven.bartizan.api.weapon.dto.ReloadData;
import org.luckyraven.bartizan.api.weapon.reload.ReloadType;
import org.luckyraven.bartizan.effect.EffectRunner;
import org.luckyraven.bartizan.weapon.WeaponService;
import org.luckyraven.keystone.item.ItemBuilder;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers gate {@code HG} items 5-6: {@code GunAction.weaponShoot}'s new {@code Reload.Shoot_Delay_After_Reload}
 * and {@code Reload.Auto_Reload_When_Empty} gates.
 *
 * <p>Only branches that return before touching {@code Bukkit.getPluginManager()}/{@code EmptyMagSoundGate} (both
 * need a live server — see {@code BiologicalActionTest}'s javadoc for the same constraint on action classes) are
 * exercised end-to-end through a real {@link GunAction}. The "auto-reload off, falls through to the empty click"
 * branch is a one-line guard clause and isn't independently tested here for that reason.
 */
@DisplayName("GunAction — Shoot_Delay_After_Reload / Auto_Reload_When_Empty")
class GunActionAutoReloadTest {

	@Test
	@DisplayName("isShootLocked: weaponShoot returns immediately, before even fetching the held item")
	void shootLocked_shortCircuits() {
		GunWeapon weapon = WeaponFixtures.gunWeapon(6, 1);
		weapon.setShootLockedUntilMillis(System.currentTimeMillis() + 10_000L);

		WeaponService weaponService = mock(WeaponService.class);
		Player        shooter       = mock(Player.class);
		GunAction     action        = new GunAction(mock(JavaPlugin.class), weaponService, weapon,
		                                            mock(WeaponRaytracer.class), mock(EffectRunner.class));

		action.weaponShoot(shooter);

		verify(weaponService, never()).getHeldWeaponItem(any(), any());
		assertTrue(weapon.isMagazineFull(), "no shot should have been attempted");
	}

	@Test
	@DisplayName("Auto_Reload_When_Empty: an empty magazine starts a reload through WeaponService.tryReload "
	             + "instead of playing the empty click")
	void autoReloadWhenEmpty_startsReload() {
		GunWeapon weapon = emptyGunWithAutoReload(true);

		WeaponService weaponService = mock(WeaponService.class);
		JavaPlugin    plugin        = mock(JavaPlugin.class);
		Player        shooter       = mock(Player.class);

		when(weaponService.getHeldWeaponItem(shooter, weapon)).thenReturn(mock(ItemBuilder.class));
		when(weaponService.tryReload(plugin, shooter, weapon)).thenReturn(true);

		GunAction action = new GunAction(plugin, weaponService, weapon, mock(WeaponRaytracer.class),
		                                 mock(EffectRunner.class));

		action.weaponShoot(shooter);

		verify(weaponService).tryReload(plugin, shooter, weapon);
	}

	private static GunWeapon emptyGunWithAutoReload(boolean autoReload) {
		ProjectileData projectile = ProjectileData.builder()
				.speed(3.0).damage(5.0).consumed(1).perShot(1).cooldown(4).distance(60).particle(false).gravity(0.0)
				.build();
		ReloadData reloadData = ReloadData.builder()
				.cooldown(20).type(ReloadType.getType("instant")).autoReloadWhenEmpty(autoReload).build();
		AmmunitionData ammunitionData = new AmmunitionData(WeaponFixtures.ammo("9mm"), 6, 1, 6);

		GunWeapon weapon = new GunWeapon(UUID.randomUUID(), "test_gun", "&fTest Gun", WeaponType.GUN,
		                                 Material.IRON_HOE, 0, (short) 100, List.of(), false, null,
		                                 SelectiveFire.SINGLE, 0, projectile, reloadData, ammunitionData);
		weapon.setCurrentMagCapacity(0); // empty magazine so consumeShot() fails
		return weapon;
	}

}
