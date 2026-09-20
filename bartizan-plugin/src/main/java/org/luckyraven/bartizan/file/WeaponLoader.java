package org.luckyraven.bartizan.file;

import lombok.CustomLog;
import org.bukkit.configuration.InvalidConfigurationException;
import org.luckyraven.bartizan.Bartizan;
import org.luckyraven.keystone.persistence.FileManager;
import org.luckyraven.keystone.persistence.FolderLoader;
import org.luckyraven.bartizan.ammo.AmmunitionManager;
import org.luckyraven.bartizan.configuration.WeaponAddon;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

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

	/**
	 * Base names of every {@code weapon/*.yml} bundled at {@code codeSource} (the plugin jar, or a classes directory
	 * under tests), so the expected-file list can never drift from what the jar actually ships.
	 */
	public static List<String> bundledWeaponNames(@Nullable URL codeSource) {
		if (codeSource == null) return List.of();

		List<String> names = new ArrayList<>();
		try {
			File source = new File(codeSource.toURI());
			if (source.isDirectory()) {
				File[] files = new File(source, "weapon").listFiles((dir, name) -> name.endsWith(".yml"));
				if (files != null) for (File file : files) names.add(file.getName());
			} else {
				try (JarFile jar = new JarFile(source)) {
					jar.stream()
					   .map(ZipEntry::getName)
					   .filter(name -> name.startsWith("weapon/") && name.endsWith(".yml") && name.indexOf('/', 7) < 0)
					   .map(name -> name.substring(7))
					   .forEach(names::add);
				}
			}
		} catch (IOException | URISyntaxException | IllegalArgumentException exception) {
			log.warn("Could not list the bundled weapon defaults in {}: {}", codeSource, exception.getMessage());
		}
		Collections.sort(names);
		return names.stream().map(name -> name.substring(0, name.length() - 4)).toList();
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
