package org.luckyraven.bartizan.configuration.parser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.support.WeaponFixtures;
import org.luckyraven.bartizan.api.weapon.GunWeapon;
import org.luckyraven.bartizan.api.weapon.modifiers.action.PenetrationModifier;
import org.luckyraven.keystone.persistence.config.ConfigDocument;
import org.luckyraven.keystone.persistence.config.ConfigParser;
import org.luckyraven.keystone.persistence.config.ConfigReport;
import org.luckyraven.keystone.persistence.config.NodeReader;
import org.luckyraven.keystone.persistence.config.Severity;

import java.io.StringReader;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link ModifiersSectionParser}'s {@code Pierce_Entities} sugar (weapons-roadmap.md gate {@code HE} part
 * b): it fills an entities-only {@link PenetrationModifier} when no {@code Penetration} string is configured, and
 * defers to an explicit {@code Penetration} (with a warning) when both are given.
 */
@DisplayName("ModifiersSectionParser — Pierce_Entities sugar")
class ModifiersSectionParserTest {

	private static final Path FIXTURE = Path.of("weapon.yml");

	@Test
	@DisplayName("Pierce_Entities alone sets an entities-only PenetrationModifier")
	void pierceEntities_noPenetration_setsSugarPenetration() {
		ConfigReport report = new ConfigReport();
		NodeReader   root   = rootReaderFor(report, """
				Modifiers:
				   Pierce_Entities: 3
				""");
		GunWeapon weapon = WeaponFixtures.gunWeapon(30, 1);

		ModifiersSectionParser.apply(root, weapon, report);

		assertEquals(new PenetrationModifier(0, 3, 0.0), weapon.getModifiersData().getPenetration());
		assertTrue(report.issues().stream().noneMatch(issue -> issue.severity() == Severity.WARNING));
	}

	@Test
	@DisplayName("Penetration wins over Pierce_Entities when both are configured, with a warning")
	void bothConfigured_penetrationWinsWithWarning() {
		ConfigReport report = new ConfigReport();
		NodeReader   root   = rootReaderFor(report, """
				Modifiers:
				   Penetration: 2-3-0.25
				   Pierce_Entities: 9
				""");
		GunWeapon weapon = WeaponFixtures.gunWeapon(30, 1);

		ModifiersSectionParser.apply(root, weapon, report);

		assertEquals(new PenetrationModifier(2, 3, 0.25), weapon.getModifiersData().getPenetration());
		assertTrue(report.issues().stream().anyMatch(
				issue -> issue.severity() == Severity.WARNING
				         && issue.code().equals("modifiers.pierce_entities_and_penetration")));
	}

	@Test
	@DisplayName("neither key configured -> no PenetrationModifier, no warning")
	void neitherConfigured_noPenetration() {
		ConfigReport report = new ConfigReport();
		NodeReader   root   = rootReaderFor(report, """
				Modifiers:
				   Flat_Damage: 0.0
				""");
		GunWeapon weapon = WeaponFixtures.gunWeapon(30, 1);

		ModifiersSectionParser.apply(root, weapon, report);

		assertEquals(null, weapon.getModifiersData().getPenetration());
		assertTrue(report.issues().stream().noneMatch(issue -> issue.severity() == Severity.WARNING));
	}

	private NodeReader rootReaderFor(ConfigReport report, String yaml) {
		ConfigDocument doc = new ConfigParser().parse(FIXTURE, new StringReader(yaml), report);
		return NodeReader.of(doc.root(), report);
	}

}
