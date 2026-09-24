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
import org.luckyraven.keystone.item.ItemBuilder;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
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
	 * BZ-WM-06: a throwable's uuid is deterministic per type, so minting one for a second player used to overwrite
	 * the instance the first player's items already resolve to.
	 */
	@Test
	@DisplayName("minting a throwable type that is already registered keeps the registered instance (BZ-WM-06)")
	void getWeapon_throwableAlreadyRegistered_isNotOverwritten() {
		Weapon first  = service.getWeapon(null, null, "test_grenade", true);
		Weapon second = service.getWeapon(null, null, "test_grenade", true);

		assertNotNull(first);
		assertSame(first, second);
		assertSame(first, service.getWeapons().get(first.getUuid()));
	}

	/**
	 * BZ-WM-05: {@code getWeapon(String)} and {@code getWeapon(Player, String)} looked like read-only lookups but
	 * forwarded a null uuid into the minting overload, registering a fresh instance per call. They are gone; the
	 * read-only callers use {@code getWeaponTemplate}/{@code createTransientWeapon}.
	 */
	@Test
	@DisplayName("no read-only-looking getWeapon overload that mints and registers survives (BZ-WM-05)")
	void getWeapon_readOnlyOverloads_removed() {
		assertThrows(NoSuchMethodException.class, () -> WeaponService.class.getMethod("getWeapon", String.class));
		assertThrows(NoSuchMethodException.class,
		             () -> WeaponService.class.getMethod("getWeapon", Player.class, String.class));
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
	 * BZ-EV-04: a hand-edited or corrupted {@code uuid} tag threw {@link IllegalArgumentException} out of
	 * {@code UUID.fromString}, and so out of every listener that routes through {@code validateAndGetWeapon} - on
	 * every interaction, since the item stays in the inventory.
	 */
	@Test
	@DisplayName("a malformed uuid tag reads as no uuid instead of throwing out of every listener (BZ-EV-04)")
	void getWeaponUUID_malformedTag_returnsNull() {
		ItemStack item = weaponItem();

		try (MockedConstruction<ItemBuilder> ignored = mockConstruction(ItemBuilder.class, (builder, ctx) -> {
			when(builder.getStringTagData("uuid")).thenReturn("not-a-uuid");
			when(builder.getStringTagData("weapon")).thenReturn("test_gun");
		})) {
			assertNull(WeaponService.getWeaponUUID(item));
			assertNull(service.validateAndGetWeapon(mock(Player.class), item));
			assertTrue(service.getWeapons().isEmpty());
		}
	}

	/**
	 * BZ-HU-03: PlaceholderAPI resolves {@code %bartizan_*%} off the main thread (async chat formats, TAB and
	 * scoreboard refreshers). Through {@code validateAndGetWeapon} that overwrote the live instance's magazine from
	 * the item's not-yet-persisted NBT mid-shot (a refunded round) and raced {@code weapons.put}. {@code peekWeapon}
	 * must read the live instance as-is.
	 */
	@Test
	@DisplayName("peekWeapon returns the live instance without syncing it from item NBT (BZ-HU-03)")
	void peekWeapon_registered_doesNotOverwriteLiveState() {
		Weapon live = service.getWeapon(null, null, "test_gun", true);
		assertNotNull(live);
		live.setCurrentMagCapacity(29); // a shot consumed in memory, not yet written back to the item

		ItemStack item = weaponItem();
		try (MockedConstruction<ItemBuilder> ignored = weaponNbt(live.getUuid(), 30)) {
			assertSame(live, service.peekWeapon(item));
		}

		assertEquals(29, live.getCurrentMagCapacity());
		assertEquals(1, service.getWeapons().size());
	}

	@Test
	@DisplayName("peekWeapon on a never-used item reads its NBT into a snapshot and registers nothing (BZ-HU-03)")
	void peekWeapon_unregistered_snapshotsWithoutRegistering() {
		ItemStack item = weaponItem();
		UUID      uuid = UUID.randomUUID();

		Weapon peeked;
		try (MockedConstruction<ItemBuilder> ignored = weaponNbt(uuid, 12)) {
			peeked = service.peekWeapon(item);
		}

		assertNotNull(peeked);
		assertNotSame(gunTemplate, peeked);
		assertEquals(uuid, peeked.getUuid());
		assertEquals(12, peeked.getCurrentMagCapacity());
		assertEquals(30, gunTemplate.getCurrentMagCapacity(), "the shared template must not be mutated");
		assertTrue(service.getWeapons().isEmpty(), "a placeholder read must never grow the registry");
		assertFalse(service.getWeapons() instanceof java.util.HashMap,
		            "the registry is read off the main thread, so it must be a concurrent map");
	}

	/**
	 * BZ-WM-04: the registry only ever shrank on a full {@code /bartizan reload}, so every weapon a player used stayed
	 * pinned for the whole uptime after they left. Quitting now forgets the quitter's weapons; the shared per-type
	 * throwable entry stays for whoever else holds that type.
	 */
	@Test
	@DisplayName("forgetWeapons drops the quitter's weapons from the registry but keeps shared throwables (BZ-WM-04)")
	void forgetWeapons_dropsInventoryWeapons_keepsThrowables() {
		Weapon gun     = service.getWeapon(null, null, "test_gun", true);
		Weapon other   = service.getWeapon(null, null, "test_gun", true);
		Weapon grenade = service.getWeapon(null, null, "test_grenade", true);
		assertNotNull(gun);
		assertNotNull(other);
		assertNotNull(grenade);

		ItemStack       gunItem     = weaponItem();
		ItemStack       grenadeItem = weaponItem();
		PlayerInventory inventory   = mock(PlayerInventory.class);
		when(inventory.getContents()).thenReturn(new ItemStack[]{gunItem, null, grenadeItem});
		Player player = mock(Player.class);
		when(player.getInventory()).thenReturn(inventory);

		try (MockedConstruction<ItemBuilder> ignored = mockConstruction(ItemBuilder.class, (builder, ctx) -> {
			UUID uuid = ctx.arguments().get(0) == gunItem ? gun.getUuid() : grenade.getUuid();
			when(builder.getStringTagData("uuid")).thenReturn(uuid.toString());
		})) {
			service.forgetWeapons(player);
		}

		assertNull(service.getWeapons().get(gun.getUuid()));
		assertSame(other, service.getWeapons().get(other.getUuid()), "another item's weapon must stay registered");
		assertSame(grenade, service.getWeapons().get(grenade.getUuid()));
	}

	private static MockedConstruction<ItemBuilder> weaponNbt(UUID uuid, int ammoLeft) {
		return mockConstruction(ItemBuilder.class, (builder, ctx) -> {
			when(builder.getStringTagData("uuid")).thenReturn(uuid.toString());
			when(builder.getStringTagData("weapon")).thenReturn("test_gun");
			when(builder.getIntegerTagData("ammo-left")).thenReturn(ammoLeft);
			when(builder.getStringTagData("selective-fire")).thenReturn("single");
		});
	}

	private static ItemStack weaponItem() {
		ItemStack item = mock(ItemStack.class);
		when(item.getType()).thenReturn(Material.IRON_HOE);
		when(item.getAmount()).thenReturn(1);
		return item;
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
