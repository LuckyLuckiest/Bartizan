package org.luckyraven.bartizan.data;

import lombok.CustomLog;
import org.bukkit.plugin.java.JavaPlugin;
import org.luckyraven.bartizan.file.BartizanSettings;
import org.luckyraven.keystone.persistence.repository.RepositoryRegistry;
import org.luckyraven.keystone.timer.Timer;

/**
 * Periodically saves every registered repository through {@link RepositoryRegistry#saveAll(Runnable)} — the
 * standalone-plugin twin of Gangland's {@code PeriodicalUpdates.task()} (bartizan.md B1, gate-GG-review blocker:
 * {@code WeaponManager.initialize()} wired {@code setDataSupplier(...)} but nothing ever called {@code saveAll},
 * so every persisted weapon UUID was lost on restart). Bartizan has no {@code PeriodicalUpdates}-style scheduler,
 * so — same shape as {@link WeaponDataCleanupTask} — this is a self-scheduling Keystone {@link Timer}, wired and
 * conditionally started by the {@code WiringConfig} bean that constructs it: a non-positive {@code Auto_Save.Time}
 * disables autosave (the task is built but {@code start(false)} is never called; see {@link Timer#getPeriod()}),
 * mirroring Gangland's {@code Settings.isAutoSave()} gate without a dedicated enable flag.
 */
@CustomLog
public final class WeaponAutoSaveTask extends Timer {

	private final RepositoryRegistry repositoryRegistry;

	public WeaponAutoSaveTask(JavaPlugin plugin, RepositoryRegistry repositoryRegistry) {
		super(plugin, periodTicks(), periodTicks());
		this.repositoryRegistry = repositoryRegistry;
	}

	private static long periodTicks() {
		// Auto_Save.Time is authored in MINUTES (settings.yml) — same unit Gangland's PeriodicalUpdates.onInitialize
		// reads (Settings.getAutoSaveTime() * 60L seconds, * 20L ticks/second).
		return BartizanSettings.getAutoSaveTime() * 60L * 20L;
	}

	@Override
	public void run() {
		boolean debug = BartizanSettings.isAutoSaveDebug();

		if (debug) log.debug("Auto-saving...");

		repositoryRegistry.saveAll(() -> {
			if (debug) log.debug("Auto-save complete");
		});
	}

}
