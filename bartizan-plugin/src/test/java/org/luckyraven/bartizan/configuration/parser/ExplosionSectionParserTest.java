package org.luckyraven.bartizan.configuration.parser;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.weapon.dto.ExplosionData;
import org.luckyraven.bartizan.api.weapon.dto.ExplosionData.Airstrike;
import org.luckyraven.bartizan.api.weapon.dto.ExplosionData.Cluster;
import org.luckyraven.bartizan.api.weapon.dto.ExplosionData.Detonation;
import org.luckyraven.bartizan.api.weapon.dto.ExplosionData.Exposure;
import org.luckyraven.bartizan.api.weapon.dto.ExplosionData.Shape;
import org.luckyraven.bartizan.api.weapon.dto.ExplosionData.Trigger;
import org.luckyraven.keystone.persistence.config.ConfigDocument;
import org.luckyraven.keystone.persistence.config.ConfigParser;
import org.luckyraven.keystone.persistence.config.ConfigReport;
import org.luckyraven.keystone.persistence.config.MappingNode;
import org.luckyraven.keystone.persistence.config.NodeReader;
import org.luckyraven.keystone.persistence.config.Severity;

import java.io.StringReader;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link ExplosionSectionParser} (gate {@code HI-a}): full-block parsing, legacy-key lowering for both
 * callers (guns and throwables have different legacy defaults), and a bad {@code Shape}/{@code Exposure} value
 * falling back with a warning rather than failing the load.
 */
@DisplayName("ExplosionSectionParser — full block, legacy lowering, bad enum values (gate HI-a)")
class ExplosionSectionParserTest {

	private static final Path FIXTURE = Path.of("weapon.yml");

	/** Mirrors GunWeaponParser's legacy-rocket default: explode immediately on any impact, no fuse. */
	private static final ExplosionSectionParser.LegacyDefaults GUN_LEGACY = new ExplosionSectionParser.LegacyDefaults(
			4.0, 50.0, 40, 5.0, true, false, Shape.SPHERE,
			new Detonation(Set.of(Trigger.BLOCK, Trigger.ENTITY), 0, 0));

	/**
	 * Mirrors ThrowableWeaponParser's legacy-grenade default: never on impact, the fuse alone drives it, flat
	 * (non-tapering) falloff, and a 2.0 knockback default (gate HI-a review fix — restores the deleted
	 * ThrowableAction#detonate's hardcoded thrower push).
	 */
	private static final ExplosionSectionParser.LegacyDefaults THROWABLE_LEGACY =
			new ExplosionSectionParser.LegacyDefaults(3.0, 6.0, 0, 2.0, false, false, Shape.FLAT,
					new Detonation(Set.of(), 0, 60));

	private ConfigReport report;

	@BeforeEach
	void setUp() {
		report = new ConfigReport();
	}

	@Test
	@DisplayName("absent Explosion: block -> every field comes from the caller's legacy defaults")
	void absentBlock_gun_lowersLegacyValues() {
		ExplosionData data = ExplosionSectionParser.parse(null, GUN_LEGACY, report);

		assertEquals(4.0, data.getRadius(), 1e-9);
		assertEquals(50.0, data.getDamage(), 1e-9);
		assertEquals(40, data.getFireTicks());
		assertEquals(Shape.SPHERE, data.getShape(), "guns default to sphere (linear falloff), the old rocket behaviour");
		assertEquals(Exposure.DISTANCE, data.getExposure());
		assertFalse(data.isBlockDamage());
		assertEquals(5.0, data.getKnockback(), 1e-9);
		assertTrue(data.isOwnerImmunity());
		assertFalse(data.isIgnoreTeams());
		assertNull(data.getCluster());
		assertNull(data.getAirstrike());
		assertEquals(Set.of(Trigger.BLOCK, Trigger.ENTITY), data.getDetonation().impactWhen());
		assertEquals(0, data.getDetonation().fuseTicks());
		assertFalse(report.hasErrors());
	}

	@Test
	@DisplayName("absent Explosion: block -> throwable legacy default never explodes on impact, fuse only")
	void absentBlock_throwable_lowersLegacyValues() {
		ExplosionData data = ExplosionSectionParser.parse(null, THROWABLE_LEGACY, report);

		assertEquals(3.0, data.getRadius(), 1e-9);
		assertEquals(6.0, data.getDamage(), 1e-9);
		assertEquals(Shape.FLAT, data.getShape(), "throwables default to flat (full damage in-radius), the old grenade behaviour");
		assertEquals(2.0, data.getKnockback(), 1e-9, "restores the deleted hardcoded thrower-push knockback");
		assertTrue(data.getDetonation().impactWhen().isEmpty());
		assertEquals(60, data.getDetonation().fuseTicks());
		assertEquals(0, data.getDetonation().delayAfterImpactTicks());
	}

	@Test
	@DisplayName("full block parses every key, including nested Cluster/Airstrike/Detonation")
	void fullBlock_parsesEveryKey() throws Exception {
		NodeReader explosion = parseExplosionBlock("""
				Explosion:
				   Radius: 6.0
				   Damage: 80.0
				   Fire_Ticks: 20
				   Shape: cube
				   Exposure: line_of_sight
				   Block_Damage: true
				   Knockback: 2.5
				   Owner_Immunity: true
				   Ignore_Teams: true
				   Cluster:
				      Count: 5
				      Speed: 1.5
				      Delay_Ticks: 2
				   Airstrike:
				      Count: 4
				      Height: 12.0
				      Radius: 5.0
				      Delay_Ticks: 30
				   Detonation:
				      Impact_When: [block, entity]
				      Delay_After_Impact: 8
				      Fuse_Ticks: 200
				""");

		ExplosionData data = ExplosionSectionParser.parse(explosion, GUN_LEGACY, report);

		assertEquals(6.0, data.getRadius(), 1e-9);
		assertEquals(80.0, data.getDamage(), 1e-9);
		assertEquals(20, data.getFireTicks());
		assertEquals(Shape.CUBE, data.getShape());
		assertEquals(Exposure.LINE_OF_SIGHT, data.getExposure());
		assertTrue(data.isBlockDamage());
		assertEquals(2.5, data.getKnockback(), 1e-9);
		assertTrue(data.isOwnerImmunity());
		assertTrue(data.isIgnoreTeams());

		Cluster cluster = data.getCluster();
		assertEquals(5, cluster.count());
		assertEquals(1.5, cluster.speed(), 1e-9);
		assertEquals(2, cluster.delayTicks());

		Airstrike airstrike = data.getAirstrike();
		assertEquals(4, airstrike.count());
		assertEquals(12.0, airstrike.height(), 1e-9);
		assertEquals(5.0, airstrike.radius(), 1e-9);
		assertEquals(30, airstrike.delayTicks());

		Detonation detonation = data.getDetonation();
		assertEquals(Set.of(Trigger.BLOCK, Trigger.ENTITY), detonation.impactWhen());
		assertEquals(8, detonation.delayAfterImpactTicks());
		assertEquals(200, detonation.fuseTicks());

		assertFalse(report.hasErrors());
	}

	@Test
	@DisplayName("a present but partial block still lowers the untouched keys from legacy defaults")
	void partialBlock_untouchedKeysFallBackToLegacy() throws Exception {
		NodeReader explosion = parseExplosionBlock("""
				Explosion:
				   Shape: cube
				""");

		ExplosionData data = ExplosionSectionParser.parse(explosion, GUN_LEGACY, report);

		assertEquals(Shape.CUBE, data.getShape());
		assertEquals(GUN_LEGACY.radius(), data.getRadius(), 1e-9);
		assertEquals(GUN_LEGACY.damage(), data.getDamage(), 1e-9);
		assertTrue(data.isOwnerImmunity());
	}

	@Test
	@DisplayName("bad Shape/Exposure value falls back to the default with a WARNING, not a fatal error")
	void badEnumValues_warnAndFallBack() throws Exception {
		NodeReader explosion = parseExplosionBlock("""
				Explosion:
				   Shape: triangle
				   Exposure: xray
				""");

		ExplosionData data = ExplosionSectionParser.parse(explosion, GUN_LEGACY, report);

		assertEquals(Shape.SPHERE, data.getShape());
		assertEquals(Exposure.DISTANCE, data.getExposure());
		assertFalse(report.hasErrors());
		assertTrue(report.issues().stream().anyMatch(
				issue -> issue.severity() == Severity.WARNING && issue.code().equals("explosion.unknown_shape")));
		assertTrue(report.issues().stream().anyMatch(
				issue -> issue.severity() == Severity.WARNING && issue.code().equals("explosion.unknown_exposure")));
	}

	@Test
	@DisplayName("an unknown Detonation.Impact_When entry warns and is skipped, valid entries still apply")
	void badTrigger_warnsAndSkipsOnlyThatEntry() throws Exception {
		NodeReader explosion = parseExplosionBlock("""
				Explosion:
				   Detonation:
				      Impact_When: [block, orbit]
				""");

		ExplosionData data = ExplosionSectionParser.parse(explosion, GUN_LEGACY, report);

		assertEquals(Set.of(Trigger.BLOCK), data.getDetonation().impactWhen());
		assertTrue(report.issues().stream().anyMatch(
				issue -> issue.severity() == Severity.WARNING && issue.code().equals("explosion.unknown_trigger")));
	}

	private NodeReader parseExplosionBlock(String yaml) throws Exception {
		ConfigDocument doc  = new ConfigParser().parse(FIXTURE, new StringReader(yaml), report);
		NodeReader     root = NodeReader.of(doc.root(), report);

		MappingNode explosionMapping = root.get("Explosion").asMapping().orNull();
		return explosionMapping != null ? NodeReader.of(explosionMapping, report) : null;
	}

}
