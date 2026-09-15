package org.luckyraven.bartizan.configuration.parser;

import org.bukkit.Material;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.ammo.AmmunitionManager;
import org.luckyraven.bartizan.api.weapon.GunWeapon;
import org.luckyraven.bartizan.api.weapon.WeaponType;
import org.luckyraven.bartizan.api.weapon.dto.DamageData;
import org.luckyraven.keystone.persistence.config.ConfigDocument;
import org.luckyraven.keystone.persistence.config.ConfigParser;
import org.luckyraven.keystone.persistence.config.ConfigReport;
import org.luckyraven.keystone.persistence.config.NodeReader;

import java.io.StringReader;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the new {@code Shoot.Projectile.Damage} keys gate {@code HF} adds — {@code Dropoff}, the hit-zone deltas
 * ({@code Body}/{@code Arms}/{@code Legs}/{@code Feet}/{@code Back}), {@code Armor_Damage}, {@code Owner_Immunity},
 * {@code Ignore_Teams} and {@code Knockback} — including their defaults when unset.
 */
@DisplayName("GunWeaponParser — Damage: keys (gate HF)")
class GunWeaponParserTest {

	private static final Path FIXTURE = Path.of("weapon.yml");

	private GunWeaponParser parser;
	private ConfigReport    report;

	@BeforeEach
	void setUp() {
		parser = new GunWeaponParser(new AmmunitionManager());
		report = new ConfigReport();
	}

	@Test
	@DisplayName("none of the new keys given -> all default (dropoff empty, deltas 0, knockback absent)")
	void newKeysAbsent_allDefault() throws Exception {
		GunWeapon gun = parse("""
				Shoot:
				   Selective_Fire: single
				   Projectile:
				      Damage:
				         Base: 10
				""");

		DamageData dd = gun.getDamageData();
		assertTrue(dd.getDropoff().isEmpty());
		assertEquals(0.0, dd.getBodyDamage(), 1e-9);
		assertEquals(0.0, dd.getArmsDamage(), 1e-9);
		assertEquals(0.0, dd.getLegsDamage(), 1e-9);
		assertEquals(0.0, dd.getFeetDamage(), 1e-9);
		assertEquals(0.0, dd.getBackDamage(), 1e-9);
		assertEquals(0, dd.getArmorDamage());
		assertFalse(dd.isOwnerImmunity());
		assertFalse(dd.isIgnoreTeams());
		assertNull(dd.getKnockback());
	}

	@Test
	@DisplayName("full Damage: block parses every new key, including Knockback: 0 as present-not-absent")
	void fullBlock_parsesEveryKey() throws Exception {
		GunWeapon gun = parse("""
				Shoot:
				   Selective_Fire: single
				   Projectile:
				      Damage:
				         Base: 50
				         Dropoff:
				            - "20 -5"
				            - "60 -15"
				         Body: -3
				         Arms: -8
				         Legs: -10
				         Feet: -12
				         Back: 10
				         Armor_Damage: 2
				         Owner_Immunity: true
				         Ignore_Teams: true
				         Knockback: 0
				""");

		DamageData dd = gun.getDamageData();
		assertEquals(2, dd.getDropoff().size());
		assertEquals(20.0, dd.getDropoff().get(0).distance(), 1e-9);
		assertEquals(-5.0, dd.getDropoff().get(0).delta(), 1e-9);
		assertEquals(-3.0, dd.getBodyDamage(), 1e-9);
		assertEquals(-8.0, dd.getArmsDamage(), 1e-9);
		assertEquals(-10.0, dd.getLegsDamage(), 1e-9);
		assertEquals(-12.0, dd.getFeetDamage(), 1e-9);
		assertEquals(10.0, dd.getBackDamage(), 1e-9);
		assertEquals(2, dd.getArmorDamage());
		assertTrue(dd.isOwnerImmunity());
		assertTrue(dd.isIgnoreTeams());
		assertEquals(0.0, dd.getKnockback(), 1e-9, "Knockback: 0 is present, not absent — must not be null");
	}

	@Test
	@DisplayName("a malformed Dropoff entry is skipped, not fatal")
	void malformedDropoffEntry_isSkipped() throws Exception {
		GunWeapon gun = parse("""
				Shoot:
				   Selective_Fire: single
				   Projectile:
				      Damage:
				         Base: 10
				         Dropoff:
				            - "20 -5"
				            - "not a number"
				""");

		assertEquals(1, gun.getDamageData().getDropoff().size());
	}

	private GunWeapon parse(String yaml) throws Exception {
		ConfigDocument doc  = new ConfigParser().parse(FIXTURE, new StringReader(yaml), report);
		NodeReader     root = NodeReader.of(doc.root(), report);
		NodeReader     shoot = NodeReader.of(root.get("Shoot").asMapping().orNull(), report);

		WeaponBaseData base = new WeaponBaseData("test_gun", "&bTest Gun", WeaponType.GUN, Material.STICK, 0,
		                                         (short) 100, List.of(), false, null);

		return parser.parse(root, shoot, report, base);
	}

}
