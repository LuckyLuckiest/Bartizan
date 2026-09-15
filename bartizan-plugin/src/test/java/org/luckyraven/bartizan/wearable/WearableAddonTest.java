package org.luckyraven.bartizan.wearable;

import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.luckyraven.bartizan.api.testsupport.BukkitRegistryFixture;
import org.luckyraven.bartizan.api.wearable.Wearable;
import org.luckyraven.keystone.persistence.FileHandler;
import org.luckyraven.keystone.persistence.FileManager;
import org.luckyraven.keystone.persistence.config.ConfigIssue;
import org.luckyraven.keystone.persistence.config.ConfigReport;
import org.luckyraven.keystone.persistence.config.Severity;
import org.luckyraven.keystone.testkit.PluginMocks;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression net for the {@code HL} loader rewrite (weapons-roadmap.md gate {@code HL}, §1): loads the bundled
 * {@code items/wearables.yml} through the real {@code NodeReader}/{@code ConfigReport} pipeline and asserts zero
 * issues, plus one registered wearable per top-level entry (mirrors {@code WeaponAddonTest}). Also covers a
 * {@code Sets}/{@code Attributes}/{@code Effects_While_Worn} fixture and the missing/invalid {@code Material}
 * warning path. {@code WearableAddonLegacyJetpackTest} covers {@code legacyJetpackToExtraTags} separately and is
 * untouched by this rewrite; runtime set-bonus/trait resolution is covered by {@code WearableServiceTest}, not
 * here — this class only pins what the loader produces from YAML.
 */
class WearableAddonTest {

	@TempDir
	Path tempDir;

	@BeforeAll
	static void bootstrapBukkitRegistry() {
		BukkitRegistryFixture.install();
	}

	@Test
	@DisplayName("the bundled wearables.yml loads through the real loader with zero ConfigReport issues")
	void bundledWearablesYaml_loadsWithoutConfigErrors() throws Exception {
		JavaPlugin  plugin      = PluginMocks.plugin(tempDir);
		FileManager fileManager = new FileManager(plugin);

		File wearablesFile = copyResourceToTempDir("items/wearables.yml");
		fileManager.addFile(new FileHandler(plugin, wearablesFile), false);

		List<String>  registeredPermissions = new ArrayList<>();
		WearableAddon addon                 = new WearableAddon(registeredPermissions::add, fileManager, null);

		ConfigReport report = addon.load();

		assertTrue(report.issues().isEmpty(), "wearables.yml produced ConfigReport issues:\n" + reportIssues(report));

		int expectedEntries = countTopLevelWearableKeys(wearablesFile);
		assertEquals(expectedEntries, addon.getWearables().size(),
		             "one registered wearable expected per bundled top-level entry (excluding Sets:)");
		assertEquals(expectedEntries, registeredPermissions.size());

		Wearable hazmatChest = addon.getWearable("hazmat_chest");
		assertNotNull(hazmatChest, "hazmat_chest should be registered");
		assertEquals("hazmat", hazmatChest.getSet());
		assertEquals(3, hazmatChest.getAttributes().size(), "Armor, Armor_Toughness, Knockback_Resistance - update "
				+ "alongside the fixture in wearables.yml");
		assertEquals(List.of("SLOWNESS-60-1"), hazmatChest.getEffectsWhileWorn());
	}

	@Test
	@DisplayName("a Sets/Attributes/Effects_While_Worn fixture parses into the wearables and the set registry")
	void setsAttributesAndEffectsWhileWorn_parseCorrectly() throws Exception {
		JavaPlugin  plugin      = PluginMocks.plugin(tempDir);
		FileManager fileManager = new FileManager(plugin);

		writeWearablesFile("""
				fixture_chest:
				   Material: IRON_CHESTPLATE
				   Name: "&7Fixture Chest"
				   Base_Damage_Reduction: 0.1
				   Attributes:
				      Armor: 4.0
				      Knockback_Resistance: 0.1
				   Traits:
				      SEALED: 1
				   Set: fixture
				   Effects_While_Worn:
				      - "SLOWNESS-60-1"

				fixture_helmet:
				   Material: IRON_HELMET
				   Name: "&7Fixture Helmet"
				   Set: fixture

				Sets:
				   fixture:
				      Pieces_2:
				         Traits:
				            SEALED: 2
				         Effects_While_Worn:
				            - "SPEED-60-1"
				""");
		fileManager.addFile(new FileHandler(plugin, tempDir.resolve("items/wearables.yml").toFile()), false);

		WearableAddon addon  = new WearableAddon(ignored -> {
		}, fileManager, null);
		ConfigReport  report = addon.load();

		assertTrue(report.issues().isEmpty(), reportIssues(report));

		Wearable chest = addon.getWearable("fixture_chest");
		assertNotNull(chest);
		assertEquals("fixture", chest.getSet());
		assertEquals(2, chest.getAttributes().size(), "Armor + Knockback_Resistance");
		assertEquals(1, chest.traitLevel("sealed"));
		assertEquals(List.of("SLOWNESS-60-1"), chest.getEffectsWhileWorn());

		Wearable helmet = addon.getWearable("fixture_helmet");
		assertNotNull(helmet);
		assertEquals("fixture", helmet.getSet());

		WearableService.SetTier tier = setsField(addon).get("fixture").get(2);
		assertNotNull(tier, "Sets.fixture.Pieces_2 should be registered");
		assertEquals(Integer.valueOf(2), tier.traits().get("sealed"));
		assertEquals(List.of("SPEED-60-1"), tier.effectsWhileWorn());
	}

	@Test
	@DisplayName("a missing/invalid Material warns and skips only that entry, everything else still loads")
	void invalidMaterial_warnsAndSkipsOnlyThatEntry() throws Exception {
		JavaPlugin  plugin      = PluginMocks.plugin(tempDir);
		FileManager fileManager = new FileManager(plugin);

		writeWearablesFile("""
				good_vest:
				   Material: IRON_CHESTPLATE
				   Name: "&7Good Vest"

				bad_vest:
				   Material: NOT_A_REAL_MATERIAL
				   Name: "&7Bad Vest"

				not_armor:
				   Material: DIRT
				   Name: "&7Not Armor"
				""");
		fileManager.addFile(new FileHandler(plugin, tempDir.resolve("items/wearables.yml").toFile()), false);

		WearableAddon addon  = new WearableAddon(ignored -> {
		}, fileManager, null);
		ConfigReport  report = addon.load();

		assertFalse(report.hasErrors(), reportIssues(report));
		// gate HL review, §6: exactly one wearable.invalid_material warning per invalid entry (bad_vest, not_armor)
		// - markAllKeysTouched() on the abandoned entry must leave no spurious config.unknown_key noise behind it.
		assertEquals(2, report.issues().size(), reportIssues(report));
		assertTrue(report.issues().stream().allMatch(
				           issue -> issue.severity() == Severity.WARNING &&
				                    issue.code().equals("wearable.invalid_material")),
		           "expected only wearable.invalid_material warnings, no unknown_key noise:\n" + reportIssues(report));

		assertNotNull(addon.getWearable("good_vest"));
		assertNull(addon.getWearable("bad_vest"), "an unresolvable Material must skip the entry");
		assertNull(addon.getWearable("not_armor"), "a non-armor Material must skip the entry");
		assertEquals(1, addon.getWearables().size());
	}

	@SuppressWarnings("unchecked")
	private static Map<String, NavigableMap<Integer, WearableService.SetTier>> setsField(WearableAddon addon)
			throws Exception {
		Field field = WearableService.class.getDeclaredField("sets");
		field.setAccessible(true);
		return (Map<String, NavigableMap<Integer, WearableService.SetTier>>) field.get(addon);
	}

	private static String reportIssues(ConfigReport report) {
		return report.issues().stream().map(ConfigIssue::render).collect(Collectors.joining("\n"));
	}

	private void writeWearablesFile(String yaml) throws IOException {
		File file = tempDir.resolve("items/wearables.yml").toFile();
		Files.createDirectories(file.getParentFile().toPath());
		Files.writeString(file.toPath(), yaml);
	}

	private static int countTopLevelWearableKeys(File wearablesFile) throws IOException {
		List<String> lines = Files.readAllLines(wearablesFile.toPath());
		int          count = 0;
		for (String line : lines) {
			if (line.matches("^[A-Za-z_][A-Za-z0-9_]*:\\s*$") && !line.equalsIgnoreCase("Sets:")) count++;
		}
		return count;
	}

	private File copyResourceToTempDir(String resourcePath) throws IOException {
		File destination = tempDir.resolve(resourcePath).toFile();
		Files.createDirectories(destination.getParentFile().toPath());

		try (InputStream in = WearableAddonTest.class.getClassLoader().getResourceAsStream(resourcePath)) {
			Objects.requireNonNull(in, resourcePath + " is not on the test classpath");
			Files.copy(in, destination.toPath());
		}
		return destination;
	}

}
