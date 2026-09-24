package org.luckyraven.bartizan.configuration.parser;

import org.bukkit.Material;
import org.bukkit.Particle;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.ammo.AmmunitionManager;
import org.luckyraven.bartizan.api.testsupport.BukkitRegistryFixture;
import org.luckyraven.bartizan.api.weapon.GunWeapon;
import org.luckyraven.bartizan.api.weapon.WeaponType;
import org.luckyraven.bartizan.api.weapon.dto.BouncyData;
import org.luckyraven.bartizan.api.weapon.dto.DamageData;
import org.luckyraven.bartizan.api.weapon.dto.ProjectileData;
import org.luckyraven.bartizan.api.weapon.dto.VisualData;
import org.luckyraven.keystone.persistence.config.ConfigDocument;
import org.luckyraven.keystone.persistence.config.ConfigParser;
import org.luckyraven.keystone.persistence.config.ConfigReport;
import org.luckyraven.keystone.persistence.config.NodeReader;
import org.luckyraven.keystone.persistence.config.Severity;

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
 * {@code Ignore_Teams} and {@code Knockback} — including their defaults when unset. Also covers the
 * {@code Shoot.Projectile} keys gate {@code HI} part b adds — {@code Visual}, {@code Bouncy}, {@code Drag},
 * {@code Extinguish_In_Water}, {@code Alive_Ticks} and {@code Trail}.
 */
@DisplayName("GunWeaponParser — Damage: keys (gate HF)")
class GunWeaponParserTest {

	private static final Path FIXTURE = Path.of("weapon.yml");

	private GunWeaponParser parser;
	private ConfigReport    report;

	@BeforeAll
	static void bootstrapBukkitRegistry() {
		BukkitRegistryFixture.install();
	}

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

	@Test
	@DisplayName("Visual absent -> ROCKET defaults to fireball, FLARE defaults to firework")
	void visualAbsent_defaultsByType() throws Exception {
		GunWeapon rocket = parse("""
				Shoot:
				   Selective_Fire: single
				   Projectile:
				      Type: ROCKET
				      Damage:
				         Base: 10
				""");
		assertEquals(VisualData.VisualType.FIREBALL, rocket.getProjectileData().getVisual().type());

		report = new ConfigReport();
		GunWeapon flare = parse("""
				Shoot:
				   Selective_Fire: single
				   Projectile:
				      Type: FLARE
				      Damage:
				         Base: 10
				""");
		assertEquals(VisualData.VisualType.FIREWORK, flare.getProjectileData().getVisual().type());
	}

	@Test
	@DisplayName("full Visual: block parses Type/Item/Custom_Model_Data/Block")
	void visualFullBlock_parsesEveryKey() throws Exception {
		GunWeapon gun = parse("""
				Shoot:
				   Selective_Fire: single
				   Projectile:
				      Type: ROCKET
				      Damage:
				         Base: 10
				      Visual:
				         Type: falling_block
				         Item: FEATHER
				         Custom_Model_Data: 7
				         Block: STONE
				""");

		VisualData visual = gun.getProjectileData().getVisual();
		assertEquals(VisualData.VisualType.FALLING_BLOCK, visual.type());
		assertEquals(Material.FEATHER, visual.item());
		assertEquals(7, visual.customModelData());
		assertEquals(Material.STONE, visual.block());
	}

	@Test
	@DisplayName("unknown Visual.Type is a WARNING and falls back to the type-based default")
	void visualUnknownType_warnsAndFallsBack() throws Exception {
		GunWeapon gun = parse("""
				Shoot:
				   Selective_Fire: single
				   Projectile:
				      Type: ROCKET
				      Damage:
				         Base: 10
				      Visual:
				         Type: not_a_real_type
				""");

		assertEquals(VisualData.VisualType.FIREBALL, gun.getProjectileData().getVisual().type());
		assertTrue(report.issues().stream().anyMatch(
				issue -> issue.severity() == Severity.WARNING
				         && issue.code().equals("projectile.unknown_visual_type")));
	}

	@Test
	@DisplayName("Bouncy: Default plus a per-material override resolved through BlockGroupResolver")
	void bouncy_defaultAndPerMaterial() throws Exception {
		GunWeapon gun = parse("""
				Shoot:
				   Selective_Fire: single
				   Projectile:
				      Damage:
				         Base: 10
				      Bouncy:
				         Default: 0.2
				         GLASS: 0.6
				""");

		BouncyData bouncy = gun.getProjectileData().getBouncy();
		assertEquals(0.2, bouncy.defaultMultiplier(), 1e-9);
		assertEquals(0.6, bouncy.multiplierFor(Material.GLASS), 1e-9);
		assertEquals(0.2, bouncy.multiplierFor(Material.STONE), 1e-9, "unlisted material falls back to Default");
	}

	@Test
	@DisplayName("Bouncy absent -> null (every block hit still terminates the projectile)")
	void bouncyAbsent_isNull() throws Exception {
		GunWeapon gun = parse("""
				Shoot:
				   Selective_Fire: single
				   Projectile:
				      Damage:
				         Base: 10
				""");

		assertNull(gun.getProjectileData().getBouncy());
	}

	@Test
	@DisplayName("Drag/Extinguish_In_Water/Alive_Ticks/Trail default to 0/false/0/null")
	void newScalarKeys_absent_allDefault() throws Exception {
		GunWeapon gun = parse("""
				Shoot:
				   Selective_Fire: single
				   Projectile:
				      Damage:
				         Base: 10
				""");

		ProjectileData pd = gun.getProjectileData();
		assertEquals(0.0, pd.getDrag(), 1e-9);
		assertFalse(pd.isExtinguishInWater());
		assertEquals(0, pd.getAliveTicks());
		assertNull(pd.getTrail());
	}

	@Test
	@DisplayName("Drag/Extinguish_In_Water/Alive_Ticks/Trail all parse when set")
	void newScalarKeys_allSet() throws Exception {
		GunWeapon gun = parse("""
				Shoot:
				   Selective_Fire: single
				   Projectile:
				      Damage:
				         Base: 10
				      Drag: 0.1
				      Extinguish_In_Water: true
				      Alive_Ticks: 150
				      Trail: FLAME
				""");

		ProjectileData pd = gun.getProjectileData();
		assertEquals(0.1, pd.getDrag(), 1e-9);
		assertTrue(pd.isExtinguishInWater());
		assertEquals(150, pd.getAliveTicks());
		assertEquals(Particle.FLAME, pd.getTrail());
	}

	@Test
	@DisplayName("unrecognised Trail particle is a WARNING and leaves the trail null")
	void trailUnknownParticle_warnsAndStaysNull() throws Exception {
		GunWeapon gun = parse("""
				Shoot:
				   Selective_Fire: single
				   Projectile:
				      Damage:
				         Base: 10
				      Trail: not_a_real_particle
				""");

		assertNull(gun.getProjectileData().getTrail());
		assertTrue(report.issues().stream().anyMatch(
				issue -> issue.severity() == Severity.WARNING && issue.code().equals("projectile.unknown_trail")));
	}

	@Test
	@DisplayName("BZ-CF-03: Consumed_Amount omitted defaults to 1, not 0 (an omitted key must not grant infinite ammo)")
	void consumedAmount_absent_defaultsToOne() throws Exception {
		GunWeapon gun = parse("""
				Shoot:
				   Selective_Fire: single
				   Projectile:
				      Damage:
				         Base: 10
				""");

		assertEquals(1, gun.getProjectileData().getConsumed());
	}

	@Test
	@DisplayName("BZ-CF-03: Consumed_Amount: 0 is clamped up to 1")
	void consumedAmount_zero_clampedToOne() throws Exception {
		GunWeapon gun = parse("""
				Shoot:
				   Selective_Fire: single
				   Projectile:
				      Consumed_Amount: 0
				      Damage:
				         Base: 10
				""");

		assertEquals(1, gun.getProjectileData().getConsumed());
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
