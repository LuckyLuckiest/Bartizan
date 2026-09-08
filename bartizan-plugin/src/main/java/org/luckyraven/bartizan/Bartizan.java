package org.luckyraven.bartizan;

import lombok.CustomLog;
import lombok.Getter;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.SingleLineChart;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;
import org.luckyraven.bartizan.bootstrap.BartizanContext;
import org.luckyraven.bartizan.configuration.WeaponAddon;
import org.luckyraven.bartizan.database.BartizanDatabase;
import org.luckyraven.keystone.nms.PacketBridge;
import org.luckyraven.keystone.persistence.database.DatabaseManager;
import org.luckyraven.keystone.persistence.repository.RepositoryRegistry;

@Getter
@CustomLog
public final class Bartizan extends JavaPlugin {

	public static final String FULL_PREFIX  = "bartizan";
	public static final String SHORT_PREFIX = "btz";

	/**
	 * bStats plugin id — ships as {@code 0} (a no-op id, bStats never throws on it) until the plugin is registered
	 * on bstats.org (bartizan.md Q3). TODO: register Bartizan on bstats.org and set the real id here.
	 */
	private static final int PLUGIN_ID = 0;

	private BartizanContext context;

	@Override
	public void onEnable() {
		try {
			this.context = new BartizanContext(this);
			context.bootstrap();

			dependencyHandler();
			bStats();
		} catch (Throwable t) {
			log.error("Bartizan failed to enable", t);
			getServer().getPluginManager().disablePlugin(this);
		}
	}

	@Override
	public void onDisable() {
		// Symmetric teardown (gate-GG review B2): the WeaponRaytracer, BartizanApi and ItemVocabulary providers are
		// registered at bean construction, so a disable/enable cycle must not leave dead providers behind.
		getServer().getServicesManager().unregisterAll(this);
		PacketBridge.reset();

		if (context == null) return;

		// B1 (gate-GG-review blocker): mirrors the stage-isolation shape of Gangland's ShutdownSequence — bean
		// shutdown, final force-save, connection close, backend disconnect, each isolated so a throwing stage never
		// skips the ones after it. Without the last two stages the DatabaseBackend's HikariCP pool (and, on
		// SQLite/Windows, its file handles) outlives a /reload.
		try {
			stage("shutdown.beans", context::shutdownBeans);
			stage("shutdown.save", this::forceSave);
			stage("shutdown.connections", this::closeConnections);
			stage("shutdown.backend", this::disconnectBackend);
		} finally {
			context.getContainer().clear();
		}
	}

	private void forceSave() {
		RepositoryRegistry repositoryRegistry = context.get(RepositoryRegistry.class);
		if (repositoryRegistry == null) return;

		repositoryRegistry.saveAll();
	}

	private void closeConnections() {
		DatabaseManager databaseManager = context.get(DatabaseManager.class);
		if (databaseManager == null || databaseManager.getDatabases().isEmpty()) return;

		databaseManager.closeConnections();
	}

	private void disconnectBackend() {
		BartizanDatabase database = context.get(BartizanDatabase.class);
		if (database == null) return;

		database.disconnectBackend();
	}

	private void stage(String code, Runnable body) {
		try {
			body.run();
		} catch (Throwable t) {
			log.error("Bartizan shutdown stage '{}' failed; continuing with the remaining stages", code, t);
		}
	}

	/**
	 * Uses bStats to create statistical metrics for development purposes. Folds in the one chart the deleted
	 * {@code WeaponMetricsContributor} used to feed the core through — Bartizan reports it directly now.
	 */
	private void bStats() {
		Metrics metrics = new Metrics(this, PLUGIN_ID);

		metrics.addCustomChart(new SingleLineChart("number_of_weapons",
		                                           () -> {
		                                               WeaponAddon addon = context == null ? null : context.get(WeaponAddon.class);
		                                               return addon == null ? 0 : addon.size();
		                                           }));
	}

	/**
	 * Checks for soft dependencies. Bartizan has no required dependency beyond Keystone (declared in plugin.yml
	 * {@code depend:}, enforced by Bukkit's own plugin loader before {@code onEnable} runs).
	 */
	private void dependencyHandler() {
		Dependency viaVersion = new Dependency("ViaVersion");
		viaVersion.validate(null);

		Dependency placeholderApi = new Dependency("PlaceholderAPI");
		placeholderApi.validate(null);

		Dependency nbtApi = new Dependency("NBTAPI");
		nbtApi.validate(null);
	}

	/**
	 * A thin soft-dependency link/log helper, ported in shape from {@code Gangland.java}'s inner {@code Dependency}
	 * class (bartizan.md §2 B8) — Bartizan only has soft dependencies, so the {@code REQUIRED} branch is dropped.
	 */
	private final class Dependency {

		private final String name;

		private Dependency(String name) {
			this.name = name;
		}

		private void validate(@Nullable Runnable runnable) {
			if (Bukkit.getPluginManager().getPlugin(name) == null) return;

			log.info("Found {}, linking...", name);
			if (runnable != null) runnable.run();
			log.info("Linked {}", name);
		}
	}

}
