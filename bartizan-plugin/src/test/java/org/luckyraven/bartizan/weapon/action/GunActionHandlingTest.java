package org.luckyraven.bartizan.weapon.action;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.raytrace.WeaponRaytracer;
import org.luckyraven.bartizan.api.support.WeaponFixtures;
import org.luckyraven.bartizan.api.weapon.GunWeapon;
import org.luckyraven.bartizan.api.weapon.SelectiveFire;
import org.luckyraven.bartizan.api.weapon.WeaponType;
import org.luckyraven.bartizan.api.weapon.dto.AmmunitionData;
import org.luckyraven.bartizan.api.weapon.dto.DurabilityData;
import org.luckyraven.bartizan.api.weapon.dto.HandlingData;
import org.luckyraven.bartizan.api.weapon.dto.ProjectileData;
import org.luckyraven.bartizan.api.weapon.dto.ReloadData;
import org.luckyraven.bartizan.api.weapon.reload.ReloadType;
import org.luckyraven.bartizan.effect.EffectRunner;
import org.luckyraven.bartizan.raytrace.WeaponShooting;
import org.luckyraven.bartizan.weapon.WeaponService;
import org.luckyraven.keystone.item.ItemBuilder;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers gate {@code HE} part a: {@code Shoot.Destroy_When_Empty} and {@code Reset_Fall_Distance}, following
 * {@code GunActionAutoReloadTest}'s pattern. {@code Bukkit.getPluginManager()} and the raytrace dispatch
 * ({@code WeaponShooting.fire}/{@code isHitscan}) are statically mocked, same technique
 * {@code WeaponDeathListenerTest}/{@code StatusEffectServiceTest} use — neither is available in a plain unit test.
 */
@DisplayName("GunAction — Destroy_When_Empty / Reset_Fall_Distance")
class GunActionHandlingTest {

	@Test
	@DisplayName("Destroy_When_Empty: removes the weapon after the shot that empties the magazine")
	void destroyWhenEmpty_removesAfterEmptyingShot() {
		GunWeapon weapon = gunWithHandling(1, handlingWith(true, false));

		PlayerInventory inventory = mock(PlayerInventory.class);
		when(inventory.getHeldItemSlot()).thenReturn(0);
		Player shooter = mockShooter(inventory);

		WeaponService weaponService = mock(WeaponService.class);
		JavaPlugin     plugin        = mock(JavaPlugin.class);
		when(weaponService.getHeldWeaponItem(shooter)).thenReturn(mock(ItemBuilder.class));

		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
		     MockedStatic<WeaponShooting> shooting = mockStatic(WeaponShooting.class)) {
			PluginManager pluginManager = mock(PluginManager.class);
			bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);
			shooting.when(() -> WeaponShooting.fire(any(), any(), any(), any(), any())).thenReturn(true);
			shooting.when(() -> WeaponShooting.isHitscan(any())).thenReturn(true);

			GunAction action = new GunAction(plugin, weaponService, weapon, mock(WeaponRaytracer.class),
			                                 mock(EffectRunner.class));

			action.weaponShoot(shooter);
		}

		// the magazine is now empty (maxMag=1, consumed=1) and the weapon carries Destroy_When_Empty: true
		verify(inventory, times(2)).setItem(eq(0), any());
	}

	@Test
	@DisplayName("Destroy_When_Empty: a non-empty magazine after the shot keeps the weapon")
	void destroyWhenEmpty_keepsWeaponWhileAmmoRemains() {
		GunWeapon weapon = gunWithHandling(2, handlingWith(true, false));

		PlayerInventory inventory = mock(PlayerInventory.class);
		when(inventory.getHeldItemSlot()).thenReturn(0);
		Player shooter = mockShooter(inventory);

		WeaponService weaponService = mock(WeaponService.class);
		JavaPlugin     plugin        = mock(JavaPlugin.class);
		when(weaponService.getHeldWeaponItem(shooter)).thenReturn(mock(ItemBuilder.class));

		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
		     MockedStatic<WeaponShooting> shooting = mockStatic(WeaponShooting.class)) {
			PluginManager pluginManager = mock(PluginManager.class);
			bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);
			shooting.when(() -> WeaponShooting.fire(any(), any(), any(), any(), any())).thenReturn(true);
			shooting.when(() -> WeaponShooting.isHitscan(any())).thenReturn(true);

			GunAction action = new GunAction(plugin, weaponService, weapon, mock(WeaponRaytracer.class),
			                                 mock(EffectRunner.class));

			action.weaponShoot(shooter);
		}

		// one shot out of a 2-round magazine: still not empty, so only the normal item-update setItem runs —
		// removeWeapon's extra AIR setItem must not.
		verify(inventory, times(1)).setItem(eq(0), any());
	}

	@Test
	@DisplayName("Reset_Fall_Distance: resets the shooter's fall distance after a successful shot")
	void resetFallDistance_resetsAfterShot() {
		GunWeapon weapon = gunWithHandling(6, handlingWith(false, true));

		PlayerInventory inventory = mock(PlayerInventory.class);
		when(inventory.getHeldItemSlot()).thenReturn(0);
		Player shooter = mockShooter(inventory);

		WeaponService weaponService = mock(WeaponService.class);
		JavaPlugin     plugin        = mock(JavaPlugin.class);
		when(weaponService.getHeldWeaponItem(shooter)).thenReturn(mock(ItemBuilder.class));

		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
		     MockedStatic<WeaponShooting> shooting = mockStatic(WeaponShooting.class)) {
			PluginManager pluginManager = mock(PluginManager.class);
			bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);
			shooting.when(() -> WeaponShooting.fire(any(), any(), any(), any(), any())).thenReturn(true);
			shooting.when(() -> WeaponShooting.isHitscan(any())).thenReturn(true);

			GunAction action = new GunAction(plugin, weaponService, weapon, mock(WeaponRaytracer.class),
			                                 mock(EffectRunner.class));

			action.weaponShoot(shooter);
		}

		verify(shooter).setFallDistance(0f);
		assertTrue(weapon.isMagazineFull() || !weapon.isMagazineEmpty(), "sanity: magazine not exhausted");
	}

	/**
	 * A {@code Player} mock whose {@code getEyeLocation()} returns a real {@link Location} (yaw/pitch 0) — needed
	 * because {@code weaponShoot} always reaches {@code EffectContext.shot}/{@code WeaponMuzzle.compute} on a
	 * successful shot, and those call real {@code Location}/{@code Vector} arithmetic that a deep mock chain would
	 * otherwise NPE on.
	 */
	private static Player mockShooter(PlayerInventory inventory) {
		Player shooter = mock(Player.class);
		when(shooter.getInventory()).thenReturn(inventory);
		when(shooter.getEyeLocation()).thenReturn(new Location(null, 0, 64, 0, 0f, 0f));
		return shooter;
	}

	private static HandlingData handlingWith(boolean destroyWhenEmpty, boolean resetFallDistance) {
		HandlingData handling = new HandlingData();
		handling.setDestroyWhenEmpty(destroyWhenEmpty);
		handling.setResetFallDistance(resetFallDistance);
		return handling;
	}

	private static GunWeapon gunWithHandling(int maxMag, HandlingData handling) {
		ProjectileData projectile = ProjectileData.builder()
				.speed(3.0).damage(5.0).consumed(1).perShot(1).cooldown(4).distance(60).particle(false).gravity(0.0)
				.build();
		ReloadData     reloadData     = ReloadData.builder().cooldown(20).type(ReloadType.getType("instant")).build();
		AmmunitionData ammunitionData = new AmmunitionData(WeaponFixtures.ammo("9mm"), maxMag, 1, maxMag);

		GunWeapon weapon = new GunWeapon(UUID.randomUUID(), "test_gun", "&fTest Gun", WeaponType.GUN,
		                                 Material.IRON_HOE, 0, (short) 100, List.of(), false, null,
		                                 SelectiveFire.SINGLE, 0, projectile, reloadData, ammunitionData);
		weapon.setDurabilityData(new DurabilityData());
		weapon.setHandlingData(handling);
		return weapon;
	}

}
