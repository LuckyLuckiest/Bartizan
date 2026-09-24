package org.luckyraven.bartizan.api.weapon;

import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.ammo.Ammunition;
import org.luckyraven.bartizan.api.weapon.dto.AmmunitionData;
import org.luckyraven.bartizan.api.weapon.dto.ProjectileData;
import org.luckyraven.bartizan.api.weapon.dto.ReloadData;
import org.luckyraven.bartizan.api.weapon.reload.Reload;
import org.luckyraven.bartizan.api.weapon.reload.ReloadType;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the four config-string parsing enums the weapons.md audit's Test Surface groups together under one
 * bullet ("WeaponType.getType, ProjectileType.getType, ThrowableType.getType, ReloadType.getType"):
 * {@link WeaponType}, {@link ProjectileType}, {@link ThrowableType} and {@link ReloadType}. Each is a tiny,
 * structurally identical {@code getType(String)} switch, so one test class covers the family instead of four
 * near-empty ones.
 *
 * <p>Pins Observation #30 (weapons.md): an unrecognised {@code Category} silently falls through to
 * {@link WeaponType#OTHER} rather than failing loudly.
 *
 * <p>BZ-WM-03 (fixed): {@code ReloadType} used to carry {@code amount} as a mutable field on the shared enum
 * constant, so two weapons parsed with different {@code num-N} amounts collided on whichever loaded last. The
 * amount is now per-weapon state on {@link org.luckyraven.bartizan.api.weapon.dto.ReloadData}, passed explicitly
 * into {@link ReloadType#createInstance}.
 */
@DisplayName("Type-parsing enums — WeaponType / ProjectileType / ThrowableType / ReloadType")
class TypeEnumParsingTest {

	@Test
	@DisplayName("WeaponType.getType maps every known category alias, case-insensitively")
	void weaponType_mapsKnownAliases() {
		assertSame(WeaponType.GUN, WeaponType.getType("gun"));
		assertSame(WeaponType.MELEE, WeaponType.getType("MELEE"));
		assertSame(WeaponType.THROWABLE, WeaponType.getType("throwable"));
		assertSame(WeaponType.THROWABLE, WeaponType.getType("throw"));
		assertSame(WeaponType.THROWABLE, WeaponType.getType("grenade"));
		assertSame(WeaponType.THROWABLE, WeaponType.getType("projectile"));
		assertSame(WeaponType.THROWABLE, WeaponType.getType("proj"));
		assertSame(WeaponType.INCENDIARY, WeaponType.getType("incendiary"));
		assertSame(WeaponType.INCENDIARY, WeaponType.getType("fire"));
		assertSame(WeaponType.BIOLOGICAL, WeaponType.getType("biological"));
		assertSame(WeaponType.BIOLOGICAL, WeaponType.getType("biology"));
		assertSame(WeaponType.BIOLOGICAL, WeaponType.getType("bio"));
		assertSame(WeaponType.BEAM, WeaponType.getType("beam"));
		assertSame(WeaponType.BEAM, WeaponType.getType("LASER"));
	}

	@Test
	@DisplayName("WeaponType.getType — an unrecognised Category silently becomes OTHER (Observation #30, weapons.md)")
	void weaponType_unknownCategory_becomesOther() {
		assertSame(WeaponType.OTHER, WeaponType.getType("not-a-real-category"));
		assertSame(WeaponType.OTHER, WeaponType.getType(""));
	}

	@Test
	@DisplayName("ProjectileType.getType maps flare/spread/rocket, defaults to BULLET")
	void projectileType_mapsKnownAliases() {
		assertSame(ProjectileType.FLARE, ProjectileType.getType("flare"));
		assertSame(ProjectileType.SPREAD, ProjectileType.getType("spread"));
		assertSame(ProjectileType.ROCKET, ProjectileType.getType("rocket"));
		assertSame(ProjectileType.BULLET, ProjectileType.getType("bullet"));
		assertSame(ProjectileType.BULLET, ProjectileType.getType("anything-else"));
	}

	@Test
	@DisplayName("ThrowableType.getType maps smoke/stun, defaults to EXPLOSIVE including for a null Type: key")
	void throwableType_mapsKnownAliasesAndNullDefault() {
		assertSame(ThrowableType.SMOKE, ThrowableType.getType("smoke"));
		assertSame(ThrowableType.STUN, ThrowableType.getType("stun"));
		assertSame(ThrowableType.EXPLOSIVE, ThrowableType.getType("explosive"));
		assertSame(ThrowableType.EXPLOSIVE, ThrowableType.getType(null),
		           "missing Type: key must keep the legacy EXPLOSIVE behaviour");
	}

	@Test
	@DisplayName("ReloadType.getType maps one/num, defaults to INSTANT")
	void reloadType_mapsKnownAliases() {
		assertSame(ReloadType.ONE, ReloadType.getType("one"));
		assertSame(ReloadType.NUM, ReloadType.getType("num"));
		assertSame(ReloadType.INSTANT, ReloadType.getType("instant"));
		assertSame(ReloadType.INSTANT, ReloadType.getType("anything-else"));
	}

	@Test
	@DisplayName("BZ-WM-03: two weapons with different num-N amounts don't collide on a shared enum field")
	void reloadType_numAmount_isPerWeapon_notSharedState() {
		// Two weapon files, both `Reload: {Type: num-N}` with different N — simulates
		// AmmunitionSectionParser.parse building each weapon's own ReloadData.amount, then Weapon's constructor
		// wiring reloadData.getType().createInstance(weapon, ammoType, reloadData.getAmount()) (Weapon.java).
		GunWeapon first  = gunWeaponWithNumAmount(3);
		GunWeapon second = gunWeaponWithNumAmount(7);

		Reload firstReload = first.getReloadData().getType().createInstance(
				first, first.getAmmunitionData().getAmmoType(), first.getReloadData().getAmount());
		Reload secondReload = second.getReloadData().getType().createInstance(
				second, second.getAmmunitionData().getAmmoType(), second.getReloadData().getAmount());

		// createInstance reads the amount off the explicit parameter — not off any state living on the shared
		// ReloadType.NUM enum constant, so building a second Reload with a different amount never mutates the
		// first one already handed to some other weapon.
		assertTrue(firstReload.toString().contains("amount=3"), firstReload.toString());
		assertTrue(secondReload.toString().contains("amount=7"), secondReload.toString());
	}

	private static GunWeapon gunWeaponWithNumAmount(int amount) {
		ProjectileData projectile = ProjectileData.builder()
				.speed(3.0).type(ProjectileType.BULLET).damage(5.0).consumed(1).perShot(1).cooldown(4)
				.distance(60).particle(false).gravity(0.0).build();
		Ammunition ammo = new Ammunition("test_ammo", "&7test_ammo", Material.COAL, 0, List.of());
		AmmunitionData ammoData = new AmmunitionData(ammo, 10, 1, 10);
		ReloadData reloadData = ReloadData.builder().cooldown(20).type(ReloadType.NUM).amount(amount).build();

		return new GunWeapon(UUID.randomUUID(), "test_gun", "&fTest Gun", WeaponType.GUN, Material.IRON_HOE, 0,
		                     (short) 100, List.of(), false, null, SelectiveFire.SINGLE, 0, projectile, reloadData,
		                     ammoData);
	}

}
