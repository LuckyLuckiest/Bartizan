package org.luckyraven.bartizan.configuration;

import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.luckyraven.bartizan.ammo.AmmunitionManager;
import org.luckyraven.bartizan.api.testsupport.BukkitRegistryFixture;
import org.luckyraven.keystone.persistence.FileHandler;
import org.luckyraven.keystone.persistence.FileManager;
import org.luckyraven.keystone.testkit.PluginMocks;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BZ-CF-12: {@code AmmunitionManager.ammunition} was never pruned on {@code /bartizan reload} -
 * {@code AmmunitionAddon} never overrode {@code FileInitializer.clear()}'s no-op default, so a removed
 * {@code items/ammunition.yml} entry stayed fully registered and givable after a reload. {@link AmmunitionAddon}
 * IS a registered {@code FileManager} initializer (see {@code FilesConfig.ammunitionAddon}), so
 * {@code FileManager.onClear()} reaches it on every reload pass.
 */
class AmmunitionAddonClearTest {

	@TempDir
	Path tempDir;

	@BeforeAll
	static void bootstrapBukkitRegistry() {
		BukkitRegistryFixture.install();
	}

	@Test
	void clearPrunesStaleAmmunitionFromTheManager() throws Exception {
		JavaPlugin  plugin      = PluginMocks.plugin(tempDir);
		FileManager fileManager = new FileManager(plugin);

		File ammoFile = tempDir.resolve("items/ammunition.yml").toFile();
		Files.createDirectories(ammoFile.getParentFile().toPath());
		Files.writeString(ammoFile.toPath(), """
				test_ammo:
				   Material: "IRON_INGOT"
				   Name: "&7Test Ammo&r"
				""");
		fileManager.addFile(new FileHandler(plugin, ammoFile), false);

		AmmunitionManager ammunitionManager = new AmmunitionManager();
		AmmunitionAddon   addon             = new AmmunitionAddon(fileManager, ammunitionManager, null);
		addon.initialize();
		assertFalse(ammunitionManager.getAmmunitionKeys().isEmpty(), "sanity: the ammo must have registered first");

		addon.clear();

		assertTrue(ammunitionManager.getAmmunitionKeys().isEmpty(),
		           "clear() must drop every previously-loaded ammo id so a reload can't keep serving one that was "
		           + "removed from items/ammunition.yml");
	}

}
