package org.luckyraven.bartizan.importer.wm;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BZ-IM-07: a {@code Command{}} mechanic with no explicit {@code console} key now defaults to {@code As: player}
 * (see {@link WmMechanicsTranslatorTest#command()}), and - like every other place this importer substitutes its
 * own default for something WM's own config left unset - the import report must say so.
 */
class WmCommandDefaultReportTest {

	private static final String TITLE = "Command_Default_Test";

	@Test
	void commandWithNoConsoleKey_isReportedAsApproximated() {
		String yaml = TITLE + ":\n"
		            + "  Shoot:\n"
		            + "    Projectile_Speed: 40\n"
		            + "    Mechanics:\n"
		            + "      - \"Command{command=say hi}\"\n";

		WmImportReport report = new WmImportReport();
		WmWeaponImporter.ImportedWeapon imported = importFromYaml(yaml, report);
		assertNotNull(imported, TITLE + " must import as a gun");

		String rendered = report.render();
		assertTrue(rendered.contains("no explicit console flag, defaulted Command's As to player"),
		          "an absent console key must be reported, not silently defaulted:\n" + rendered);
	}

	@Test
	void commandWithExplicitConsoleKey_isNotReported() {
		String yaml = TITLE + ":\n"
		            + "  Shoot:\n"
		            + "    Projectile_Speed: 40\n"
		            + "    Mechanics:\n"
		            + "      - \"Command{command=say hi, console=true}\"\n";

		WmImportReport report = new WmImportReport();
		WmWeaponImporter.ImportedWeapon imported = importFromYaml(yaml, report);
		assertNotNull(imported, TITLE + " must import as a gun");

		String rendered = report.render();
		assertTrue(!rendered.contains("defaulted Command's As to player"),
		          "an explicit console key is a live value, not a default - must not be reported:\n" + rendered);
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
