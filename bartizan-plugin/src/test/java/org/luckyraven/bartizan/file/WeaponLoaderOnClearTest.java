package org.luckyraven.bartizan.file;

import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.luckyraven.bartizan.Bartizan;
import org.luckyraven.bartizan.ammo.AmmunitionManager;
import org.luckyraven.bartizan.configuration.WeaponAddon;
import org.luckyraven.keystone.persistence.FileHandler;
import org.luckyraven.keystone.persistence.FileManager;
import org.luckyraven.keystone.testkit.PluginMocks;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * BZ-CF-12: {@link WeaponAddon#getWeapons() weapons} is a plain map populated only by a successful
 * {@code registerWeapon} call and was never pruned on {@code /bartizan reload} - {@code WeaponLoader}'s only
 * override was {@code initialize()}, so a weapon file that starts throwing on reload (a typo that trips a real
 * parse error) left its old, pre-edit {@link org.luckyraven.bartizan.api.weapon.Weapon} entry served forever, with
 * no way to discover this short of a full server restart. {@link WeaponLoader} is itself a
 * {@code BeanLifecycle} bean (via {@code FolderLoader}), so {@code BeanFactory.reloadLifecycleBeans()} calls its
 * {@code onClear()} directly on every reload pass, before {@code onInitialize(false)} re-parses the folder.
 */
class WeaponLoaderOnClearTest {

	@TempDir
	Path tempDir;

	@Test
	void onClearPrunesStaleWeaponsFromWeaponAddon() throws Exception {
		Bartizan bartizan = mock(Bartizan.class);
		when(bartizan.getDataFolder()).thenReturn(tempDir.toFile());
		when(bartizan.getDescription()).thenReturn(
				new PluginDescriptionFile("Bartizan", "0.5.1-TEST", "org.luckyraven.bartizan.Bartizan"));
		when(bartizan.getResource(anyString())).thenReturn(null);
		doNothing().when(bartizan).saveResource(anyString(), anyBoolean());

		FileManager       fileManager       = new FileManager(bartizan);
		WeaponAddon       weaponAddon       = new WeaponAddon(null);
		AmmunitionManager ammunitionManager = new AmmunitionManager();

		JavaPlugin plugin     = PluginMocks.plugin(tempDir);
		File       weaponFile = tempDir.resolve("weapon/pistol.yml").toFile();
		Files.createDirectories(weaponFile.getParentFile().toPath());
		Files.writeString(weaponFile.toPath(), """
				Information:
				   Name: "&7Pistol&r"
				   Category: melee
				   Material: IRON_HOE
				   Durability:
				      Base: 100

				Attack:
				   Damage: 5.0
				   Range: 2.5
				""");
		weaponAddon.registerWeapon(ammunitionManager, new FileHandler(plugin, weaponFile));
		assertNotNull(weaponAddon.getWeapon("pistol"), "sanity: the weapon must have registered first");

		WeaponLoader loader = new WeaponLoader(bartizan, fileManager, weaponAddon, ammunitionManager);

		loader.onClear();

		assertTrue(weaponAddon.getWeaponKeys().isEmpty(),
		           "onClear must drop every previously-loaded weapon so a reload can't keep serving a stale, "
		           + "possibly pre-edit definition for a file that stops parsing");
	}

}
