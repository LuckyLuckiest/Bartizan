package org.luckyraven.bartizan.configuration;

import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.luckyraven.bartizan.ammo.AmmunitionManager;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.api.weapon.dto.SpreadData;
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
 * BZ-WM-09: {@code Spread.Change.Base} configured with no matching {@code Change.Bounds} block used to leave
 * {@code SpreadData.boundMinimum}/{@code boundMaximum} at primitive-double's {@code 0.0} — a live, reachable bound,
 * not "unconfigured" — so {@code SpreadManager.updateSpread} silently pinned the weapon's spread to 0 after the
 * very first shot. Bounds now default to unbounded, and {@code WeaponAddon} warns about the likely-oversight config
 * shape instead.
 */
class SpreadBoundsConfigTest {

	@TempDir
	Path tempDir;

	@Test
	@DisplayName("Change.Base with no Bounds block warns and leaves bounds unbounded, not collapsed to 0")
	void changeBaseWithoutBounds_warnsAndStaysUnbounded() throws Exception {
		JavaPlugin  plugin      = PluginMocks.plugin(tempDir);
		WeaponAddon weaponAddon = new WeaponAddon(null);

		File weaponFile = writeWeaponFile("no_bounds.yml", """
				Information:
				   Name: "&7Test Rifle&r"
				   Category: gun
				   Material: IRON_HOE

				Shoot:
				   Selective_Fire: single

				   Projectile:
				      Speed: 4
				      Type: BULLET
				      Damage:
				         Base: 5
				      Distance: 60

				   Spread:
				      Starting_Spread: 0.05
				      Change:
				         Base: 0.02
				""");

		ConfigReport report = weaponAddon.registerWeapon(new AmmunitionManager(), new FileHandler(plugin, weaponFile));

		Weapon weapon = weaponAddon.getWeapon("no_bounds");
		assertNotNull(weapon);
		SpreadData spreadData = weapon.getSpreadData();
		assertNotNull(spreadData);
		assertEquals(Double.MAX_VALUE, spreadData.getBoundMaximum());
		assertEquals(-Double.MAX_VALUE, spreadData.getBoundMinimum());

		assertTrue(report.issues().stream().anyMatch(
				issue -> issue.severity() == Severity.WARNING
				         && issue.code().equals("spread.change_without_bounds")));
	}

	private File writeWeaponFile(String name, String yaml) throws IOException {
		File file = tempDir.resolve("weapon/" + name).toFile();
		Files.createDirectories(file.getParentFile().toPath());
		Files.writeString(file.toPath(), yaml);
		return file;
	}

}
