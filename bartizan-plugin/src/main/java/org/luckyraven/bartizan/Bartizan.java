package org.luckyraven.bartizan;

import lombok.CustomLog;
import lombok.Getter;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.SingleLineChart;
import org.bukkit.plugin.java.JavaPlugin;
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
		// PacketBridge.reset() only where it is scoped to the caller's own install (BZ-NU-01): on the documented
		// deployment floor (Keystone 1.9.0, pom.xml keystone.version) it is `adapter = NoOpAdapter.INSTANCE`, a single
		// server-global field shared by every Keystone-powered plugin - resetting it would downgrade recoil and packet
		// handling to a no-op for every OTHER plugin still running, while leaving Bartizan's stateless install live
		// is harmless. Keystone 1.11.2+ keeps one install per plugin classloader instead, and there skipping the
		// reset pins this dead PluginClassLoader (and every Bartizan static) for good on each disable/enable.
		if (packetBridgeResetIsOwnerScoped()) {
			PacketBridge.reset();
		}
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
	 * {@code true} on a Keystone whose {@code PacketBridge} keeps a per-owner install list (1.11.2+), where
	 * {@code reset()} removes only the caller's own install.
	 * <p>
	 * // ponytail: probes the private field name; swap for a version check once the pom pins Keystone 1.11.2+.
	 */
	static boolean packetBridgeResetIsOwnerScoped() {
		try {
			PacketBridge.class.getDeclaredField("installs");
			return true;
		} catch (NoSuchFieldException absent) {
			return false;
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
