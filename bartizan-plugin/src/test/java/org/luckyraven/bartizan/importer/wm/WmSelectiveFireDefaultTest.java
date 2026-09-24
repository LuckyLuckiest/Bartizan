package org.luckyraven.bartizan.importer.wm;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * BZ-IM-03: {@code WmWeaponImporter#buildSelectiveFire}'s starting-mode fallback (no real WM config ever sets
 * {@code Selective_Fire.Default} - that key doesn't exist in WM; {@code Shoot.Selective_Fire}, when present, only
 * ever describes the mode-switch trigger). Before the fix, any full-auto-capable weapon with a {@code
 * Selective_Fire} block but no {@code Default} (e.g. the vendored {@code FN_FAL.yml}, whose own lore calls it
 * "Semi-automatic") always started in {@code auto} - the most aggressive possible fallback, applied to every
 * imported assault rifle/SMG regardless of the weapon's intended default.
 */
class WmSelectiveFireDefaultTest {

	@Test
	@DisplayName("FN_FAL: Selective_Fire block present, no Default -> starts single, not the most aggressive mode")
	void fnFal_hasSelectiveFireBlockButNoDefault_startsSingleNotAuto() throws Exception {
		Map<String, Object> yaml = importFixture("FN_FAL");

		Map<?, ?> shoot = (Map<?, ?>) yaml.get("Shoot");
		assertNotNull(shoot, "FN_FAL must import as a gun");
		assertEquals("single", shoot.get("Selective_Fire"),
		            "Selective_Fire has a Trigger/Mechanics block but no Default - WM's own implicit default "
		            + "(missing selective-fire NBT tag = SINGLE) must win, not the most aggressive available mode");
	}

	@Test
	@DisplayName("FR_5.56: Selective_Fire.Default: BURST is a live key and must still be honoured")
	void fr556_hasExplicitDefault_stillHonoursIt() throws Exception {
		Map<String, Object> yaml = importFixture("FR_5.56", "FR_5_56");

		Map<?, ?> shoot = (Map<?, ?>) yaml.get("Shoot");
		assertNotNull(shoot, "FR_5_56 must import as a gun");
		assertEquals("burst", shoot.get("Selective_Fire"),
		            "Selective_Fire.Default: \"BURST\" is a live key and must still be honoured");
	}

	private Map<String, Object> importFixture(String title) throws URISyntaxException {
		return importFixture(title, title);
	}

	private Map<String, Object> importFixture(String fileName, String title) throws URISyntaxException {
		File file = classpathFile("wm/weapons/assault_rifles/" + fileName + ".yml");
		YamlConfiguration doc  = YamlConfiguration.loadConfiguration(file);
		ConfigurationSection body = doc.getConfigurationSection(title);
		assertNotNull(body, title + " section missing from " + fileName + ".yml");

		WmImportReport report = new WmImportReport();
		WmWeaponImporter.ImportedWeapon imported =
				WmWeaponImporter.importWeapon(title, body, Map.of(), Map.of(), report.weapon(title));
		assertNotNull(imported, title + " did not import");
		return imported.yaml();
	}

	private File classpathFile(String resource) throws URISyntaxException {
		URL url = Objects.requireNonNull(WmSelectiveFireDefaultTest.class.getClassLoader().getResource(resource),
		                                 resource + " is not on the test classpath");
		return new File(url.toURI());
	}

}
