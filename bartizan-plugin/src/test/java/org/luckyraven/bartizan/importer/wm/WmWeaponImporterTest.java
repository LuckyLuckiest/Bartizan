package org.luckyraven.bartizan.importer.wm;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.luckyraven.bartizan.ammo.AmmunitionManager;
import org.luckyraven.bartizan.api.testsupport.BukkitRegistryFixture;
import org.luckyraven.bartizan.configuration.AmmunitionAddon;
import org.luckyraven.bartizan.configuration.WeaponAddon;
import org.luckyraven.keystone.persistence.FileHandler;
import org.luckyraven.keystone.persistence.FileManager;
import org.luckyraven.keystone.persistence.config.ConfigIssue;
import org.luckyraven.keystone.persistence.config.ConfigReport;
import org.luckyraven.keystone.persistence.config.Severity;
import org.luckyraven.keystone.testkit.PluginMocks;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden test (weapons-roadmap.md gate {@code HM}, §6.5): imports every WeaponMechanics default weapon vendored
 * under {@code src/test/resources/wm/} through {@link WmWeaponImporter}, asserts the {@link WmImportReport} carries
 * no errors, then loads every generated file through the real {@link WeaponAddon#registerWeapon} (the same
 * bootstrap {@code WeaponAddonTest} uses) and asserts zero {@link ConfigReport} {@link Severity#ERROR}s - warnings
 * are allowed and printed so a reviewer can see which keys are still pending a not-yet-merged gate.
 */
class WmWeaponImporterTest {

	@TempDir
	Path tempDir;

	@BeforeAll
	static void bootstrapBukkitRegistry() {
		BukkitRegistryFixture.install();
	}

	@Test
	void everyWmDefaultImportsWithNoReportErrorsAndLoadsWithNoConfigErrors() throws Exception {
		File wmRoot = classpathDirectory("wm");

		Map<String, ConfigurationSection> projectiles = loadRefs(new File(wmRoot, "projectiles"));
		Map<String, ConfigurationSection> ammos       = loadRefs(new File(wmRoot, "ammos"));
		assertFalse(projectiles.isEmpty(), "no projectile refs loaded from " + wmRoot);

		List<File> weaponFiles = new ArrayList<>();
		collectYamlFiles(new File(wmRoot, "weapons"), weaponFiles);
		assertEquals(24, weaponFiles.size(), "expected all 24 vendored WM default weapon files");

		WmImportReport report = new WmImportReport();
		File weaponOutDir = tempDir.resolve("weapon").toFile();
		Files.createDirectories(weaponOutDir.toPath());

		List<WmWeaponImporter.AmmoAppend> ammoAppends = new ArrayList<>();
		List<File> generatedFiles = new ArrayList<>();
		List<String> skippedTitles = new ArrayList<>();

		for (File weaponFile : weaponFiles) {
			YamlConfiguration doc = YamlConfiguration.loadConfiguration(weaponFile);
			for (String title : doc.getKeys(false)) {
				ConfigurationSection body = doc.getConfigurationSection(title);
				if (body == null) continue;

				WmImportReport.WeaponEntry entry = report.weapon(title);
				WmWeaponImporter.ImportedWeapon imported =
						WmWeaponImporter.importWeapon(title, body, projectiles, ammos, entry);

				if (imported == null) {
					skippedTitles.add(title);
					continue;
				}

				assertSpreadWeaponsHaveStartingSpread(title, imported.yaml());

				String rendered = WmYamlEmitter.emit(imported.yaml());
				assertFalse(rendered.contains("%reload_state%"),
				            title + ": generated YAML still carries the stale %reload_state% placeholder - "
				            + "WeaponPlaceholders only resolves %firearm_state%");

				File outFile = new File(weaponOutDir, imported.fileKey() + ".yml");
				Files.writeString(outFile.toPath(), rendered);
				generatedFiles.add(outFile);

				if (imported.ammoAppend() != null) ammoAppends.add(imported.ammoAppend());
			}
		}

		assertEquals(List.of("Stim"), skippedTitles,
		             "only WM's consumable-shaped Stim has no supported category - if this list grew or shrank, "
		             + "the category detection in WmWeaponImporter needs a look");

		String reportText = report.render();
		assertFalse(report.hasErrors(), "import report has errors:\n" + reportText);
		System.out.println(reportText);

		JavaPlugin  plugin      = PluginMocks.plugin(tempDir);
		FileManager fileManager = new FileManager(plugin);

		File ammoFile = copyResourceToTempDir("items/ammunition.yml");
		appendAmmoEntries(ammoFile, ammoAppends);
		fileManager.addFile(new FileHandler(plugin, ammoFile), false);

		AmmunitionManager ammunitionManager = new AmmunitionManager();
		new AmmunitionAddon(fileManager, ammunitionManager, null).initialize();

		WeaponAddon weaponAddon = new WeaponAddon(null);

		List<String> allWarnings = new ArrayList<>();
		List<String> allErrors   = new ArrayList<>();

		for (File generated : generatedFiles) {
			FileHandler  handler = new FileHandler(plugin, generated);
			ConfigReport configReport;
			try {
				configReport = weaponAddon.registerWeapon(ammunitionManager, handler);
			} catch (Exception exception) {
				allErrors.add(generated.getName() + " threw " + exception);
				continue;
			}

			for (ConfigIssue issue : configReport.issues()) {
				String rendered = generated.getName() + ": " + issue.render();
				if (issue.severity() == Severity.ERROR) allErrors.add(rendered);
				else allWarnings.add(rendered);
			}
		}

		System.out.println("== ConfigReport warnings (pending-gate keys etc.) ==");
		allWarnings.forEach(System.out::println);

		assertTrue(allErrors.isEmpty(), "generated weapon YAML produced ConfigReport errors:\n"
		                                 + String.join("\n", allErrors));
		assertEquals(generatedFiles.size(), weaponAddon.getWeaponKeys().size(),
		             "one registered weapon expected per generated file, got: " + weaponAddon.getWeaponKeys());
	}

	/**
	 * None of the 24 vendored defaults reference {@code Reload.Ammo} (every one relies on a plain
	 * {@code Magazine_Size} with no physical ammo item), so the golden test above never exercises this row of the
	 * mapping table on its own - a small synthetic fixture covers it directly.
	 */
	@Test
	void reloadAmmoReferenceBecomesAnAmmunitionAppend() {
		ConfigurationSection ammoRef = YamlConfiguration.loadConfiguration(new java.io.StringReader("""
				Rocket:
				   Item_Ammo:
				      Bullet_Item:
				         Type: TNT
				         Name: "<red>Rocket"
				""")).getConfigurationSection("Rocket");
		Map<String, ConfigurationSection> ammos = Map.of("Rocket", ammoRef);

		ConfigurationSection body = YamlConfiguration.loadConfiguration(new java.io.StringReader("""
				Test_Rocket:
				   Info:
				      Weapon_Item:
				         Type: FEATHER
				         Name: "<gold>Test Rocket"
				   Shoot:
				      Trigger:
				         Main_Hand: RIGHT_CLICK
				      Projectile_Speed: 50
				   Reload:
				      Magazine_Size: 1
				      Reload_Duration: 40
				      Ammo: "Rocket"
				""")).getConfigurationSection("Test_Rocket");

		WmImportReport.WeaponEntry entry = new WmImportReport().weapon("Test_Rocket");
		WmWeaponImporter.ImportedWeapon imported =
				WmWeaponImporter.importWeapon("Test_Rocket", body, Map.of(), ammos, entry);

		assertNotNull(imported);
		assertNotNull(imported.ammoAppend());
		assertEquals("wm_rocket", imported.ammoAppend().id());
		assertEquals("TNT", imported.ammoAppend().material());
		assertEquals("&cRocket", imported.ammoAppend().name());

		@SuppressWarnings("unchecked")
		Map<String, Object> ammunition = (Map<String, Object>) imported.yaml().get("Ammunition");
		assertEquals("wm_rocket", ammunition.get("Ammo_Type"));
	}

	@Test
	void noReloadAmmoReferenceBecomesAmmoTypeNone() {
		ConfigurationSection body = YamlConfiguration.loadConfiguration(new java.io.StringReader("""
				Plain_Rifle:
				   Info:
				      Weapon_Item:
				         Type: FEATHER
				   Shoot:
				      Projectile_Speed: 50
				   Reload:
				      Magazine_Size: 30
				      Reload_Duration: 40
				""")).getConfigurationSection("Plain_Rifle");

		WmImportReport.WeaponEntry entry = new WmImportReport().weapon("Plain_Rifle");
		WmWeaponImporter.ImportedWeapon imported =
				WmWeaponImporter.importWeapon("Plain_Rifle", body, Map.of(), Map.of(), entry);

		assertNotNull(imported);
		assertNull(imported.ammoAppend());

		@SuppressWarnings("unchecked")
		Map<String, Object> ammunition = (Map<String, Object>) imported.yaml().get("Ammunition");
		assertEquals("none", ammunition.get("Ammo_Type"));
	}

	/** Finding HM-2: a SPREAD weapon with zero pellets spread (no {@code Spread.Starting_Spread}) fires every
	 * pellet down one ray - assert the importer always synthesizes one when WM's own shape didn't provide it. */
	@SuppressWarnings("unchecked")
	private void assertSpreadWeaponsHaveStartingSpread(String title, Map<String, Object> yaml) {
		Object shootObj = yaml.get("Shoot");
		if (!(shootObj instanceof Map<?, ?> shoot)) return;

		Object projectileObj = shoot.get("Projectile");
		if (!(projectileObj instanceof Map<?, ?> projectile)) return;
		if (!"SPREAD".equals(projectile.get("Type"))) return;

		Object spreadObj = shoot.get("Spread");
		assertTrue(spreadObj instanceof Map<?, ?>, title + ": SPREAD weapon has no Shoot.Spread section at all");

		Object starting = ((Map<?, ?>) spreadObj).get("Starting_Spread");
		assertTrue(starting instanceof Number, title + ": SPREAD weapon's Shoot.Spread.Starting_Spread is missing");
		assertTrue(((Number) starting).doubleValue() > 0,
		           title + ": SPREAD weapon's Starting_Spread must be > 0, got " + starting);
	}

	private File classpathDirectory(String resource) throws URISyntaxException {
		URL url = Objects.requireNonNull(WmWeaponImporterTest.class.getClassLoader().getResource(resource),
		                                 resource + " is not on the test classpath");
		return new File(url.toURI());
	}

	private Map<String, ConfigurationSection> loadRefs(File dir) {
		Map<String, ConfigurationSection> refs = new LinkedHashMap<>();
		File[] files = dir.listFiles((ignored, name) -> name.endsWith(".yml"));
		if (files == null) return refs;

		for (File file : files) {
			YamlConfiguration doc = YamlConfiguration.loadConfiguration(file);
			for (String key : doc.getKeys(false)) {
				ConfigurationSection section = doc.getConfigurationSection(key);
				if (section != null) refs.put(key, section);
			}
		}
		return refs;
	}

	private void collectYamlFiles(File dir, List<File> out) {
		File[] children = dir.listFiles();
		if (children == null) return;
		for (File child : children) {
			if (child.isDirectory()) collectYamlFiles(child, out);
			else if (child.getName().endsWith(".yml")) out.add(child);
		}
	}

	private void appendAmmoEntries(File ammoFile, List<WmWeaponImporter.AmmoAppend> ammoAppends) throws IOException {
		if (ammoAppends.isEmpty()) return;

		StringBuilder appendText = new StringBuilder();
		for (WmWeaponImporter.AmmoAppend ammo : ammoAppends) {
			appendText.append(ammo.id()).append(":\n")
			          .append("   Material: \"").append(ammo.material()).append("\"\n")
			          .append("   Name: \"").append(ammo.name().replace("\"", "\\\"")).append("\"\n");
		}
		Files.writeString(ammoFile.toPath(), "\n" + appendText, java.nio.file.StandardOpenOption.APPEND);
	}

	private File copyResourceToTempDir(String resourcePath) throws IOException {
		File destination = tempDir.resolve(resourcePath).toFile();
		Files.createDirectories(destination.getParentFile().toPath());

		try (var in = WmWeaponImporterTest.class.getClassLoader().getResourceAsStream(resourcePath)) {
			Objects.requireNonNull(in, resourcePath + " is not on the test classpath");
			Files.copy(in, destination.toPath());
		}
		return destination;
	}

}
