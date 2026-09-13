package org.luckyraven.bartizan.configuration;

import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import org.luckyraven.bartizan.ammo.AmmunitionManager;
import org.luckyraven.bartizan.api.testsupport.BukkitRegistryFixture;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.api.weapon.dto.EffectHook;
import org.luckyraven.bartizan.api.weapon.dto.SoundData;
import org.luckyraven.keystone.persistence.FileHandler;
import org.luckyraven.keystone.persistence.FileManager;
import org.luckyraven.keystone.persistence.config.ConfigReport;
import org.luckyraven.keystone.persistence.config.ConfigIssue;
import org.luckyraven.keystone.persistence.config.Severity;
import org.luckyraven.keystone.testkit.PluginMocks;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression net for roadmap §0.1 item 4: loads every YAML bundled under {@code src/main/resources/weapon/}
 * through the real {@link WeaponAddon#registerWeapon(AmmunitionManager, FileHandler)} and asserts zero
 * {@link ConfigReport} errors, plus one registered weapon per file. Ammunition is loaded the same way
 * {@link AmmunitionAddon} does in production so the {@code Ammo_Type} references in the gun/incendiary/biological
 * weapon files resolve against the real catalogue instead of an empty stand-in.
 *
 * <p>Every YAML is copied out of the classpath into {@code @TempDir} first: {@link FileHandler}'s single-file
 * constructor reads straight off disk (no plugin-jar resource extraction involved), which is the smallest way to
 * hand it a real file without dragging in {@link org.luckyraven.bartizan.file.WeaponLoader}'s
 * {@code FolderLoader} machinery.
 */
class WeaponAddonTest {

	@TempDir
	Path tempDir;

	@BeforeAll
	static void bootstrapBukkitRegistry() {
		BukkitRegistryFixture.install();
	}

	@Test
	void everyBundledWeaponYamlLoadsWithoutConfigErrors() throws Exception {
		JavaPlugin  plugin      = PluginMocks.plugin(tempDir);
		FileManager fileManager = new FileManager(plugin);

		AmmunitionManager ammunitionManager = loadAmmunition(plugin, fileManager);

		List<File> weaponFiles = copyWeaponResourcesToTempDir();
		assertFalse(weaponFiles.isEmpty(), "no weapon YAML files found on the classpath under weapon/");

		WeaponAddon weaponAddon = new WeaponAddon(null);

		List<Executable> perFileChecks = new ArrayList<>();
		for (File weaponFile : weaponFiles) {
			perFileChecks.add(() -> {
				FileHandler  handler = new FileHandler(plugin, weaponFile);
				ConfigReport report  = weaponAddon.registerWeapon(ammunitionManager, handler);

				String issues = report.issues().stream()
						.map(ConfigIssue::render)
						.collect(Collectors.joining("\n"));
				assertFalse(report.hasErrors(),
				            weaponFile.getName() + " produced ConfigReport errors:\n" + issues);

				// gate HA follow-up item H: EffectsSectionParser problems are WARNING severity, so hasErrors()
				// alone can't catch them.
				boolean anyEffectsWarning = report.issues().stream().anyMatch(
						issue -> issue.severity() == Severity.WARNING && issue.code().startsWith("effects."));
				assertFalse(anyEffectsWarning, weaponFile.getName() + " produced effects.* warnings:\n" + issues);

				String key    = weaponFile.getName().replaceFirst("\\.yml$", "").toLowerCase();
				Weapon weapon = weaponAddon.getWeapon(key);
				assertNotNull(weapon, "no weapon registered for " + weaponFile.getName());

				// the legacy Shoot.Sound.* lowering (EffectsSectionParser.lowerLegacySounds) must have actually
				// run into exactly one ON_SHOOT spec whenever the weapon configures a shot sound.
				SoundData sounds = weapon.getSoundData();
				if (sounds != null && (sounds.getShotDefault() != null || sounds.getShotCustom() != null)) {
					assertTrue(weapon.getEffects().has(EffectHook.ON_SHOOT),
					           weaponFile.getName() + " has a shoot sound but no lowered ON_SHOOT effect");
					assertEquals(1, weapon.getEffects().forHook(EffectHook.ON_SHOOT).size(),
					             weaponFile.getName() + " should lower to exactly one ON_SHOOT spec");
				}
			});
		}
		assertAll("every bundled weapon YAML", perFileChecks);

		assertEquals(weaponFiles.size(), weaponAddon.getWeaponKeys().size(),
		             "one registered weapon expected per bundled weapon YAML, got: " + weaponAddon.getWeaponKeys());
	}

	/**
	 * Mirrors {@code FilesConfig.ammunitionAddon}: registers the bundled {@code items/ammunition.yml} into the
	 * {@link FileManager} and runs {@link AmmunitionAddon} for real so the returned {@link AmmunitionManager} is
	 * populated exactly like it is at plugin startup.
	 */
	private AmmunitionManager loadAmmunition(JavaPlugin plugin, FileManager fileManager) throws IOException {
		File ammoFile = copyResourceToTempDir("items/ammunition.yml");
		fileManager.addFile(new FileHandler(plugin, ammoFile), false);

		AmmunitionManager ammunitionManager = new AmmunitionManager();
		AmmunitionAddon   addon             = new AmmunitionAddon(fileManager, ammunitionManager, null);
		addon.initialize();
		return ammunitionManager;
	}

	private List<File> copyWeaponResourcesToTempDir() throws IOException, URISyntaxException {
		URL weaponDirUrl = WeaponAddonTest.class.getClassLoader().getResource("weapon");
		Objects.requireNonNull(weaponDirUrl, "weapon/ is not on the test classpath");
		File[] bundled = new File(weaponDirUrl.toURI())
				.listFiles((dir, name) -> name.endsWith(".yml"));
		Objects.requireNonNull(bundled, "weapon/ resolved to a non-directory: " + weaponDirUrl);

		List<File> copies = new ArrayList<>();
		for (File source : bundled) {
			copies.add(copyResourceToTempDir("weapon/" + source.getName()));
		}
		return copies;
	}

	private File copyResourceToTempDir(String resourcePath) throws IOException {
		File destination = tempDir.resolve(resourcePath).toFile();
		Files.createDirectories(destination.getParentFile().toPath());

		try (InputStream in = WeaponAddonTest.class.getClassLoader().getResourceAsStream(resourcePath)) {
			Objects.requireNonNull(in, resourcePath + " is not on the test classpath");
			Files.copy(in, destination.toPath());
		}
		return destination;
	}

}
