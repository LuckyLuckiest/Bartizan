package org.luckyraven.bartizan.config;

import org.luckyraven.bartizan.Bartizan;
import org.luckyraven.bartizan.ammo.AmmunitionManager;
import org.luckyraven.bartizan.configuration.AmmunitionAddon;
import org.luckyraven.bartizan.configuration.WeaponAddon;
import org.luckyraven.bartizan.file.BartizanMessages;
import org.luckyraven.bartizan.file.BartizanSettings;
import org.luckyraven.bartizan.file.WeaponBlockRegenerationSettings;
import org.luckyraven.bartizan.file.WeaponLoader;
import org.luckyraven.bartizan.wearable.WearableAddon;
import org.luckyraven.keystone.bean.Bean;
import org.luckyraven.keystone.bean.Configuration;
import org.luckyraven.keystone.bean.Phase;
import org.luckyraven.keystone.permission.PermissionManager;
import org.luckyraven.keystone.persistence.FileHandler;
import org.luckyraven.keystone.persistence.FileManager;
import org.luckyraven.keystone.persistence.message.LanguageLoader;

import java.security.CodeSource;

/**
 * FILE-phase configuration replacing Gangland's {@code WeaponFileConfig} (bartizan.md §1.3), plus the
 * {@code settings.yml}/{@code message_en.yml} loaders every standalone Keystone plugin needs
 * ({@code oriel-plugin-skeleton.md} §7) — Bartizan is not a module, so it drives both directly rather than relying
 * on a host plugin's core configs.
 */
@Configuration(phase = Phase.FILE)
public final class FilesConfig {

	private final Bartizan bartizan;

	public FilesConfig(Bartizan bartizan) {
		this.bartizan = bartizan;
	}

	@Bean
	public BartizanSettings settingsLoader(FileManager fileManager) {
		BartizanSettings settings = new BartizanSettings(fileManager);
		fileManager.registerInitializer(settings);
		return settings;
	}

	@Bean
	public LanguageLoader languageLoader(FileManager fileManager, BartizanSettings settings) {
		LanguageLoader loader = new LanguageLoader(bartizan, fileManager, BartizanSettings::getLanguagePicked,
		                                           "message", "message", BartizanMessages::findMissingPaths,
		                                           BartizanMessages::init);
		loader.initialize();
		return loader;
	}

	/**
	 * Empty {@link AmmunitionManager} created in the FILE phase so {@link AmmunitionAddon} can populate it.
	 * {@code BartizanSettings} is an ordering-only parameter — the bean-graph edge that keeps this after settings
	 * load (house rule {@code feedback_bean_ordering_via_params}).
	 */
	@Bean
	public AmmunitionManager ammunitionManager(BartizanSettings settings) {
		return new AmmunitionManager();
	}

	@Bean
	public AmmunitionAddon ammunitionAddon(FileManager fileManager, AmmunitionManager ammunitionManager) {
		// AmmunitionAddon resolves fileManager.getFile("ammunition") in its CONSTRUCTOR, so the handler must be
		// registered first. The default ships in bartizan-plugin's own jar at items/ammunition.yml.
		fileManager.addFile(new FileHandler(bartizan, "ammunition", "items", ".yml"), true);
		// No PlaceholderAPI adapter is wired up in this stream (bartizan.md §1.3 says "pass null unless
		// PlaceholderAPI is present" but names no seam class for the detection/adapter) — passing null
		// unconditionally is the safe, scope-preserving default; recorded in bartizan.md §7, task B10.
		AmmunitionAddon addon = new AmmunitionAddon(fileManager, ammunitionManager, null);
		fileManager.registerInitializer(addon);
		return addon;
	}

	@Bean
	public WearableAddon wearableAddon(PermissionManager permissionManager, FileManager fileManager) {
		fileManager.addFile(new FileHandler(bartizan, "wearables", "items", ".yml"), true);
		WearableAddon addon = new WearableAddon(permissionManager::addPermission, fileManager, null);
		fileManager.registerInitializer(addon);
		return addon;
	}

	@Bean
	public WeaponBlockRegenerationSettings blockRegenerationSettings() {
		return new WeaponBlockRegenerationSettings();
	}

	/**
	 * {@code AmmunitionAddon} is an ordering-only parameter — the bean-graph edge that forces ammunition to load
	 * before weapons.
	 */
	@Bean
	public WeaponAddon weaponAddon(AmmunitionAddon ammunitionAddon) {
		return new WeaponAddon(null);
	}

	/**
	 * {@link WeaponLoader} reads its own folder of YAML files and registers them via {@link WeaponAddon}. Every
	 * {@code weapon/*.yml} bundled in the jar is an expected file, so a fresh install gets the whole default set.
	 */
	@Bean
	public WeaponLoader weaponLoader(FileManager fileManager, AmmunitionManager ammunitionManager,
	                                 WeaponAddon weaponAddon) {
		WeaponLoader loader = new WeaponLoader(bartizan, fileManager, weaponAddon, ammunitionManager);
		CodeSource source = Bartizan.class.getProtectionDomain().getCodeSource();
		for (String name : WeaponLoader.bundledWeaponNames(source == null ? null : source.getLocation())) {
			loader.addExpectedFile(new FileHandler(bartizan, name, "weapon", ".yml"));
		}
		return loader;
	}
}
