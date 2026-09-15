package org.luckyraven.bartizan.configuration;

import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.luckyraven.bartizan.ammo.AmmunitionManager;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.api.weapon.dto.SpreadData;
import org.luckyraven.keystone.persistence.FileHandler;
import org.luckyraven.keystone.testkit.PluginMocks;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Covers {@code WeaponAddon.applyOptionalShootConfig}'s {@code Shoot.Spread.Modify_Spread_When} parsing (gate
 * {@code HE} part b review finding 1: nothing previously called {@link SpreadData}'s five modifier setters, so
 * the multiplier was always 1.0 and every shipped weapon's {@code Modify_Spread_When} block was dead).
 */
class SpreadModifyWhenConfigTest {

	@TempDir
	Path tempDir;

	@Test
	@DisplayName("Spread.Modify_Spread_When parses all five conditions into SpreadData")
	void modifySpreadWhen_parsesIntoSpreadData() throws Exception {
		JavaPlugin  plugin      = PluginMocks.plugin(tempDir);
		WeaponAddon weaponAddon = new WeaponAddon(null);

		File weaponFile = writeWeaponFile("modify_spread.yml", """
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
				      Time: 5
				      Modify_Spread_When:
				         Zooming: -40
				         Sneaking: -20
				         Sprinting: 60
				         In_Midair: 100
				         Swimming: 25
				""");

		weaponAddon.registerWeapon(new AmmunitionManager(), new FileHandler(plugin, weaponFile));

		Weapon weapon = weaponAddon.getWeapon("modify_spread");
		assertNotNull(weapon);
		SpreadData spreadData = weapon.getSpreadData();
		assertNotNull(spreadData);
		assertEquals(-40.0, spreadData.getZoomingModifier());
		assertEquals(-20.0, spreadData.getSneakingModifier());
		assertEquals(60.0, spreadData.getSprintingModifier());
		assertEquals(100.0, spreadData.getInMidairModifier());
		assertEquals(25.0, spreadData.getSwimmingModifier());
	}

	private File writeWeaponFile(String name, String yaml) throws IOException {
		File file = tempDir.resolve("weapon/" + name).toFile();
		Files.createDirectories(file.getParentFile().toPath());
		Files.writeString(file.toPath(), yaml);
		return file;
	}

}
