package org.luckyraven.bartizan.weapon.action;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.raytrace.WeaponRaytracer;
import org.luckyraven.bartizan.api.support.WeaponFixtures;
import org.luckyraven.bartizan.api.weapon.BeamWeapon;
import org.luckyraven.bartizan.api.weapon.BiologicalWeapon;
import org.luckyraven.bartizan.api.weapon.IncendiaryWeapon;
import org.luckyraven.bartizan.api.weapon.dto.BeamData;
import org.luckyraven.bartizan.api.weapon.dto.DurabilityData;
import org.luckyraven.bartizan.api.weapon.modifiers.BlockDamageManager;
import org.luckyraven.bartizan.effect.EffectRunner;
import org.luckyraven.bartizan.fire.PluginFireRegistry;
import org.luckyraven.bartizan.status.StatusEffectService;
import org.luckyraven.bartizan.weapon.WeaponService;
import org.mockito.MockedStatic;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BZ-EV-13: a charge release (biological/beam) or a flamethrower spray tick that lands after the weapon left both
 * hands - Q-dropped or shift-clicked into the backpack while RMB is still down - fires nothing and consumes nothing.
 * The release watchdog and the AUTO spray loop only noticed a hotbar switch before. Every event the actions fire is
 * cancelled here so a (wrongly) firing action stops right after its first event, before any raytrace/world call a
 * unit test cannot serve.
 */
@DisplayName("Charge release / flamethrower spray with the weapon no longer in hand (BZ-EV-13)")
class UnheldWeaponFireTest {

	private Player        player;
	private WeaponService weaponService;
	private PluginManager pluginManager;

	@BeforeEach
	void setUp() {
		player = mock(Player.class);
		when(player.getEyeLocation()).thenReturn(new Location(null, 0, 64, 0, 0f, 0f));

		// a mock WeaponService finds the weapon in neither hand (getHeldHand -> null) unless a test says otherwise
		weaponService = mock(WeaponService.class);

		pluginManager = mock(PluginManager.class);
		doAnswer(invocation -> {
			if (invocation.getArgument(0) instanceof Cancellable cancellable) cancellable.setCancelled(true);
			return null;
		}).when(pluginManager).callEvent(any());
	}

	@Test
	@DisplayName("biological: a release after the syringe gun left both hands fires and consumes nothing")
	void biological_unheld_firesNothing() {
		BiologicalWeapon weapon = WeaponFixtures.biologicalWeapon(5);

		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
			bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);

			new BiologicalAction(weapon, mock(WeaponRaytracer.class), mock(EffectRunner.class),
			                     mock(StatusEffectService.class), weaponService).fire(player, 2);
		}

		verify(pluginManager, never()).callEvent(any());
		assertEquals(5, weapon.getCurrentMagCapacity());
		verify(weaponService, never()).persistHeldWeapon(any(), any());
	}

	@Test
	@DisplayName("biological: still fires while the weapon is in a hand")
	void biological_held_stillFires() {
		BiologicalWeapon weapon = WeaponFixtures.biologicalWeapon(5);
		when(weaponService.getHeldHand(player, weapon.getUuid())).thenReturn(EquipmentSlot.HAND);

		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
			bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);

			new BiologicalAction(weapon, mock(WeaponRaytracer.class), mock(EffectRunner.class),
			                     mock(StatusEffectService.class), weaponService).fire(player, 2);
		}

		verify(pluginManager).callEvent(any());
	}

	@Test
	@DisplayName("beam: a release after the beam weapon left both hands fires nothing")
	void beam_unheld_firesNothing() {
		BeamWeapon weapon = mock(BeamWeapon.class);
		when(weapon.getUuid()).thenReturn(UUID.randomUUID());
		when(weapon.getBeam()).thenReturn(new BeamData());

		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
			bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);

			new BeamAction(mock(JavaPlugin.class), weapon, mock(WeaponRaytracer.class), weaponService,
			               mock(EffectRunner.class), mock(BlockDamageManager.class)).fire(player, 2);
		}

		verify(pluginManager, never()).callEvent(any());
	}

	@Test
	@DisplayName("incendiary: a spray tick after the flamethrower left both hands sprays nothing and stops the loop")
	void incendiary_unheld_spraysNothingAndStopsLoop() {
		IncendiaryWeapon weapon = WeaponFixtures.incendiaryWeapon(5, 1);
		weapon.setDurabilityData(new DurabilityData());

		boolean fired;
		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
			bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);

			fired = new IncendiaryAction(mock(JavaPlugin.class), weaponService, weapon, mock(WeaponRaytracer.class),
			                             mock(PluginFireRegistry.class), mock(EffectRunner.class)).fireOnce(player);
		}

		assertFalse(fired, "false is what stops WeaponInteract's AUTO spray loop");
		verify(pluginManager, never()).callEvent(any());
		assertEquals(5, weapon.getCurrentMagCapacity());
	}

}
