package org.luckyraven.bartizan.weapon;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.configuration.WeaponAddon;
import org.luckyraven.bartizan.api.support.WeaponFixtures;
import org.luckyraven.bartizan.api.weapon.GunWeapon;
import org.luckyraven.bartizan.api.weapon.ThrowableWeapon;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.mockito.MockedStatic;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the read-only lookup seams added for WP-06 (P0, weapons.md observation #6).
 *
 * <p>Every {@code getWeapon(type)} call with a null uuid minted a fresh {@link Weapon} with a random uuid and put it
 * into {@code WeaponService.weapons}, so item converters, item refreshers, shop display names and throwable death
 * messages each grew the registry by one entry per call, without bound.
 *
 * <p>{@code getWeaponTemplate} / {@code getWeaponTemplates} / {@code createTransientWeapon} give those callers a
 * lookup that never registers anything; only a real give ({@code getWeapon(...)}) still mints.
 */
@DisplayName("WeaponService — template lookups never grow the registry (WP-06)")
class WeaponServiceTest {

	private WeaponAddon     addon;
	private WeaponService   service;
	private GunWeapon       gunTemplate;
	private ThrowableWeapon throwableTemplate;

	@BeforeEach
	void setUp() {
		addon             = mock(WeaponAddon.class);
		gunTemplate       = WeaponFixtures.gunWeapon(30, 1);
		throwableTemplate = WeaponFixtures.throwableWeapon(1);

		when(addon.getWeapon("test_gun")).thenReturn(gunTemplate);
		when(addon.getWeapon("test_grenade")).thenReturn(throwableTemplate);
		when(addon.getWeapons()).thenReturn(List.of(gunTemplate, throwableTemplate));

		service = new WeaponService(addon) {
		};
	}

	@Test
	@DisplayName("getWeaponTemplate hands back the shared catalogue entry and registers nothing")
	void getWeaponTemplate_registersNothing() {
		Weapon template = service.getWeaponTemplate("test_gun");

		assertSame(gunTemplate, template);
		assertTrue(service.getWeapons().isEmpty(),
		           "a read-only template lookup must not mint a registry entry");
	}

	@Test
	@DisplayName("getWeaponTemplate returns null for a null or unknown type instead of minting")
	void getWeaponTemplate_unknownType_returnsNull() {
		assertNull(service.getWeaponTemplate(null));
		assertNull(service.getWeaponTemplate(""));
		assertNull(service.getWeaponTemplate("not_a_weapon"));
		assertTrue(service.getWeapons().isEmpty());
	}

	@Test
	@DisplayName("getWeaponTemplates exposes the whole configured catalogue, not just the instances minted so far")
	void getWeaponTemplates_exposesCatalogue() {
		assertEquals(2, service.getWeaponTemplates().size());
		assertTrue(service.getWeaponTemplates().contains(gunTemplate));
		assertTrue(service.getWeapons().isEmpty());
	}

	@Test
	@DisplayName("createTransientWeapon builds a usable copy without registering it")
	void createTransientWeapon_registersNothing() {
		Weapon first  = service.createTransientWeapon("test_gun");
		Weapon second = service.createTransientWeapon("test_gun");

		assertNotNull(first);
		assertNotNull(second);
		assertNotSame(gunTemplate, first);
		assertNotSame(first, second);
		assertNotNull(first.getUuid());
		assertTrue(service.getWeapons().isEmpty(),
		           "converters and refreshers must not add a registry entry per item they build");
	}

	@Test
	@DisplayName("createTransientWeapon keeps the deterministic per-type uuid for throwables so items still stack")
	void createTransientWeapon_throwable_usesDeterministicUuid() {
		UUID expected = UUID.nameUUIDFromBytes(("throwable:test_grenade").getBytes(StandardCharsets.UTF_8));

		Weapon first  = service.createTransientWeapon("test_grenade");
		Weapon second = service.createTransientWeapon("test_grenade");

		assertNotNull(first);
		assertNotNull(second);
		assertEquals(expected, first.getUuid());
		assertEquals(expected, second.getUuid());
	}

	@Test
	@DisplayName("createTransientWeapon returns null for an unknown type")
	void createTransientWeapon_unknownType_returnsNull() {
		assertNull(service.createTransientWeapon("not_a_weapon"));
		assertNull(service.createTransientWeapon(null));
	}

	@Test
	@DisplayName("an actual give still mints and registers exactly one instance")
	void getWeapon_actualGive_stillRegisters() {
		Weapon given = service.getWeapon(null, null, "test_gun", true);

		assertNotNull(given);
		assertEquals(1, service.getWeapons().size());
		assertSame(given, service.getWeapons().get(given.getUuid()));
	}

	/**
	 * The main-then-off-hand probe shared by {@code BartizanApiImpl#getHeldWeapon} and
	 * {@code WeaponQuitCleanupListener}'s death/quit handlers (weapons-roadmap.md gate {@code HH} review fix).
	 */
	@Test
	@DisplayName("getHeldWeapon falls back to the off hand when the main hand is not a weapon")
	void getHeldWeapon_mainHandEmpty_fallsBackToOffHand() {
		WeaponService   spyService = spy(service);
		Player          player     = mock(Player.class);
		PlayerInventory inventory  = mock(PlayerInventory.class);
		ItemStack       mainHand   = mock(ItemStack.class);
		ItemStack       offHand    = mock(ItemStack.class);
		when(player.getInventory()).thenReturn(inventory);
		when(inventory.getItemInMainHand()).thenReturn(mainHand);
		when(inventory.getItemInOffHand()).thenReturn(offHand);

		Weapon offHandWeapon = mock(Weapon.class);
		doReturn(null).when(spyService).validateAndGetWeapon(player, mainHand);
		doReturn(offHandWeapon).when(spyService).validateAndGetWeapon(player, offHand);

		assertSame(offHandWeapon, spyService.getHeldWeapon(player));
	}

	/**
	 * Gate {@code HJ} review finding 4: a weapon read from the off hand is written back to the off hand, not the
	 * main-hand hotbar slot. {@code getWeaponUUID} is stubbed statically because no NBT provider is installed in a
	 * unit test, so real items carry no readable tags.
	 */
	@Test
	@DisplayName("persistHeldWeapon writes back to the off hand when the weapon is held there")
	void persistHeldWeapon_offHandWeapon_writesToOffHand() {
		UUID      weaponUuid   = UUID.randomUUID();
		ItemStack mainHandItem = mock(ItemStack.class);
		ItemStack offHandItem  = mock(ItemStack.class);
		Weapon    weapon       = weaponWithUuid(weaponUuid);

		PlayerInventory inventory = handsInventory(mainHandItem, offHandItem);
		Player          player    = playerWith(inventory);

		try (MockedStatic<WeaponService> uuids = mockStatic(WeaponService.class)) {
			uuids.when(() -> WeaponService.getWeaponUUID(offHandItem)).thenReturn(weaponUuid);

			service.persistHeldWeapon(weapon, player);
		}

		verify(inventory).setItem(EquipmentSlot.OFF_HAND, offHandItem);
		verify(inventory, never()).setItem(eq(EquipmentSlot.HAND), any());
		verify(inventory, never()).setItem(anyInt(), any(ItemStack.class));
	}

	/**
	 * BZ-WM-14: rifle A (reloading) sits in the off hand after an F swap and rifle B is in the main hand. Finishing
	 * A's reload used to stamp A's full magazine onto B, because any weapon in the main hand was the write target.
	 */
	@Test
	@DisplayName("persistHeldWeapon never writes a weapon's state onto a different weapon in the main hand")
	void persistHeldWeapon_otherWeaponInMainHand_writesOnlyToOwnItem() {
		UUID      weaponA = UUID.randomUUID();
		UUID      weaponB = UUID.randomUUID();
		ItemStack itemB   = mock(ItemStack.class);
		ItemStack itemA   = mock(ItemStack.class);
		Weapon    weapon  = weaponWithUuid(weaponA);

		PlayerInventory inventory = handsInventory(itemB, itemA);
		Player          player    = playerWith(inventory);

		try (MockedStatic<WeaponService> uuids = mockStatic(WeaponService.class)) {
			uuids.when(() -> WeaponService.getWeaponUUID(itemB)).thenReturn(weaponB);
			uuids.when(() -> WeaponService.getWeaponUUID(itemA)).thenReturn(weaponA);

			service.persistHeldWeapon(weapon, player);
		}

		verify(inventory, never()).setItem(eq(EquipmentSlot.HAND), any());
		verify(inventory, never()).setItem(anyInt(), any(ItemStack.class));
		verify(inventory).setItem(EquipmentSlot.OFF_HAND, itemA);
	}

	@Test
	@DisplayName("persistHeldWeapon is a no-op when neither hand holds the weapon")
	void persistHeldWeapon_weaponNotHeld_writesNothing() {
		ItemStack itemB  = mock(ItemStack.class);
		Weapon    weapon = weaponWithUuid(UUID.randomUUID());

		PlayerInventory inventory = handsInventory(itemB, mock(ItemStack.class));
		Player          player    = playerWith(inventory);

		try (MockedStatic<WeaponService> uuids = mockStatic(WeaponService.class)) {
			uuids.when(() -> WeaponService.getWeaponUUID(itemB)).thenReturn(UUID.randomUUID());

			service.persistHeldWeapon(weapon, player);
		}

		verify(weapon, never()).updateWeaponData(any(), any());
		verify(inventory, never()).setItem(any(EquipmentSlot.class), any());
		verify(inventory, never()).setItem(anyInt(), any());
	}

	@Test
	@DisplayName("persistHeldWeapon writes to the main hand when the weapon is held there")
	void persistHeldWeapon_mainHandWeapon_writesToMainHand() {
		UUID      weaponUuid = UUID.randomUUID();
		ItemStack mainItem   = mock(ItemStack.class);
		Weapon    weapon     = weaponWithUuid(weaponUuid);

		PlayerInventory inventory = handsInventory(mainItem, mock(ItemStack.class));
		Player          player    = playerWith(inventory);

		try (MockedStatic<WeaponService> uuids = mockStatic(WeaponService.class)) {
			uuids.when(() -> WeaponService.getWeaponUUID(mainItem)).thenReturn(weaponUuid);

			service.persistHeldWeapon(weapon, player);
		}

		verify(inventory).setItem(EquipmentSlot.HAND, mainItem);
		verify(inventory, never()).setItem(eq(EquipmentSlot.OFF_HAND), any());
	}

	private static Weapon weaponWithUuid(UUID uuid) {
		Weapon weapon = mock(Weapon.class);
		when(weapon.getUuid()).thenReturn(uuid);
		return weapon;
	}

	private static PlayerInventory handsInventory(ItemStack mainHand, ItemStack offHand) {
		for (ItemStack item : new ItemStack[]{mainHand, offHand}) {
			when(item.getType()).thenReturn(Material.IRON_HOE);
			when(item.getAmount()).thenReturn(1);
		}
		PlayerInventory inventory = mock(PlayerInventory.class);
		when(inventory.getItem(EquipmentSlot.HAND)).thenReturn(mainHand);
		when(inventory.getItem(EquipmentSlot.OFF_HAND)).thenReturn(offHand);
		return inventory;
	}

	private static Player playerWith(PlayerInventory inventory) {
		Player player = mock(Player.class);
		when(player.getInventory()).thenReturn(inventory);
		return player;
	}

}
