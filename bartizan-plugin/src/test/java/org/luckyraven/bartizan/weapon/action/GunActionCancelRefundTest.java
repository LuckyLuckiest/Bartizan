package org.luckyraven.bartizan.weapon.action;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.raytrace.WeaponRaytracer;
import org.luckyraven.bartizan.api.support.WeaponFixtures;
import org.luckyraven.bartizan.api.weapon.GunWeapon;
import org.luckyraven.bartizan.effect.EffectRunner;
import org.luckyraven.bartizan.weapon.WeaponService;
import org.luckyraven.keystone.item.ItemBuilder;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BZ-FA-05: a cancelled {@code WeaponShootEvent} refunded a hardcoded 1 round regardless of
 * {@code Shoot.Consumed_Amount}, shorting every weapon configured with {@code Consumed_Amount > 1} one round on
 * every cancelled press.
 */
@DisplayName("GunAction — cancelled WeaponShootEvent refund (BZ-FA-05)")
class GunActionCancelRefundTest {

	@Test
	@DisplayName("a cancelled shot restores the exact pre-shot magazine count for Consumed_Amount > 1")
	void cancelledShot_restoresExactMagazineCount() {
		GunWeapon weapon = WeaponFixtures.gunWeapon(10, 3);
		assertEquals(10, weapon.getCurrentMagCapacity(), "sanity: full magazine before the shot");

		PlayerInventory inventory = mock(PlayerInventory.class);
		Player          shooter   = mock(Player.class);
		when(shooter.getInventory()).thenReturn(inventory);
		when(shooter.getEyeLocation()).thenReturn(new Location(null, 0, 64, 0, 0f, 0f));

		WeaponService weaponService = mock(WeaponService.class);
		JavaPlugin    plugin        = mock(JavaPlugin.class);
		ItemBuilder   heldItem      = mock(ItemBuilder.class);
		when(weaponService.getHeldWeaponItem(shooter, weapon)).thenReturn(heldItem);

		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
			PluginManager pluginManager = mock(PluginManager.class);
			bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);
			doAnswer(invocation -> {
				if (invocation.getArgument(0) instanceof Cancellable cancellable) cancellable.setCancelled(true);
				return null;
			}).when(pluginManager).callEvent(any());

			GunAction action = new GunAction(plugin, weaponService, weapon, mock(WeaponRaytracer.class),
			                                 mock(EffectRunner.class));

			action.weaponShoot(shooter);
		}

		assertEquals(10, weapon.getCurrentMagCapacity(),
		            "the 3-round consume must be fully restored, not refunded a hardcoded 1");
		verify(weaponService, never()).replaceHeldWeapon(any(), any(), any(ItemStack.class));
	}

}
