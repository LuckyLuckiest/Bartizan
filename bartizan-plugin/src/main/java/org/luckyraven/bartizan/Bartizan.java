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
import org.luckyraven.bartizan.hud.PlaceholderApiSupport;
import org.luckyraven.keystone.nms.PacketBridge;

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
		PlaceholderApiSupport.unregisterIfPresent();

		if (context == null) return;

		// Nothing to flush: the weapon registry is rebuilt from item NBT, so bean shutdown is the only stage left.
		try {
			context.shutdownBeans();
		} catch (Throwable t) {
			log.error("Bartizan bean shutdown failed; clearing the container anyway", t);
		} finally {
			context.getContainer().clear();
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
