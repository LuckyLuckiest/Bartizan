package org.luckyraven.bartizan.weapon.action;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.raytrace.WeaponRaytracer;
import org.luckyraven.bartizan.api.support.WeaponFixtures;
import org.luckyraven.bartizan.api.weapon.GunWeapon;
import org.luckyraven.bartizan.api.weapon.dto.DurabilityData;
import org.luckyraven.bartizan.configuration.WeaponAddon;
import org.luckyraven.bartizan.effect.EffectRunner;
import org.luckyraven.bartizan.raytrace.WeaponShooting;
import org.luckyraven.bartizan.weapon.WeaponService;
import org.luckyraven.keystone.item.ItemBuilder;
import org.mockito.MockedStatic;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BZ-EV-12 / BZ-FA-10: a {@code GunAction} shot resolves and writes back only its own weapon's item, matched by uuid
 * in either hand. Before, it took whatever weapon was in hand (main first, then off) and always wrote the main-hand
 * hotbar slot, so a leftover burst round after a hotbar swap drew on the holstered gun's in-memory magazine and
 * stamped it onto the newly held gun, and an off-hand gun's shot was written into the main-hand slot.
 * {@code getWeaponUUID} is stubbed statically: no NBT provider is installed in a unit test.
 */
@DisplayName("GunAction - held weapon resolved by uuid (BZ-EV-12, BZ-FA-10)")
class GunActionHeldWeaponTest {

	private GunWeapon       weaponA;
	private UUID            uuidB;
	private ItemStack       itemB;
	private ItemStack       offHandItem;
	private PlayerInventory inventory;
	private Player          shooter;
	private WeaponService   weaponService;

	@BeforeEach
	void setUp() {
		weaponA = WeaponFixtures.gunWeapon(30, 1);
		weaponA.setDurabilityData(new DurabilityData());

		GunWeapon weaponB = WeaponFixtures.gunWeapon(1, 1);
		uuidB = weaponB.getUuid();

		itemB       = item();
		offHandItem = item();

		inventory = mock(PlayerInventory.class);
		when(inventory.getItem(EquipmentSlot.HAND)).thenReturn(itemB);
		when(inventory.getItem(EquipmentSlot.OFF_HAND)).thenReturn(offHandItem);
		when(inventory.getItemInMainHand()).thenReturn(itemB);
		when(inventory.getItemInOffHand()).thenReturn(offHandItem);

		shooter = mock(Player.class);
		when(shooter.getInventory()).thenReturn(inventory);
		when(shooter.getEyeLocation()).thenReturn(new Location(null, 0, 64, 0, 0f, 0f));

		weaponService = spy(new WeaponService(mock(WeaponAddon.class)) {
		});
		weaponService.getWeapons().put(uuidB, weaponB);
	}

	@Test
	@DisplayName("a burst round landing after a hotbar swap consumes nothing and writes nothing")
	void holsteredWeapon_consumesNothingAndWritesNothing() {
		try (MockedStatic<WeaponService> uuids = mockStatic(WeaponService.class);
		     MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
		     MockedStatic<WeaponShooting> shooting = mockStatic(WeaponShooting.class)) {
			uuids.when(() -> WeaponService.getWeaponUUID(itemB)).thenReturn(uuidB);
			bukkit.when(Bukkit::getPluginManager).thenReturn(mock(PluginManager.class));
			shooting.when(() -> WeaponShooting.isHitscan(any())).thenReturn(true);

			newAction().weaponShoot(shooter);
		}

		assertEquals(30, weaponA.getCurrentMagCapacity(), "the holstered gun must not spend a round");
		verify(inventory, never()).setItem(anyInt(), any());
		verify(inventory, never()).setItem(any(EquipmentSlot.class), any());
	}

	@Test
	@DisplayName("an off-hand gun's shot is written to the off hand, never over the main-hand weapon")
	void offHandWeapon_writesToOffHandOnly() {
		ItemBuilder heldA  = mock(ItemBuilder.class);
		ItemStack   builtA = mock(ItemStack.class);
		when(heldA.build()).thenReturn(builtA);
		// the builder is mocked (a real one needs item meta), but only handed out when getHeldHand finds A
		doAnswer(invocation -> weaponService.getHeldHand(shooter, weaponA.getUuid()) != null ? heldA : null)
				.when(weaponService).getHeldWeaponItem(shooter, weaponA);

		try (MockedStatic<WeaponService> uuids = mockStatic(WeaponService.class);
		     MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
		     MockedStatic<WeaponShooting> shooting = mockStatic(WeaponShooting.class)) {
			uuids.when(() -> WeaponService.getWeaponUUID(itemB)).thenReturn(uuidB);
			uuids.when(() -> WeaponService.getWeaponUUID(offHandItem)).thenReturn(weaponA.getUuid());
			bukkit.when(Bukkit::getPluginManager).thenReturn(mock(PluginManager.class));
			shooting.when(() -> WeaponShooting.isHitscan(any())).thenReturn(true);

			newAction().weaponShoot(shooter);
		}

		assertEquals(29, weaponA.getCurrentMagCapacity());
		verify(inventory).setItem(EquipmentSlot.OFF_HAND, builtA);
		verify(inventory, never()).setItem(eq(EquipmentSlot.HAND), any());
		verify(inventory, never()).setItem(anyInt(), any());
	}

	private GunAction newAction() {
		return new GunAction(mock(JavaPlugin.class), weaponService, weaponA, mock(WeaponRaytracer.class),
		                     mock(EffectRunner.class));
	}

	private static ItemStack item() {
		ItemStack item = mock(ItemStack.class);
		when(item.getType()).thenReturn(Material.IRON_HOE);
		when(item.getAmount()).thenReturn(1);
		return item;
	}

}
