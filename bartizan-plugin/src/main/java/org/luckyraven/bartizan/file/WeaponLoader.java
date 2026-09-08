package org.luckyraven.bartizan.file;

import lombok.CustomLog;
import org.bukkit.configuration.InvalidConfigurationException;
import org.luckyraven.bartizan.Bartizan;
import org.luckyraven.keystone.persistence.FileManager;
import org.luckyraven.keystone.persistence.FolderLoader;
import org.luckyraven.bartizan.ammo.AmmunitionManager;
import org.luckyraven.bartizan.configuration.WeaponAddon;

@CustomLog
public class WeaponLoader extends FolderLoader {

	private final FileManager       fileManager;
	private final WeaponAddon       weaponAddon;
	private final AmmunitionManager ammunitionManager;

	public WeaponLoader(Bartizan bartizan,
	                    FileManager fileManager,
	                    WeaponAddon weaponAddon,
	                    AmmunitionManager ammunitionManager) {
		super(bartizan, "weapon", fileManager);
		this.fileManager       = fileManager;
		this.weaponAddon       = weaponAddon;
		this.ammunitionManager = ammunitionManager;
	}

	@Override
	public void initialize() {
		this.load(true, fileHandler -> {
			try {
				weaponAddon.registerWeapon(ammunitionManager, fileHandler);
			} catch (InvalidConfigurationException exception) {
				log.info("There was a problem loading the weapon: {}", exception.getMessage());
			}
		}, fileManager);
	}

}
