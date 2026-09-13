package org.luckyraven.bartizan.weapon.action;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.raytrace.WeaponRaytracer;
import org.luckyraven.bartizan.api.weapon.GunWeapon;
import org.luckyraven.bartizan.api.support.WeaponFixtures;
import org.luckyraven.bartizan.weapon.WeaponService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * The AUTO first-shot delay: {@code WeaponInteract.shootFullAuto} fires the first round synchronously via
 * {@code run()} and relies on the scheduled task resuming the cadence table on the very next tick. If the initial
 * delay ever drifts back to the projectile cooldown, every fresh AUTO press waits that long before its first round
 * and the cadence table is evaluated from the wrong index.
 */
@DisplayName("FullAutoTask")
class FullAutoTaskTest {

	@Test
	@DisplayName("schedules with a one-tick delay and period, not the projectile cooldown")
	void schedulesWithOneTickDelay() {
		GunWeapon weapon = WeaponFixtures.gunWeapon(30, 1); // cooldown 4 in the fixture

		FullAutoTask task = new FullAutoTask(mock(JavaPlugin.class), mock(WeaponService.class), weapon,
		                                     mock(WeaponRaytracer.class), mock(Player.class), mock(ItemStack.class),
		                                     () -> {
		                                     });

		assertEquals(1L, task.getDelay());
		assertEquals(1L, task.getPeriod());
	}

}
