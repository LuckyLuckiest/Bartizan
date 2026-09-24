package org.luckyraven.bartizan.configuration;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
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
				// run into exactly one sound spec plus the shared muzzle-flash spec (gate HE part b review:
				// the flash must actually fire for every shipped gun) whenever the weapon configures a shot sound.
				SoundData sounds = weapon.getSoundData();
				if (sounds != null && (sounds.getShotDefault() != null || sounds.getShotCustom() != null)) {
					assertTrue(weapon.getEffects().has(EffectHook.ON_SHOOT),
					           weaponFile.getName() + " has a shoot sound but no lowered ON_SHOOT effect");
					assertEquals(2, weapon.getEffects().forHook(EffectHook.ON_SHOOT).size(),
					             weaponFile.getName() + " should lower to exactly one sound spec + muzzle flash");
				}
			});
		}
		assertAll("every bundled weapon YAML", perFileChecks);

		assertEquals(weaponFiles.size(), weaponAddon.getWeaponKeys().size(),
		             "one registered weapon expected per bundled weapon YAML, got: " + weaponAddon.getWeaponKeys());
	}

	/**
	 * Gate {@code HG} review finding 1: a non-ammo {@code config.required} ERROR (here: the whole {@code
	 * Durability} section absent) must not fail the load — {@code WeaponAddon.FATAL_AMMO_CODES} gates only the
	 * ammo codes.
	 */
	@Test
	@DisplayName("registerWeapon: a non-ammo config.required ERROR still registers the weapon")
	void registerWeapon_nonAmmoRequiredError_stillRegisters() throws Exception {
		JavaPlugin        plugin            = PluginMocks.plugin(tempDir);
		AmmunitionManager ammunitionManager = new AmmunitionManager();

		File weaponFile = writeWeaponFile("no_durability.yml", """
				Information:
				   Name: "&7No Durability&r"
				   Category: melee
				   Material: IRON_HOE

				Attack:
				   Damage: 5.0
				   Range: 2.5
				""");

		WeaponAddon  weaponAddon = new WeaponAddon(null);
		ConfigReport report     = weaponAddon.registerWeapon(ammunitionManager, new FileHandler(plugin, weaponFile));

		assertTrue(report.issues().stream().anyMatch(
				           issue -> issue.severity() == Severity.ERROR && issue.code().equals("config.required")),
		           "expected a config.required ERROR for the missing Durability section");
		assertNotNull(weaponAddon.getWeapon("no_durability"),
		              "a non-ammo config.required ERROR must not block registration");
	}

	/**
	 * Gate {@code HG} review finding 1: {@code ammo.unknown_type} stays fatal.
	 */
	@Test
	@DisplayName("registerWeapon: ammo.unknown_type still throws")
	void registerWeapon_unknownAmmoType_throws() throws Exception {
		JavaPlugin        plugin            = PluginMocks.plugin(tempDir);
		AmmunitionManager ammunitionManager = new AmmunitionManager();

		File weaponFile = writeWeaponFile("bad_ammo.yml", """
				Information:
				   Name: "&7Bad Ammo&r"
				   Category: melee
				   Material: IRON_HOE
				   Durability:
				      Base: 100

				Attack:
				   Damage: 5.0
				   Range: 2.5

				Ammunition:
				   Capacity: 6
				   Ammo_Type: "does_not_exist"
				""");

		WeaponAddon weaponAddon = new WeaponAddon(null);
		FileHandler handler     = new FileHandler(plugin, weaponFile);

		assertThrows(InvalidConfigurationException.class,
		             () -> weaponAddon.registerWeapon(ammunitionManager, handler));
	}

	/**
	 * BZ-EV-18: {@code Player#setCooldown(Material, ticks)} is a per-Material client overlay, not per-weapon — two
	 * weapons sharing a base Material (the shipped {@code scout.yml}/{@code arc_lance.yml} both {@code SPYGLASS})
	 * would blank each other's HUD overlay the moment both turn on {@code HUD.Reload_Item_Cooldown}.
	 */
	@Test
	@DisplayName("registerWeapon: two weapons sharing a Material with HUD.Reload_Item_Cooldown both true warns "
			+ "naming both files, but does not block either weapon's registration")
	void registerWeapon_reloadItemCooldownMaterialCollision_warnsButStillRegistersBoth() throws Exception {
		JavaPlugin        plugin            = PluginMocks.plugin(tempDir);
		AmmunitionManager ammunitionManager = new AmmunitionManager();
		WeaponAddon       weaponAddon       = new WeaponAddon(null);

		String yaml = """
				Information:
				   Name: "&7Test&r"
				   Category: melee
				   Material: SPYGLASS
				   Durability:
				      Base: 100

				Attack:
				   Damage: 5.0
				   Range: 2.5

				HUD:
				   Reload_Item_Cooldown: true
				""";
		File first  = writeWeaponFile("first_spyglass.yml", yaml);
		File second = writeWeaponFile("second_spyglass.yml", yaml);

		ConfigReport firstReport  = weaponAddon.registerWeapon(ammunitionManager, new FileHandler(plugin, first));
		ConfigReport secondReport = weaponAddon.registerWeapon(ammunitionManager, new FileHandler(plugin, second));

		assertFalse(firstReport.issues().stream().anyMatch(
				           issue -> issue.code().equals("hud.reload_item_cooldown_material_collision")),
		            "the first weapon to claim SPYGLASS is never the one warned about");
		assertTrue(secondReport.issues().stream().anyMatch(
				           issue -> issue.severity() == Severity.WARNING
				                    && issue.code().equals("hud.reload_item_cooldown_material_collision")
				                    && issue.message().contains("first_spyglass")),
		           "expected a WARNING on the second file naming the first:\n" +
		           secondReport.issues().stream().map(ConfigIssue::render).collect(Collectors.joining("\n")));

		assertNotNull(weaponAddon.getWeapon("first_spyglass"), "the collision must not block either weapon's load");
		assertNotNull(weaponAddon.getWeapon("second_spyglass"), "the collision must not block either weapon's load");
	}

	private File writeWeaponFile(String name, String yaml) throws IOException {
		File file = tempDir.resolve("weapon/" + name).toFile();
		Files.createDirectories(file.getParentFile().toPath());
		Files.writeString(file.toPath(), yaml);
		return file;
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
