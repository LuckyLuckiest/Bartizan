package org.luckyraven.bartizan.importer.wm;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BZ-IM-06: {@code WmMechanicsTranslator}'s {@code "push","leap"} case copies both WM {@code speed} and {@code
 * height} args onto the single Bartizan {@code Strength} key - {@code PushHookEffect} has no separate vertical
 * component, so the second {@code copy()} call silently overwrites the first with no trace in the import report,
 * unlike every other lossy conversion this importer performs.
 */
class WmPushLeapCollisionReportTest {

	private static final String TITLE = "Push_Leap_Test";

	@Test
	void pushMechanicWithBothSpeedAndHeight_isReportedAsApproximated() {
		String yaml = TITLE + ":\n"
		            + "  Shoot:\n"
		            + "    Projectile_Speed: 40\n"
		            + "    Mechanics:\n"
		            + "      - \"Push{speed=1.0, height=2.0}\"\n";

		WmImportReport report = new WmImportReport();
		WmWeaponImporter.ImportedWeapon imported = importFromYaml(yaml, report);
		assertNotNull(imported, TITLE + " must import as a gun");

		String rendered = report.render();
		assertTrue(rendered.contains("Push/Leap has both speed and height"),
		          "a Push{} mechanic carrying both speed and height must be reported as approximated, since only "
		          + "one survives onto Strength:\n" + rendered);
	}

	@Test
	void pushMechanicWithOnlySpeed_isNotReportedAsACollision() {
		String yaml = TITLE + ":\n"
		            + "  Shoot:\n"
		            + "    Projectile_Speed: 40\n"
		            + "    Mechanics:\n"
		            + "      - \"Push{speed=1.0}\"\n";

		WmImportReport report = new WmImportReport();
		WmWeaponImporter.ImportedWeapon imported = importFromYaml(yaml, report);
		assertNotNull(imported, TITLE + " must import as a gun");

		String rendered = report.render();
		assertTrue(!rendered.contains("Push/Leap has both speed and height"),
		          "a Push{} mechanic with only one of speed/height loses nothing and must not be flagged:\n"
		          + rendered);
	}

	private WmWeaponImporter.ImportedWeapon importFromYaml(String yaml, WmImportReport report) {
		YamlConfiguration doc = new YamlConfiguration();
		try {
			doc.loadFromString(yaml);
		} catch (Exception exception) {
			throw new RuntimeException(exception);
		}
		ConfigurationSection body = doc.getConfigurationSection(TITLE);
		assertNotNull(body, "fixture YAML must parse");

		return WmWeaponImporter.importWeapon(TITLE, body, Map.of(), Map.of(), report.weapon(TITLE));
	}

}
