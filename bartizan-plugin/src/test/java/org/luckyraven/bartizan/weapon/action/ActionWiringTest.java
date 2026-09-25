package org.luckyraven.bartizan.weapon.action;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.raytrace.WeaponRaytracer;
import org.luckyraven.bartizan.api.support.WeaponFixtures;
import org.luckyraven.bartizan.api.weapon.BiologicalWeapon;
import org.luckyraven.bartizan.api.weapon.ThrowableWeapon;
import org.luckyraven.bartizan.api.weapon.WeaponType;
import org.luckyraven.bartizan.api.weapon.dto.BiologicalData;
import org.luckyraven.bartizan.api.weapon.dto.ChargeData;
import org.luckyraven.bartizan.api.weapon.dto.DurabilityData;
import org.luckyraven.bartizan.api.weapon.dto.EffectHook;
import org.luckyraven.bartizan.api.weapon.dto.ModifiersData;
import org.luckyraven.bartizan.effect.EffectRunner;
import org.luckyraven.bartizan.fire.PluginFireRegistry;
import org.luckyraven.bartizan.status.StatusEffectService;
import org.luckyraven.bartizan.weapon.WeaponService;
import org.luckyraven.keystone.util.ActionBarManager;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the call sites the BZ-FA-03/BZ-FA-06/BZ-FA-13 helpers are wired into: the helper tests
 * ({@code ThrowableActionAmmoTest}, {@code ThrowableActionDurabilityTest}, {@code BiologicalActionDurabilityTest})
 * call them directly, so deleting the calls from {@code activate()}/{@code fire()} failed nothing.
 */
@DisplayName("ThrowableAction / BiologicalAction - helper wiring")
class ActionWiringTest {

	@Test
	@DisplayName("a throw consumes a round, applies On_Shot durability and cosmetic-tags the grenade")
	void throwableActivate_callsAmmoAndDurabilityHelpers() {
		ThrowableWeapon weapon = WeaponFixtures.throwableWeapon(5);
		weapon.setDurabilityData(new DurabilityData());
		weapon.getThrowableData().setDisplayItem(mock(ItemStack.class));

		World                   world = mock(World.class);
		Item                    item  = mock(Item.class);
		PersistentDataContainer pdc   = mock(PersistentDataContainer.class);
		when(world.dropItem(any(), any())).thenReturn(item);
		when(item.getPersistentDataContainer()).thenReturn(pdc);

		Player player = mock(Player.class);
		when(player.getWorld()).thenReturn(world);
		when(player.getEyeLocation()).thenReturn(new Location(world, 0, 64, 0, 0f, 0f));
		when(player.getGameMode()).thenReturn(GameMode.CREATIVE); // no held-stack decrement to stub

		ThrowableAction action = spy(new ThrowableAction(mock(JavaPlugin.class), weapon,
		                                                 mock(PluginFireRegistry.class), mock(EffectRunner.class),
		                                                 mock(WeaponService.class)));

		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
			bukkit.when(Bukkit::getPluginManager).thenReturn(mock(PluginManager.class));
			bukkit.when(Bukkit::getScheduler).thenReturn(mock(BukkitScheduler.class));

			action.activate(player);
		}

		verify(action).consumeAmmoIfTracked(player);
		verify(action).applyDurabilityOnShot(player);
		verify(pdc).set(any(NamespacedKey.class), eq(PersistentDataType.BYTE), eq((byte) 1));
	}

	@Test
	@DisplayName("spawnGrenadeItem makes the display item unpickable and cosmetic-tagged (BZ-FA-13)")
	void spawnGrenadeItem_tagsTheItem() {
		World                   world = mock(World.class);
		Item                    item  = mock(Item.class);
		PersistentDataContainer pdc   = mock(PersistentDataContainer.class);
		Location                eye   = new Location(world, 0, 64, 0);
		ItemStack               shown = mock(ItemStack.class);
		when(world.dropItem(eye, shown)).thenReturn(item);
		when(item.getPersistentDataContainer()).thenReturn(pdc);

		ThrowableAction.spawnGrenadeItem(world, eye, shown);

		verify(item).setPickupDelay(Integer.MAX_VALUE);
		verify(pdc).set(any(NamespacedKey.class), eq(PersistentDataType.BYTE), eq((byte) 1));
	}

	@Test
	@DisplayName("a worn-out throwable is refused before the throw (BZ-FA-06)")
	void throwableActivate_broken_refused() {
		ThrowableWeapon weapon = WeaponFixtures.throwableWeapon(5);
		weapon.setCurrentDurability((short) 0);

		Player player = mock(Player.class);
		when(player.getUniqueId()).thenReturn(UUID.randomUUID());
		EffectRunner  effectRunner  = mock(EffectRunner.class);
		PluginManager pluginManager = mock(PluginManager.class);

		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
			bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);
			bukkit.when(Bukkit::getScheduler).thenReturn(mock(BukkitScheduler.class));

			new ThrowableAction(mock(JavaPlugin.class), weapon, mock(PluginFireRegistry.class), effectRunner,
			                    mock(WeaponService.class)).activate(player);
		}

		verify(pluginManager, never()).callEvent(any());
		verify(player, never()).getWorld();
		verify(effectRunner).run(eq(weapon), eq(EffectHook.ON_EMPTY), any());
	}

	@Test
	@DisplayName("a charged biological shot applies On_Shot durability")
	void biologicalFire_callsDurabilityHelper() {
		BiologicalData data = new BiologicalData(new ChargeData(20, 3, 1, false), List.of(), 30.0, 4.0,
		                                         WeaponFixtures.statusData(), false);
		BiologicalWeapon weapon = new BiologicalWeapon(UUID.randomUUID(), "test_biogun", "&fTest Biogun",
		                                               WeaponType.BIOLOGICAL, Material.IRON_HOE, 0, (short) 100,
		                                               List.of(), false, null, data, WeaponFixtures.instantReload(),
		                                               WeaponFixtures.ammoData(5, 1, 5));
		weapon.setDurabilityData(new DurabilityData());
		weapon.setModifiersData(new ModifiersData());

		Player player = mock(Player.class);
		when(player.getEyeLocation()).thenReturn(new Location(null, 0, 64, 0, 0f, 0f));
		WeaponService weaponService = mock(WeaponService.class);
		when(weaponService.getHeldHand(player, weapon.getUuid())).thenReturn(EquipmentSlot.HAND);

		BiologicalAction action = spy(new BiologicalAction(weapon, mock(WeaponRaytracer.class),
		                                                   mock(EffectRunner.class), mock(StatusEffectService.class),
		                                                   weaponService));

		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
		     MockedStatic<ActionBarManager> actionBar = mockStatic(ActionBarManager.class)) {
			bukkit.when(Bukkit::getPluginManager).thenReturn(mock(PluginManager.class));

			action.fire(player, 2);
		}

		verify(action).applyDurabilityOnShot(player);
		verify(weaponService).persistHeldWeapon(weapon, player);
	}

}
