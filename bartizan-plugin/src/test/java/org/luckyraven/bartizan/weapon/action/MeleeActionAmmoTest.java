package org.luckyraven.bartizan.weapon.action;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.raytrace.WeaponRaytracer;
import org.luckyraven.bartizan.api.support.WeaponFixtures;
import org.luckyraven.bartizan.api.weapon.MeleeWeapon;
import org.luckyraven.bartizan.api.weapon.WeaponType;
import org.luckyraven.bartizan.api.weapon.dto.MeleeData;
import org.luckyraven.bartizan.api.weapon.dto.ModifiersData;
import org.luckyraven.bartizan.effect.EffectRunner;
import org.luckyraven.bartizan.weapon.WeaponService;
import org.mockito.MockedStatic;

import org.bukkit.Material;

import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BZ-FA-03: {@code MeleeAction.activate()} never called {@code weapon.consumeShot()}, so a melee weapon authored
 * with {@code Ammunition:}/{@code Reload:} sections had unlimited uses — the {@code isMagazineEmpty()} guard read a
 * {@code currentMagCapacity} nothing ever decremented.
 */
@DisplayName("MeleeAction — Ammunition/Reload depletion (BZ-FA-03)")
class MeleeActionAmmoTest {

	@Test
	@DisplayName("a swing depletes one round of a configured magazine and persists it onto the held item")
	void swing_withReloadConfigured_consumesOneRound() {
		MeleeWeapon weapon = WeaponFixtures.meleeWeapon(5);
		weapon.setModifiersData(new ModifiersData());

		WeaponService weaponService = mock(WeaponService.class);
		Player        player        = mockPlayer();

		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
			PluginManager pluginManager = mock(PluginManager.class);
			bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);

			new MeleeAction(weapon, mock(WeaponRaytracer.class), new HashMap<>(), mock(EffectRunner.class),
			                weaponService).activate(player);
		}

		assertEquals(4, weapon.getCurrentMagCapacity(), "5-round magazine loses one round per swing");
		verify(weaponService).persistHeldWeapon(weapon, player);
	}

	@Test
	@DisplayName("a melee weapon authored without Ammunition:/Reload: sections is unaffected")
	void swing_withoutReloadConfigured_consumesNothing() {
		MeleeData    meleeData = new MeleeData(8.0, 3.0, 10, 0.5);
		MeleeWeapon  weapon    = new MeleeWeapon(UUID.randomUUID(), "test_knife", "&fTest Knife", WeaponType.MELEE,
		                                        Material.IRON_HOE, 0, (short) 50, List.of(), false, null, meleeData,
		                                        null, null);
		weapon.setModifiersData(new ModifiersData());

		WeaponService weaponService = mock(WeaponService.class);
		Player        player        = mockPlayer();

		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
			PluginManager pluginManager = mock(PluginManager.class);
			bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);

			new MeleeAction(weapon, mock(WeaponRaytracer.class), new HashMap<>(), mock(EffectRunner.class),
			                weaponService).activate(player);
		}

		assertEquals(0, weapon.getCurrentMagCapacity(), "no Ammunition: section — never tracked in the first place");
		verify(weaponService, never()).persistHeldWeapon(weapon, player);
	}

	/**
	 * BZ-FA-06: {@code applyOnHitDurability} wears a melee weapon down to 0, but nothing refused a swing once it got
	 * there - a worn-out knife kept swinging at full damage while a gun in the same state is refused.
	 */
	@Test
	@DisplayName("a worn-out melee weapon refuses the swing")
	void swing_broken_refused() {
		MeleeWeapon weapon = WeaponFixtures.meleeWeapon(5);
		weapon.setModifiersData(new ModifiersData());
		weapon.setCurrentDurability((short) 0);

		WeaponService weaponService = mock(WeaponService.class);
		Player        player        = mockPlayer();
		PluginManager pluginManager = mock(PluginManager.class);

		boolean hit;
		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
			bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);

			hit = new MeleeAction(weapon, mock(WeaponRaytracer.class), new HashMap<>(), mock(EffectRunner.class),
			                      weaponService).activate(player);
		}

		assertFalse(hit);
		assertEquals(5, weapon.getCurrentMagCapacity(), "a refused swing consumes nothing");
		verify(pluginManager, never()).callEvent(any());
	}

	private static Player mockPlayer() {
		Player player = mock(Player.class);
		Location loc = new Location(null, 0, 64, 0, 0f, 0f);
		when(player.getEyeLocation()).thenReturn(loc);
		when(player.getLocation()).thenReturn(loc);
		return player;
	}

}
