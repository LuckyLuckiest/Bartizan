package org.luckyraven.bartizan.configuration;

import org.apache.logging.log4j.Level;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.luckyraven.bartizan.ammo.AmmunitionManager;
import org.luckyraven.bartizan.api.ammo.Ammunition;
import org.luckyraven.bartizan.api.testsupport.BukkitRegistryFixture;
import org.luckyraven.bartizan.support.LogCapture;
import org.luckyraven.keystone.persistence.FileHandler;
import org.luckyraven.keystone.persistence.FileManager;
import org.luckyraven.keystone.testkit.PluginMocks;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gate {@code BZ-CF-06}: an ammo entry's unresolvable {@code Material} silently fell back to
 * {@code XMaterial.IRON_PICKAXE} with no diagnostic at all - {@link AmmunitionAddon} has no {@link
 * org.luckyraven.keystone.persistence.config.ConfigReport} wiring (it still reads off Bukkit's
 * {@code ConfigurationSection} directly), so the fallback must be a {@code log.warn} instead.
 */
class AmmunitionAddonMaterialWarningTest {

	@TempDir
	Path tempDir;

	@BeforeAll
	static void bootstrapBukkitRegistry() {
		BukkitRegistryFixture.install();
	}

	@Test
	void unresolvableMaterialFallsBackToIronPickaxeWithAWarning() throws Exception {
		JavaPlugin  plugin      = PluginMocks.plugin(tempDir);
		FileManager fileManager = new FileManager(plugin);

		File ammoFile = tempDir.resolve("items/ammunition.yml").toFile();
		Files.createDirectories(ammoFile.getParentFile().toPath());
		Files.writeString(ammoFile.toPath(), """
				typo_ammo:
				   Material: "NOT_A_REAL_MATERIAL"
				   Name: "&7Typo Ammo&r"
				""");
		fileManager.addFile(new FileHandler(plugin, ammoFile), false);

		AmmunitionManager ammunitionManager = new AmmunitionManager();
		AmmunitionAddon   addon             = new AmmunitionAddon(fileManager, ammunitionManager, null);

		try (LogCapture capture = LogCapture.attach(AmmunitionAddon.class)) {
			addon.initialize();

			assertTrue(capture.any(Level.WARN, "typo_ammo"),
			           "an unresolvable ammo Material must be logged, not silently swallowed");
		}

		Ammunition ammo = ammunitionManager.getAmmunition("typo_ammo");
		assertNotNull(ammo, "the ammo must still register under its IRON_PICKAXE fallback");
		assertEquals(org.bukkit.Material.IRON_PICKAXE, ammo.getMaterial());
	}

}
