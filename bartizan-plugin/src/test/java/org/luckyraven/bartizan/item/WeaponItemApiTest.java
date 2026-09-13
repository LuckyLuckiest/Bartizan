package org.luckyraven.bartizan.item;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.configuration.WeaponAddon;
import org.luckyraven.bartizan.support.PerStackNbtAccessor;
import org.luckyraven.bartizan.api.support.WeaponFixtures;
import org.luckyraven.bartizan.api.testsupport.BukkitRegistryFixture;
import org.luckyraven.bartizan.api.item.WeaponItemApi;
import org.luckyraven.bartizan.api.weapon.GunWeapon;
import org.luckyraven.bartizan.api.weapon.MeleeWeapon;
import org.luckyraven.bartizan.api.weapon.ThrowableWeapon;
import org.luckyraven.bartizan.weapon.WeaponService;
import org.luckyraven.keystone.item.nbt.NbtBridge;
import org.luckyraven.keystone.util.ChatUtil;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * bartizan.md §3 N-gap: pins the four {@link WeaponItemApi} members ({@code WeaponItemApiImpl}, bartizan.md
 * §1.6(9)) — the throwable-UUID determinism rule {@link #buildItem(String)} inherits from
 * {@code WeaponService.createTransientWeapon}, and that {@code isSameWeapon} is a read-only template comparison
 * (gate-GG review B3: it must never mint/register a weapon in {@code WeaponService} as a side effect of a
 * comparison called once per item by P3's sign/shop similarity code).
 *
 * <p>Uses {@link PerStackNbtAccessor} rather than keystone-testkit's {@code RecordingNbtAccessor} because
 * {@code isSameWeapon}/{@code cleanDisplayName} must read the {@code weapon} tag independently off two distinct
 * stacks — a flat, tag-name-keyed accessor cannot express two different stacks carrying different values for the
 * same tag name.
 */
@DisplayName("WeaponItemApiImpl — buildItem/isValidWeaponName/isSameWeapon/cleanDisplayName")
class WeaponItemApiTest {

	private WeaponAddon      addon;
	private WeaponService    service;
	private WeaponItemApi    api;
	private GunWeapon        gunTemplate;
	private MeleeWeapon      meleeTemplate;
	private ThrowableWeapon  throwableTemplate;

	@BeforeAll
	static void bootstrapBukkitRegistry() {
		// Weapon#buildItem's ItemBuilder calls route display-name/lore/durability writes through
		// ItemStack.getItemMeta(), which NPEs with no Bukkit.server installed at all — see the fixture javadoc.
		BukkitRegistryFixture.install();
	}

	@BeforeEach
	void setUp() {
		NbtBridge.install(new PerStackNbtAccessor());

		addon             = mock(WeaponAddon.class);
		gunTemplate       = WeaponFixtures.gunWeapon(30, 1);
		meleeTemplate     = WeaponFixtures.meleeWeapon(10);
		throwableTemplate = WeaponFixtures.throwableWeapon(1);

		when(addon.getWeapon("test_gun")).thenReturn(gunTemplate);
		when(addon.getWeapon("test_knife")).thenReturn(meleeTemplate);
		when(addon.getWeapon("test_grenade")).thenReturn(throwableTemplate);
		when(addon.getWeapons()).thenReturn(List.of(gunTemplate, meleeTemplate, throwableTemplate));

		service = new WeaponService(addon) {
		};
		api = new WeaponItemApiImpl(service);
	}

	@AfterEach
	void tearDown() {
		NbtBridge.reset();
	}

	@Test
	@DisplayName("buildItem gives throwables the deterministic uuid('throwable:'+name) so items keep stacking")
	void buildItem_throwable_usesDeterministicUuidAcrossCalls() {
		ItemStack first  = api.buildItem("test_grenade");
		ItemStack second = api.buildItem("test_grenade");

		UUID expected = UUID.nameUUIDFromBytes("throwable:test_grenade".getBytes(StandardCharsets.UTF_8));
		assertEquals(expected, WeaponService.getWeaponUUID(first));
		assertEquals(expected, WeaponService.getWeaponUUID(second));
	}

	@Test
	@DisplayName("buildItem returns null for an unknown weapon name")
	void buildItem_unknownName_returnsNull() {
		assertNull(api.buildItem("not_a_weapon"));
	}

	@Test
	@DisplayName("isValidWeaponName is true only for a name configured on the addon")
	void isValidWeaponName_matchesTheConfiguredCatalogue() {
		assertTrue(api.isValidWeaponName("test_gun"));
		assertFalse(api.isValidWeaponName("not_a_weapon"));
	}

	@Test
	@DisplayName("isSameWeapon is true for two independently-built copies of the same weapon and registers nothing")
	void isSameWeapon_trueForTwoIndependentCopiesOfTheSameWeapon() {
		ItemStack a = api.buildItem("test_gun");
		ItemStack b = api.buildItem("test_gun");
		assertNotSame(a, b);

		assertTrue(api.isSameWeapon(a, b));
		assertTrue(service.getWeapons().isEmpty(),
		           "isSameWeapon must be read-only — validateAndGetWeapon would mint and register a uuid'd weapon "
		           + "per call (gate-GG review B3)");
	}

	@Test
	@DisplayName("isSameWeapon is false for two different weapon types")
	void isSameWeapon_falseForDifferentWeapons() {
		ItemStack gun   = api.buildItem("test_gun");
		ItemStack knife = api.buildItem("test_knife");

		assertFalse(api.isSameWeapon(gun, knife));
		assertTrue(service.getWeapons().isEmpty());
	}

	@Test
	@DisplayName("isSameWeapon is false when either side is not a weapon at all")
	void isSameWeapon_falseWhenEitherSideIsNotAWeapon() {
		ItemStack gun      = api.buildItem("test_gun");
		ItemStack notAWeapon = new ItemStack(Material.STONE);

		assertFalse(api.isSameWeapon(gun, notAWeapon));
		assertFalse(api.isSameWeapon(notAWeapon, gun));
	}

	@Test
	@DisplayName("cleanDisplayName resolves the configured template display name, colored")
	void cleanDisplayName_resolvesTheConfiguredName() {
		ItemStack gun = api.buildItem("test_gun");

		assertEquals(ChatUtil.color(gunTemplate.getDisplayName()), api.cleanDisplayName(gun));
	}

	@Test
	@DisplayName("cleanDisplayName is null for a stack carrying no weapon tag")
	void cleanDisplayName_nullForANonWeaponStack() {
		assertNull(api.cleanDisplayName(new ItemStack(Material.STONE)));
	}

}
