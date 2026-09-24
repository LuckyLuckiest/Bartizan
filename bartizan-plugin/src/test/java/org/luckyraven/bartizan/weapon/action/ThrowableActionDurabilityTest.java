package org.luckyraven.bartizan.weapon.action;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.support.WeaponFixtures;
import org.luckyraven.bartizan.api.weapon.ThrowableWeapon;
import org.luckyraven.bartizan.api.weapon.dto.DurabilityData;
import org.luckyraven.bartizan.effect.EffectRunner;
import org.luckyraven.bartizan.fire.PluginFireRegistry;
import org.luckyraven.bartizan.weapon.WeaponService;
import org.luckyraven.keystone.item.ItemBuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BZ-FA-06: {@code Durability_On_Shot} was silently ignored for throwables — no call site in
 * {@code ThrowableAction} ever decreased durability. {@code applyDurabilityOnShot} is exercised directly for the
 * same reason {@code consumeAmmoIfTracked} is in {@code ThrowableActionAmmoTest} — see that method's javadoc.
 */
@DisplayName("ThrowableAction — Durability_On_Shot (BZ-FA-06)")
class ThrowableActionDurabilityTest {

	@Test
	@DisplayName("a throw decreases durability by On_Shot and writes it onto the held item")
	void applyDurabilityOnShot_withOnShotConfigured_decreasesDurability() {
		ThrowableWeapon weapon = WeaponFixtures.throwableWeapon(5);
		weapon.setDurabilityData(new DurabilityData());
		weapon.getDurabilityData().setOnShot((short) 3);
		assertEquals(1, weapon.getCurrentDurability(), "sanity: fixture's Durability.Base is 1");

		WeaponService weaponService = mock(WeaponService.class);
		Player        player        = mock(Player.class);
		ItemBuilder   heldWeapon    = mock(ItemBuilder.class);
		ItemStack     built         = mock(ItemStack.class);
		when(heldWeapon.build()).thenReturn(built);
		when(weaponService.getHeldWeaponItem(player, weapon)).thenReturn(heldWeapon);

		new ThrowableAction(mock(JavaPlugin.class), weapon, mock(PluginFireRegistry.class), mock(EffectRunner.class),
		                    weaponService).applyDurabilityOnShot(player);

		assertEquals(0, weapon.getCurrentDurability(), "1 base - 3 On_Shot, clamped at 0");
		verify(weaponService).replaceHeldWeapon(player, weapon, built);
	}

	@Test
	@DisplayName("On_Shot: 0 (unconfigured) touches neither durability nor the held item")
	void applyDurabilityOnShot_withoutOnShotConfigured_doesNothing() {
		ThrowableWeapon weapon = WeaponFixtures.throwableWeapon(5);
		weapon.setDurabilityData(new DurabilityData());

		WeaponService weaponService = mock(WeaponService.class);
		Player        player        = mock(Player.class);

		new ThrowableAction(mock(JavaPlugin.class), weapon, mock(PluginFireRegistry.class), mock(EffectRunner.class),
		                    weaponService).applyDurabilityOnShot(player);

		assertEquals(1, weapon.getCurrentDurability());
		verify(weaponService, never()).getHeldWeaponItem(player, weapon);
	}

}
