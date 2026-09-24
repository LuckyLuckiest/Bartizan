package org.luckyraven.bartizan.configuration;

import com.cryptomorin.xseries.XMaterial;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import org.luckyraven.bartizan.ammo.AmmunitionManager;
import org.luckyraven.bartizan.api.ammo.Ammunition;
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

	/**
	 * Gate {@code BZ-CF-02}: {@code registerWeapon} used to return immediately once a {@code Config_Version} key
	 * was present - before any section was parsed, before the {@link ConfigReport} was logged, and before the
	 * weapon was put into the catalogue map. An ordinary versioning habit for a config author therefore made the
	 * weapon disappear with zero output anywhere.
	 */
	@Test
	@DisplayName("registerWeapon: a Config_Version key warns and falls through instead of aborting the load")
	void registerWeapon_configVersionKeyPresent_stillRegisters() throws Exception {
		JavaPlugin        plugin            = PluginMocks.plugin(tempDir);
		AmmunitionManager ammunitionManager = new AmmunitionManager();

		// Matches PluginMocks' default plugin version exactly: Keystone's own FileHandler has an unrelated
		// Config_Version convention (regenerate-on-mismatch for upgrade migrations) that would otherwise move this
		// fixture aside as *-old.yml before WeaponAddon ever sees it - a real value is fine here since
		// WeaponAddon.registerWeapon's own Config_Version handling (under test) only checks presence, not content.
		File weaponFile = writeWeaponFile("versioned.yml", """
				Config_Version: "0.0.1-TEST"

				Information:
				   Name: "&7Versioned&r"
				   Category: melee
				   Material: IRON_HOE
				   Durability:
				      Base: 100

				Attack:
				   Damage: 5.0
				   Range: 2.5
				""");

		WeaponAddon  weaponAddon = new WeaponAddon(null);
		ConfigReport report     = weaponAddon.registerWeapon(ammunitionManager, new FileHandler(plugin, weaponFile));

		assertNotNull(weaponAddon.getWeapon("versioned"),
		              "a Config_Version key must not make the whole file disappear from the catalogue");
		assertTrue(report.issues().stream().anyMatch(
				           issue -> issue.severity() == Severity.WARNING &&
				                    issue.code().equals("weapon.config_version_unsupported")),
		           "expected a WARNING recorded for the unimplemented Config_Version key");
	}

	/**
	 * Gate {@code BZ-CF-09}: {@code Durability.Base: 0} used to be accepted and stored as {@code durability = 0}.
	 * Two independent consumers divide by {@code weapon.getDurability()} with no guard ({@code Weapon.buildItem()}
	 * and {@code DurabilityCalculator.getWeaponDurability}), producing NaN/Infinity that a narrowing cast silently
	 * truncates to 0 instead of surfacing the misconfiguration.
	 */
	@Test
	@DisplayName("registerWeapon: Durability.Base of 0 is rejected instead of reaching the weapon as a zero denominator")
	void registerWeapon_durabilityBaseZero_neverProducesZeroDurability() throws Exception {
		JavaPlugin        plugin            = PluginMocks.plugin(tempDir);
		AmmunitionManager ammunitionManager = new AmmunitionManager();

		File weaponFile = writeWeaponFile("zero_durability.yml", """
				Information:
				   Name: "&7Zero Durability&r"
				   Category: melee
				   Material: IRON_HOE
				   Durability:
				      Base: 0

				Attack:
				   Damage: 5.0
				   Range: 2.5
				""");

		WeaponAddon  weaponAddon = new WeaponAddon(null);
		ConfigReport report     = weaponAddon.registerWeapon(ammunitionManager, new FileHandler(plugin, weaponFile));

		Weapon weapon = weaponAddon.getWeapon("zero_durability");
		assertNotNull(weapon, "a non-fatal Durability.Base range violation must not block registration");
		assertTrue(weapon.getDurability() >= 1,
		           "Durability.Base of 0 must never reach the weapon as 0 - the durability scale divides by it");
		assertTrue(report.issues().stream().anyMatch(
				           issue -> issue.severity() == Severity.ERROR && issue.code().equals("config.range")),
		           "expected a config.range ERROR for Durability.Base below the minimum");
	}

	/**
	 * Gate {@code BZ-CF-11}: a missing {@code Information.Category} used to reach
	 * {@code Objects.requireNonNull(categoryString)} and throw a bare, message-less {@link NullPointerException}
	 * that escapes {@code WeaponLoader}'s {@code catch (InvalidConfigurationException)} entirely - a materially
	 * worse diagnostic than every other required-field violation in this method produces.
	 */
	@Test
	@DisplayName("registerWeapon: missing Information.Category throws a clean InvalidConfigurationException, not an NPE")
	void registerWeapon_missingCategory_throwsCleanException() throws Exception {
		JavaPlugin        plugin            = PluginMocks.plugin(tempDir);
		AmmunitionManager ammunitionManager = new AmmunitionManager();

		File weaponFile = writeWeaponFile("no_category.yml", """
				Information:
				   Name: "&7No Category&r"
				   Material: IRON_HOE
				   Durability:
				      Base: 100

				Attack:
				   Damage: 5.0
				   Range: 2.5
				""");

		WeaponAddon weaponAddon = new WeaponAddon(null);
		FileHandler handler     = new FileHandler(plugin, weaponFile);

		InvalidConfigurationException exception = assertThrows(InvalidConfigurationException.class,
				() -> weaponAddon.registerWeapon(ammunitionManager, handler));
		assertTrue(exception.getMessage().contains("Category"),
		           "exception message should mention the missing key: " + exception.getMessage());
	}

	/**
	 * Gate {@code BZ-CF-11}: the identical {@code Objects.requireNonNull} trap two lines later for
	 * {@code Information.Material}.
	 */
	@Test
	@DisplayName("registerWeapon: missing Information.Material throws a clean InvalidConfigurationException, not an NPE")
	void registerWeapon_missingMaterial_throwsCleanException() throws Exception {
		JavaPlugin        plugin            = PluginMocks.plugin(tempDir);
		AmmunitionManager ammunitionManager = new AmmunitionManager();

		File weaponFile = writeWeaponFile("no_material.yml", """
				Information:
				   Name: "&7No Material&r"
				   Category: melee
				   Durability:
				      Base: 100

				Attack:
				   Damage: 5.0
				   Range: 2.5
				""");

		WeaponAddon weaponAddon = new WeaponAddon(null);
		FileHandler handler     = new FileHandler(plugin, weaponFile);

		InvalidConfigurationException exception = assertThrows(InvalidConfigurationException.class,
				() -> weaponAddon.registerWeapon(ammunitionManager, handler));
		assertTrue(exception.getMessage().contains("Material"),
		           "exception message should mention the missing key: " + exception.getMessage());
	}

	/**
	 * Gate {@code BZ-CF-06}: an unrecognised {@code Category} silently resolved through
	 * {@code WeaponType.getType}'s {@code default -> OTHER} (dispatching like a GUN), and an unresolvable
	 * {@code Material} silently fell back to {@code FEATHER}, with no diagnostic at all.
	 */
	@Test
	@DisplayName("registerWeapon: unrecognised Category/Material fall back with a WARNING instead of silently")
	void registerWeapon_unknownCategoryAndMaterial_warnsAndFallsBack() throws Exception {
		JavaPlugin        plugin            = PluginMocks.plugin(tempDir);
		AmmunitionManager ammunitionManager = new AmmunitionManager();
		ammunitionManager.register("test_ammo",
				new Ammunition("test_ammo", "&7Test Ammo&r", XMaterial.IRON_INGOT.get(), 0, List.of()));

		File weaponFile = writeWeaponFile("typo_category.yml", """
				Information:
				   Name: "&7Typo&r"
				   Category: "gunn"
				   Material: "NOT_A_REAL_MATERIAL"
				   Durability:
				      Base: 100

				Shoot:
				   Selective_Fire: single
				   Projectile:
				      Speed: 20
				      Type: BULLET
				      Damage:
				         Base: 5

				Ammunition:
				   Ammo_Type: "test_ammo"
				""");

		WeaponAddon  weaponAddon = new WeaponAddon(null);
		ConfigReport report     = weaponAddon.registerWeapon(ammunitionManager, new FileHandler(plugin, weaponFile));

		assertNotNull(weaponAddon.getWeapon("typo_category"),
		              "an unrecognised Category/Material must still register the weapon under its OTHER/FEATHER "
		              + "fallback");
		assertTrue(report.issues().stream().anyMatch(
				           issue -> issue.severity() == Severity.WARNING &&
				                    issue.code().equals("weapon.unknown_category")),
		           "expected a WARNING for the unrecognised Category");
		assertTrue(report.issues().stream().anyMatch(
				           issue -> issue.severity() == Severity.WARNING &&
				                    issue.code().equals("weapon.unknown_material")),
		           "expected a WARNING for the unresolvable Material");
	}

	/**
	 * BZ-CF-14: {@code applyOptionalShootConfig}'s own {@code Selective_Fire} read (independent of each type
	 * parser's {@code SelectiveFireSectionParser} call) used to silently resolve an unrecognised value to AUTO via
	 * {@code SelectiveFire.getType}'s default branch, with no {@link ConfigReport} entry anywhere.
	 */
	@Test
	@DisplayName("registerWeapon: an unrecognised Shoot.Selective_Fire warns instead of silently becoming AUTO")
	void registerWeapon_unknownSelectiveFire_warns() throws Exception {
		JavaPlugin        plugin            = PluginMocks.plugin(tempDir);
		AmmunitionManager ammunitionManager = new AmmunitionManager();

		File weaponFile = writeWeaponFile("bad_selective_fire.yml", """
				Information:
				   Name: "&7Bad Selective Fire&r"
				   Category: gun
				   Material: IRON_HOE
				   Durability:
				      Base: 100

				Shoot:
				   Selective_Fire: sinlge
				   Weapon_Consumed:
				      Consume_On_Shot: 0
				   Projectile:
				      Damage:
				         Base: 10
				""");

		WeaponAddon  weaponAddon = new WeaponAddon(null);
		ConfigReport report      = weaponAddon.registerWeapon(ammunitionManager, new FileHandler(plugin, weaponFile));

		assertTrue(report.issues().stream().anyMatch(
				           issue -> issue.severity() == Severity.WARNING
				                    && issue.code().equals("selectiveFire.unknown_mode")),
		           "expected a selectiveFire.unknown_mode WARNING for 'sinlge'");

		Weapon weapon = weaponAddon.getWeapon("bad_selective_fire");
		assertNotNull(weapon);
		assertEquals(org.luckyraven.bartizan.api.weapon.SelectiveFire.AUTO, weapon.getCurrentSelectiveFire());
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
