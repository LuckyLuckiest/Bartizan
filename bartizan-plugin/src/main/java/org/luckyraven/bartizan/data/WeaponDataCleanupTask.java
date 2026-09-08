package org.luckyraven.bartizan.data;

import lombok.CustomLog;
import org.bukkit.plugin.java.JavaPlugin;
import org.luckyraven.bartizan.database.WeaponRepository;
import org.luckyraven.bartizan.file.BartizanSettings;
import org.luckyraven.bartizan.weapon.WeaponManager;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.keystone.persistence.repository.IRepository;
import org.luckyraven.keystone.timer.Timer;

/**
 * Clears the weapon table and the live {@link WeaponManager} cache on a schedule. Gangland's original
 * {@code WeaponDataCleanupTask} was a passive {@code DataCleanupTask} bean that Gangland's
 * {@code PluginDataCleanupService} discovered and invoked on its own schedule — Bartizan has no such service, so
 * per bartizan.md §1.1 this becomes a plain, self-scheduling Keystone {@link Timer} instead (started by the
 * {@code WiringConfig} bean that constructs it). The cleanup logic itself — the {@code instanceof WeaponRepository}
 * guard included — is verbatim.
 */
@CustomLog
public final class WeaponDataCleanupTask extends Timer {

	private final WeaponManager       weaponManager;
	private final IRepository<Weapon> weaponRepository;

	public WeaponDataCleanupTask(JavaPlugin plugin, WeaponManager weaponManager, IRepository<Weapon> weaponRepository) {
		super(plugin, periodTicks(), periodTicks());
		this.weaponManager    = weaponManager;
		this.weaponRepository = weaponRepository;
	}

	private static long periodTicks() {
		// Auto_Save.Time is authored in minutes (settings.yml, bartizan.md §1.8), same unit Gangland uses.
		return BartizanSettings.getAutoSaveTime() * 60L * 20L;
	}

	public String name() {
		return "weapons";
	}

	@Override
	public void run() {
		cleanup();
	}

	public int cleanup() {
		int count = weaponManager.getWeapons().size();

		if (weaponRepository instanceof WeaponRepository repo) {
			repo.deleteAll();
		}

		weaponManager.clear();

		if (BartizanSettings.isAutoSaveDebug()) log.info("Cleared {} weapons from weapon table", count);
		return count;
	}
}
