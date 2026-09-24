package org.luckyraven.bartizan;

import lombok.CustomLog;
import lombok.Getter;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.SingleLineChart;
import org.bukkit.plugin.java.JavaPlugin;
import org.luckyraven.bartizan.bootstrap.BartizanContext;
import org.luckyraven.bartizan.configuration.WeaponAddon;
import org.luckyraven.bartizan.hud.PlaceholderApiSupport;

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
		//
		// Deliberately does NOT call PacketBridge.reset() (BZ-NU-01): on the documented deployment floor
		// (Keystone 1.9.0, pom.xml keystone.version - README.md/migration.md tell admins to deploy
		// Keystone-1.9.0.jar) PacketBridge.reset() is `adapter = NoOpAdapter.INSTANCE`, a single server-global
		// field shared by every Keystone-powered plugin on the shared classloader - Bartizan disabling/reloading
		// would silently downgrade recoil and packet handling to a no-op for every OTHER plugin still running.
		// The adapter itself is stateless reflection (KernelConfig.packetAdapter()), so leaving Bartizan's install
		// live after disable is harmless. (A newer Keystone scopes reset() to the caller's own install, but
		// Bartizan cannot rely on that against the version it compiles/ships against.)
		getServer().getServicesManager().unregisterAll(this);
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

}
