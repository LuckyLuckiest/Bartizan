package org.luckyraven.bartizan.weapon.action;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.raytrace.WeaponRaytracer;
import org.luckyraven.bartizan.api.support.WeaponFixtures;
import org.luckyraven.bartizan.api.weapon.BiologicalWeapon;
import org.luckyraven.bartizan.api.weapon.dto.DurabilityData;
import org.luckyraven.bartizan.effect.EffectRunner;
import org.luckyraven.bartizan.status.StatusEffectService;
import org.luckyraven.bartizan.weapon.WeaponService;
import org.luckyraven.keystone.item.ItemBuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BZ-FA-06: {@code Durability_On_Shot} was silently ignored for biological weapons — no call site in
 * {@code BiologicalAction} ever decreased durability, so a syringe gun configured with a
 * {@code Durability.Change.On_Shot} never wore down and never broke. {@code applyDurabilityOnShot} is exercised
 * directly (rather than through {@code fire()}) because the rest of {@code fire()}'s effect tail needs a live
 * Bukkit potion/sound registry a plain unit test can't serve — see the method's own javadoc.
 */
@DisplayName("BiologicalAction — Durability_On_Shot (BZ-FA-06)")
class BiologicalActionDurabilityTest {

	@Test
	@DisplayName("On_Shot > 0 decreases durability and writes it onto the held item")
	void applyDurabilityOnShot_withOnShotConfigured_decreasesDurability() {
		BiologicalWeapon weapon = WeaponFixtures.biologicalWeapon(5);
		weapon.setDurabilityData(new DurabilityData());
		weapon.getDurabilityData().setOnShot((short) 10);
		assertEquals(100, weapon.getCurrentDurability(), "sanity: fixture's Durability.Base is 100");

		Player        player        = mock(Player.class);
		WeaponService weaponService = mock(WeaponService.class);
		ItemBuilder   heldWeapon    = mock(ItemBuilder.class);
		ItemStack     built         = mock(ItemStack.class);
		when(heldWeapon.build()).thenReturn(built);
		when(weaponService.getHeldWeaponItem(player, weapon)).thenReturn(heldWeapon);

		new BiologicalAction(weapon, mock(WeaponRaytracer.class), mock(EffectRunner.class),
		                     mock(StatusEffectService.class), weaponService).applyDurabilityOnShot(player);

		assertEquals(90, weapon.getCurrentDurability(), "100 base - 10 On_Shot");
		verify(weaponService).replaceHeldWeapon(player, weapon, built);
	}

	@Test
	@DisplayName("On_Shot: 0 (unconfigured) touches neither durability nor the held item")
	void applyDurabilityOnShot_withoutOnShotConfigured_doesNothing() {
		BiologicalWeapon weapon = WeaponFixtures.biologicalWeapon(5);
		weapon.setDurabilityData(new DurabilityData());

		Player        player        = mock(Player.class);
		WeaponService weaponService = mock(WeaponService.class);

		new BiologicalAction(weapon, mock(WeaponRaytracer.class), mock(EffectRunner.class),
		                     mock(StatusEffectService.class), weaponService).applyDurabilityOnShot(player);

		assertEquals(100, weapon.getCurrentDurability());
		verify(weaponService, never()).getHeldWeaponItem(player, weapon);
	}

}
