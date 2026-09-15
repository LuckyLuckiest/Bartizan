package org.luckyraven.bartizan.configuration;

import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.luckyraven.bartizan.ammo.AmmunitionManager;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.api.weapon.dto.RecoilData;
import org.luckyraven.keystone.persistence.FileHandler;
import org.luckyraven.keystone.persistence.config.ConfigReport;
import org.luckyraven.keystone.persistence.config.Severity;
import org.luckyraven.keystone.testkit.PluginMocks;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@code WeaponAddon.applyOptionalShootConfig}'s {@code Shoot.Recoil.Random} parsing (weapons-roadmap.md
 * gate {@code HE} part b): a lone {@code Random} block, and the {@code ConfigReport} warning when both
 * {@code Random} and {@code Pattern} are configured (Random still wins at runtime — see
 * {@code RecoilManagerTest}).
 */
class RecoilRandomConfigTest {

	@TempDir
	Path tempDir;

	@Test
	@DisplayName("Recoil.Random alone parses into RecoilData.random")
	void recoilRandom_parsesIntoRecoilData() throws Exception {
		JavaPlugin  plugin      = PluginMocks.plugin(tempDir);
		WeaponAddon weaponAddon = new WeaponAddon(null);

		File weaponFile = writeWeaponFile("random_recoil.yml", """
				Information:
				   Name: "&7Test Knife&r"
				   Category: melee
				   Material: IRON_HOE

				Attack:
				   Damage: 5.0
				   Range: 2.5

				   Recoil:
				      Random:
				         Mean_X: 2.0
				         Mean_Y: 1.0
				         Variance_X: 4.0
				         Variance_Y: 1.0
				""");

		ConfigReport report = weaponAddon.registerWeapon(new AmmunitionManager(), new FileHandler(plugin, weaponFile));

		Weapon weapon = weaponAddon.getWeapon("random_recoil");
		assertNotNull(weapon);
		RecoilData.RecoilRandom random = weapon.getRecoilData().getRandom();
		assertNotNull(random);
		assertEquals(2.0, random.meanX());
		assertEquals(1.0, random.meanY());
		assertEquals(4.0, random.varianceX());
		assertEquals(1.0, random.varianceY());
		assertTrue(report.issues().stream().noneMatch(issue -> issue.severity() == Severity.WARNING
		                                                        && issue.code().equals("recoil.random_and_pattern")));
	}

	@Test
	@DisplayName("Recoil.Random and Recoil.Pattern both configured -> warning, Random still parsed")
	void recoilRandomAndPattern_bothConfigured_warns() throws Exception {
		JavaPlugin  plugin      = PluginMocks.plugin(tempDir);
		WeaponAddon weaponAddon = new WeaponAddon(null);

		File weaponFile = writeWeaponFile("random_and_pattern.yml", """
				Information:
				   Name: "&7Test Knife&r"
				   Category: melee
				   Material: IRON_HOE

				Attack:
				   Damage: 5.0
				   Range: 2.5

				   Recoil:
				      Pattern:
				         - 2.5;1
				         - 0;0
				      Random:
				         Mean_X: 2.0
				         Mean_Y: 1.0
				         Variance_X: 4.0
				         Variance_Y: 1.0
				""");

		ConfigReport report = weaponAddon.registerWeapon(new AmmunitionManager(), new FileHandler(plugin, weaponFile));

		Weapon weapon = weaponAddon.getWeapon("random_and_pattern");
		assertNotNull(weapon);
		assertNotNull(weapon.getRecoilData().getRandom());
		assertTrue(weapon.getRecoilData().getPattern().size() == 2);
		assertTrue(report.issues().stream().anyMatch(
				issue -> issue.severity() == Severity.WARNING
				         && issue.code().equals("recoil.random_and_pattern")));
	}

	private File writeWeaponFile(String name, String yaml) throws IOException {
		File file = tempDir.resolve("weapon/" + name).toFile();
		Files.createDirectories(file.getParentFile().toPath());
		Files.writeString(file.toPath(), yaml);
		return file;
	}

}
