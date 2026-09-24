package org.luckyraven.bartizan.file;

import org.apache.logging.log4j.Level;
import org.bukkit.plugin.PluginDescriptionFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.luckyraven.bartizan.Bartizan;
import org.luckyraven.bartizan.ammo.AmmunitionManager;
import org.luckyraven.bartizan.configuration.WeaponAddon;
import org.luckyraven.bartizan.support.LogCapture;
import org.luckyraven.keystone.persistence.FileManager;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * BZ-CF-01: a weapon YAML that fails to parse must be reported above routine startup chatter, not at
 * {@code log.info} - every validation throw in the five weapon parsers (missing Selective_Fire, missing
 * Projectile, missing Damage, an unresolvable Display_Item.Material, ...) funnels into
 * {@link WeaponLoader}'s per-file catch, and it stayed indistinguishable from routine startup output.
 */
class WeaponLoaderTest {

	@TempDir
	Path tempDir;

	private Bartizan mockBartizan() {
		Bartizan bartizan = mock(Bartizan.class);
		when(bartizan.getDataFolder()).thenReturn(tempDir.toFile());
		when(bartizan.getDescription()).thenReturn(
				new PluginDescriptionFile("Bartizan", "0.5.1-TEST", "org.luckyraven.bartizan.Bartizan"));
		when(bartizan.getResource(anyString())).thenReturn(null);
		doNothing().when(bartizan).saveResource(anyString(), anyBoolean());
		return bartizan;
	}

	@Test
	void aWeaponThatFailsToParseIsLoggedAboveInfo() throws Exception {
		Bartizan          bartizan          = mockBartizan();
		FileManager       fileManager       = new FileManager(bartizan);
		WeaponAddon       weaponAddon       = new WeaponAddon(null);
		AmmunitionManager ammunitionManager = new AmmunitionManager();

		Files.createDirectories(tempDir.resolve("weapon"));
		// no "Information:" section at all - trips WeaponAddon.registerWeapon's explicit
		// InvalidConfigurationException, exactly the failure every validation throw in the five weapon parsers
		// funnels into.
		Files.writeString(tempDir.resolve("weapon/broken.yml"), "Some_Unrelated_Key: 1\n");

		WeaponLoader loader = new WeaponLoader(bartizan, fileManager, weaponAddon, ammunitionManager);

		try (LogCapture capture = LogCapture.attach(WeaponLoader.class)) {
			loader.initialize();

			assertTrue(capture.any(Level.ERROR, "There was a problem loading the weapon"),
			           "a weapon that silently drops out of the catalogue must be logged at ERROR");
			assertFalse(capture.any(Level.INFO, "There was a problem loading the weapon"),
			            "the parse failure must not be logged at INFO, indistinguishable from routine startup chatter");
		}
	}

}
