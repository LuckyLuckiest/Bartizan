package org.luckyraven.bartizan.hud;

import org.bukkit.Material;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.support.WeaponFixtures;
import org.luckyraven.bartizan.api.testsupport.BukkitRegistryFixture;
import org.luckyraven.bartizan.api.weapon.GunWeapon;
import org.luckyraven.bartizan.api.weapon.ProjectileType;
import org.luckyraven.bartizan.api.weapon.SelectiveFire;
import org.luckyraven.bartizan.api.weapon.WeaponType;
import org.luckyraven.bartizan.api.weapon.dto.ProjectileData;
import org.luckyraven.bartizan.file.BartizanSettings;
import org.luckyraven.bartizan.util.BartizanChatUtil;

import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Covers {@link WeaponPlaceholders} (weapons-roadmap.md gate {@code HD}): every placeholder against a
 * {@code WeaponFixtures} gun, plus the no-magazine case ({@code %ammo_left%} empty, {@code %ammo_max%} the
 * infinity symbol) a weapon built without {@code Ammunition:}/{@code Reload:} exercises.
 */
@DisplayName("WeaponPlaceholders")
class WeaponPlaceholdersTest {

	@BeforeAll
	static void bootstrapBukkitRegistry() {
		BukkitRegistryFixture.install();
	}

	@BeforeAll
	static void primeMoneySymbol() throws ReflectiveOperationException {
		// resolve() -> BartizanChatUtil.color() substitutes %money_symbol%, set only by BartizanSettings#init() -
		// not available in a plain unit test (same trap StatusEffectServiceTest documents).
		Field field = BartizanSettings.class.getDeclaredField("moneySymbol");
		field.setAccessible(true);
		field.set(null, "$");
	}

	@Test
	@DisplayName("%weapon% is the plain display name (no ammo suffix), colorized")
	void weaponPlaceholder_isPlainDisplayNameColorized() {
		GunWeapon weapon = WeaponFixtures.gunWeapon(30, 1);

		assertEquals(BartizanChatUtil.color(weapon.getDisplayName()),
		             WeaponPlaceholders.resolve(weapon, null, "%weapon%"));
	}

	@Test
	@DisplayName("%ammo_left%/%ammo_max% report the magazine when the weapon has one")
	void ammoPlaceholders_reportMagazine() {
		GunWeapon weapon = WeaponFixtures.gunWeapon(30, 1);

		assertEquals("30", WeaponPlaceholders.resolve(weapon, null, "%ammo_left%"));
		assertEquals("30", WeaponPlaceholders.resolve(weapon, null, "%ammo_max%"));
	}

	@Test
	@DisplayName("no magazine: %ammo_left% is empty, %ammo_max% is the infinity symbol")
	void ammoPlaceholders_noMagazine() {
		GunWeapon weapon = gunWeaponWithNoMagazine();

		assertEquals("", WeaponPlaceholders.resolve(weapon, null, "%ammo_left%"));
		assertEquals("∞", WeaponPlaceholders.resolve(weapon, null, "%ammo_max%"));
	}

	@Test
	@DisplayName("%selective_fire% is the current fire mode name")
	void selectiveFirePlaceholder_currentMode() {
		GunWeapon weapon = WeaponFixtures.gunWeapon(30, 1);

		assertEquals(SelectiveFire.SINGLE.name(), WeaponPlaceholders.resolve(weapon, null, "%selective_fire%"));
	}

	@Test
	@DisplayName("%selective_fire% is empty when the weapon has no selective fire configured")
	void selectiveFirePlaceholder_none() {
		GunWeapon weapon = gunWeaponWithNoMagazine();
		weapon.setCurrentSelectiveFire(null);

		assertEquals("", WeaponPlaceholders.resolve(weapon, null, "%selective_fire%"));
	}

	@Test
	@DisplayName("%durability% is current/max, empty when unbreakable (Durability.Base <= 0)")
	void durabilityPlaceholder_currentOverMax_emptyWhenUnbreakable() {
		GunWeapon breakable = WeaponFixtures.gunWeapon(30, 1, (short) 100);
		assertEquals("100/100", WeaponPlaceholders.resolve(breakable, null, "%durability%"));

		GunWeapon unbreakable = WeaponFixtures.gunWeapon(30, 1, (short) 0);
		assertEquals("", WeaponPlaceholders.resolve(unbreakable, null, "%durability%"));
	}

	@Test
	@DisplayName("not reloading: %firearm_state% is ready, %reload%/%reload_progress% are empty")
	void notReloading_readyStateAndEmptyReloadPlaceholders() {
		GunWeapon weapon = WeaponFixtures.gunWeapon(30, 1);

		assertEquals("ready", WeaponPlaceholders.resolve(weapon, null, "%firearm_state%"));
		assertEquals("", WeaponPlaceholders.resolve(weapon, null, "%reload%"));
		assertEquals("", WeaponPlaceholders.resolve(weapon, null, "%reload_progress%"));
	}

	@Test
	@DisplayName("%firearm_state% is empty once the magazine is drained")
	void firearmState_emptyMagazine() {
		GunWeapon weapon = WeaponFixtures.gunWeapon(1, 1);
		weapon.consumeShot();

		assertEquals("empty", WeaponPlaceholders.resolve(weapon, null, "%firearm_state%"));
	}

	private static GunWeapon gunWeaponWithNoMagazine() {
		ProjectileData projectile = ProjectileData.builder()
				.speed(3.0)
				.type(ProjectileType.BULLET)
				.damage(5.0)
				.consumed(1)
				.perShot(1)
				.cooldown(4)
				.distance(60)
				.particle(false)
				.gravity(0.0)
				.build();

		return new GunWeapon(UUID.randomUUID(), "test_gun_no_mag", "&fNo Mag Gun", WeaponType.GUN, Material.IRON_HOE,
		                     0, (short) 100, List.of(), false, null, SelectiveFire.SINGLE, 0, projectile, null, null);
	}

}
