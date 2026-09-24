package org.luckyraven.bartizan.file;

import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.luckyraven.keystone.persistence.FileHandler;
import org.luckyraven.keystone.persistence.FileManager;
import org.luckyraven.keystone.testkit.PluginMocks;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * BZ-CF-08: {@code init()} ran {@code str(root, "Money_Symbol", "$").substring(0, 1)} - {@code str()}'s default
 * only substitutes for an ABSENT key, so an explicitly configured {@code Money_Symbol: ""} returned the empty
 * string and {@code substring(0, 1)} threw {@link StringIndexOutOfBoundsException}. This runs inside a FILE-phase
 * bean initializer, so the exception used to abort the whole bean-instantiation pipeline and the plugin failed to
 * enable rather than degrading one feature.
 */
class BartizanSettingsTest {

	@TempDir
	Path tempDir;

	@Test
	void emptyMoneySymbolFallsBackToTheDefaultInsteadOfThrowing() throws Exception {
		JavaPlugin  plugin      = PluginMocks.plugin(tempDir);
		FileManager fileManager = new FileManager(plugin);

		File settingsFile = tempDir.resolve("settings.yml").toFile();
		Files.writeString(settingsFile.toPath(), "Money_Symbol: \"\"\n");
		fileManager.addFile(new FileHandler(plugin, settingsFile), false);

		BartizanSettings settings = new BartizanSettings(fileManager);

		assertDoesNotThrow(settings::initialize,
		                    "an explicit empty Money_Symbol must not abort the FILE-phase bean pipeline");
		assertEquals("$", BartizanSettings.getMoneySymbol());
	}

}
