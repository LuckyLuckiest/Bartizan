package org.luckyraven.bartizan.config;

import org.luckyraven.bartizan.Bartizan;
import org.luckyraven.bartizan.command.data.InformationManager;
import org.luckyraven.keystone.bean.Bean;
import org.luckyraven.keystone.bean.Configuration;
import org.luckyraven.keystone.bean.Phase;
import org.luckyraven.keystone.diagnostics.Diagnostics;
import org.luckyraven.keystone.diagnostics.LoggingSink;
import org.luckyraven.keystone.diagnostics.RecentFaultsSink;
import org.luckyraven.keystone.nms.PacketBridge;
import org.luckyraven.keystone.nms.internal.ReflectivePacketAdapter;
import org.luckyraven.keystone.permission.PermissionHandler;
import org.luckyraven.keystone.permission.PermissionManager;
import org.luckyraven.keystone.permission.PermissionWorker;
import org.luckyraven.keystone.persistence.FileHandler;
import org.luckyraven.keystone.persistence.FileManager;

/**
 * KERNEL-phase configuration that produces every bootstrap-critical singleton Bartizan needs before the FILE phase
 * begins — the standalone-plugin twin of Gangland's {@code KernelConfig} (bartizan.md §2 B10), minus every module
 * concern (no {@code ModuleLoader} parameter, no per-module {@code commands.json} merge) and minus the database
 * beans (Bartizan keeps no database since 0.2.0).
 */
@Configuration(phase = Phase.KERNEL)
public class KernelConfig {

	private final Bartizan bartizan;

	public KernelConfig(Bartizan bartizan) {
		this.bartizan = bartizan;
	}

	/**
	 * The {@code /bartizan} help index: Bartizan's own bundled {@code commands.json}, read through the plugin
	 * classloader. Unlike Gangland's counterpart there is no module merge loop — Bartizan ships no modules.
	 */
	@Bean
	public InformationManager informationManager() {
		InformationManager manager = new InformationManager();
		manager.processCommands();
		return manager;
	}

	/**
	 * The plugin's fault hub (Keystone diagnostics). Guard-wrapped listener dispatch and the command dispatch funnel
	 * report here; faults are classified, logged at the right level, and kept in a recent-faults ring. Installed
	 * process-wide so Keystone code paths reach it via {@code Diagnostics.active()}.
	 */
	@Bean
	public Diagnostics diagnostics() {
		Diagnostics hub = Diagnostics.withDefaults()
		                             .addSink(new LoggingSink())
		                             .addSink(new RecentFaultsSink());
		Diagnostics.install(hub);
		return hub;
	}

	@Bean
	public PermissionWorker permissionWorker() {
		return new PermissionWorker(Bartizan.FULL_PREFIX);
	}

	@Bean
	public PermissionManager permissionManager(PermissionHandler permissionHandler) {
		return new PermissionManager(permissionHandler, Bartizan.FULL_PREFIX);
	}

	/**
	 * Installs the reflective recoil adapter (bartizan.md §1.6(2)) — {@code PacketBridge} does not auto-detect, so
	 * every consumer plugin installs its own adapter exactly once at bootstrap. {@code Bartizan.onDisable()} calls
	 * {@code PacketBridge.reset()} only on a Keystone that scopes it to the caller's own install (BZ-NU-01): on the
	 * documented deployment floor (Keystone 1.9.0) that reset is a single server-global field shared by every
	 * Keystone-powered plugin, so withdrawing it there would silently kill packet handling for every other plugin
	 * still running. The install itself is stateless reflection, so leaving it live on 1.9.0 is harmless.
	 */
	@Bean
	public ReflectivePacketAdapter packetAdapter() {
		ReflectivePacketAdapter adapter = new ReflectivePacketAdapter();
		PacketBridge.install(adapter);
		return adapter;
	}

	/**
	 * Constructs the {@link FileManager} and registers the one static {@link FileHandler} Bartizan's kernel owns.
	 * {@code items/ammunition.yml}, {@code items/wearables.yml} and the {@code weapon/*.yml} handlers are
	 * registered by {@code FilesConfig}'s own FILE-phase beans (bartizan.md §1.3), exactly where Gangland's
	 * {@code WeaponFileConfig} registered them — only the {@code moduleLoader.classLoader()} argument is dropped,
	 * since Bartizan has no module classloader.
	 */
	@Bean
	public FileManager fileManager() {
		FileManager fm = new FileManager(bartizan);
		fm.addFile(new FileHandler(bartizan, "settings", ".yml"), true);
		return fm;
	}

}
