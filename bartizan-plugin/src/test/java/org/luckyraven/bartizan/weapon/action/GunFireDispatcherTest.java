package org.luckyraven.bartizan.weapon.action;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.raytrace.WeaponRaytracer;
import org.luckyraven.bartizan.api.support.WeaponFixtures;
import org.luckyraven.bartizan.api.weapon.GunWeapon;
import org.luckyraven.bartizan.api.weapon.ProjectileType;
import org.luckyraven.bartizan.api.weapon.SelectiveFire;
import org.luckyraven.bartizan.api.weapon.WeaponType;
import org.luckyraven.bartizan.api.weapon.dto.DurabilityData;
import org.luckyraven.bartizan.api.weapon.dto.ProjectileData;
import org.luckyraven.bartizan.effect.EffectRunner;
import org.luckyraven.bartizan.weapon.WeaponService;
import org.luckyraven.keystone.timer.CountdownTimer;
import org.mockito.MockedConstruction;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;

@DisplayName("GunFireDispatcher")
class GunFireDispatcherTest {

	private final JavaPlugin    plugin        = mock(JavaPlugin.class);
	private final WeaponService weaponService = mock(WeaponService.class);
	private final Player        player        = mock(Player.class);

	private static GunWeapon gun(int perShot, int cooldown, SelectiveFire mode, int consumeOnTime) {
		ProjectileData projectile = ProjectileData.builder()
				.speed(3.0)
				.type(ProjectileType.BULLET)
				.damage(5.0)
				.consumed(1)
				.perShot(perShot)
				.cooldown(cooldown)
				.distance(60)
				.build();
		GunWeapon gun = new GunWeapon(UUID.randomUUID(), "test_gun", "&fTest Gun", WeaponType.GUN, Material.IRON_HOE,
		                              0, (short) 100, List.of(), false, null, mode, 0, projectile,
		                              WeaponFixtures.instantReload(), WeaponFixtures.ammoData(30, 1, 30));
		DurabilityData durability = new DurabilityData();
		durability.setConsumeOnTime(consumeOnTime);
		gun.setDurabilityData(durability);
		return gun;
	}

	private void shoot(GunWeapon gun) {
		GunFireDispatcher.shoot(plugin, weaponService, gun, mock(WeaponRaytracer.class), mock(EffectRunner.class),
		                        player);
	}

	// BZ-EV-06

	@Test
	@DisplayName("Consume_On_Time keeps one pending countdown per weapon, and re-arms once it has run")
	@SuppressWarnings("unchecked")
	void consumeOnTime_oneCountdownPerWeapon() {
		GunWeapon gun = gun(1, 4, SelectiveFire.SINGLE, 100);

		List<Consumer<CountdownTimer>> ends = new ArrayList<>();
		try (MockedConstruction<GunAction> ignored = mockConstruction(GunAction.class);
		     MockedConstruction<CountdownTimer> timers = mockConstruction(CountdownTimer.class,
				     (timer, context) -> ends.add((Consumer<CountdownTimer>) context.arguments().get(6)))) {
			shoot(gun);
			shoot(gun);
			shoot(gun);

			assertEquals(1, timers.constructed().size(), "every shot stacked its own countdown");

			ends.get(0).accept(timers.constructed().get(0));
			verify(weaponService).replaceHeldWeapon(player, gun, null);

			shoot(gun);
			assertEquals(2, timers.constructed().size(), "a shot after the countdown ran starts a fresh one");
		}
	}

}
