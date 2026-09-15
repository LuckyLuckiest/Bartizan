package org.luckyraven.bartizan.configuration.parser;

import org.bukkit.Material;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.ammo.AmmunitionManager;
import org.luckyraven.bartizan.api.weapon.ThrowableWeapon;
import org.luckyraven.bartizan.api.weapon.WeaponType;
import org.luckyraven.bartizan.api.weapon.dto.ThrowableData;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the {@code Owner_Immunity}/{@code Ignore_Teams}/{@code Knockback} keys gate {@code HF} adds directly under
 * a throwable's {@code Throw:} section (no nested {@code Damage:} block, unlike guns) — including that the
 * positional {@link ThrowableData} constructor still lines the new fields up correctly.
 */
@DisplayName("ThrowableWeaponParser — Owner_Immunity/Ignore_Teams/Knockback (gate HF)")
class ThrowableWeaponParserTest {

	private static final Path FIXTURE = Path.of("weapon.yml");

	private ThrowableWeaponParser parser;
	private ConfigReport          report;

	@BeforeEach
	void setUp() {
		parser = new ThrowableWeaponParser(new AmmunitionManager());
		report = new ConfigReport();
	}

	@Test
	@DisplayName("absent -> Owner_Immunity/Ignore_Teams false, Knockback 0 (grenades keep self-damaging)")
	void absent_defaultsPreserveExistingSelfDamage() throws Exception {
		ThrowableData data = parse("""
				Throw:
				   Type: EXPLOSIVE
				""").getThrowableData();

		assertFalse(data.isOwnerImmunity());
		assertFalse(data.isIgnoreTeams());
		assertEquals(0.0, data.getKnockback(), 1e-9);
	}

	@Test
	@DisplayName("all three keys set -> parsed onto ThrowableData")
	void allSet_parsedCorrectly() throws Exception {
		ThrowableData data = parse("""
				Throw:
				   Type: EXPLOSIVE
				   Owner_Immunity: true
				   Ignore_Teams: true
				   Knockback: 1.5
				""").getThrowableData();

		assertTrue(data.isOwnerImmunity());
		assertTrue(data.isIgnoreTeams());
		assertEquals(1.5, data.getKnockback(), 1e-9);

		// Fields parsed before the new three still line up (positional constructor sanity check).
		assertEquals(60, data.getFuseTime());
		assertEquals(3.0, data.getExplosionRadius(), 1e-9);
		assertEquals(6, data.getExplosionDamage());
	}

	@Test
	@DisplayName("gate HI-a: Entity_Type is a dead key — parses without error but warns")
	void entityType_isDeadKey_warnsButDoesNotFail() throws Exception {
		parse("""
				Throw:
				   Type: EXPLOSIVE
				   Entity_Type: SNOWBALL
				""");

		assertFalse(report.hasErrors());
		assertTrue(report.issues().stream().anyMatch(
				issue -> issue.severity() == Severity.WARNING && issue.code().equals("throw.entity_type_ignored")));
	}

	@Test
	@DisplayName("gate HI-a: absent Entity_Type produces no dead-key warning")
	void entityType_absent_noWarning() throws Exception {
		parse("""
				Throw:
				   Type: EXPLOSIVE
				""");

		assertFalse(report.issues().stream().anyMatch(issue -> issue.code().equals("throw.entity_type_ignored")));
	}

	private ThrowableWeapon parse(String yaml) throws Exception {
		ConfigDocument doc   = new ConfigParser().parse(FIXTURE, new StringReader(yaml), report);
		NodeReader     root  = NodeReader.of(doc.root(), report);
		NodeReader     shoot = NodeReader.of(root.get("Throw").asMapping().orNull(), report);

		WeaponBaseData base = new WeaponBaseData("test_grenade", "&aTest Grenade", WeaponType.THROWABLE,
		                                         Material.STICK, 0, (short) 1, List.of(), false, null);

		return parser.parse(root, shoot, report, base);
	}

}
