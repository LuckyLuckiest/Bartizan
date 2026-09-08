package org.luckyraven.bartizan.config;

import lombok.CustomLog;
import org.luckyraven.bartizan.Bartizan;
import org.luckyraven.bartizan.database.BartizanDatabase;
import org.luckyraven.bartizan.database.WeaponTableImportTask;
import org.luckyraven.keystone.exception.PluginException;
import org.luckyraven.keystone.bean.Bean;
import org.luckyraven.keystone.bean.Configuration;
import org.luckyraven.keystone.bean.Phase;
import org.luckyraven.bartizan.file.BartizanSettings;
import org.luckyraven.keystone.persistence.database.DatabaseHandler;
import org.luckyraven.keystone.persistence.database.DatabaseManager;
import org.luckyraven.keystone.persistence.database.DatabaseSettingsProvider;
import org.luckyraven.keystone.persistence.database.backend.DatabaseBackend;
import org.luckyraven.keystone.persistence.repository.RepositoryRegistry;

import java.io.IOException;
import java.sql.SQLException;

/**
 * DATABASE-phase wiring — the standalone-plugin twin of Gangland's {@code DatabaseConfig} (bartizan.md §2 B10),
 * minus the module repository-package loop: {@link RepositoryRegistry} only ever scans
 * {@code org.luckyraven.bartizan.database}, since Bartizan ships no modules.
 */
@CustomLog
@Configuration(phase = Phase.DATABASE)
public class DatabaseConfig {

	private final Bartizan bartizan;

	public DatabaseConfig(Bartizan bartizan) {
		this.bartizan = bartizan;
	}

	@Bean
	public BartizanDatabase bartizanDatabase(DatabaseManager databaseManager,
	                                         DatabaseSettingsProvider settings,
	                                         BartizanSettings settingsAddon) {
		int type = BartizanSettings.getDatabaseType().equalsIgnoreCase("mysql")
		           ? DatabaseHandler.MYSQL
		           : DatabaseHandler.SQLITE;

		BartizanDatabase database = new BartizanDatabase(bartizan, "bartizan", settings);
		// Connects the legacy pool and resolves the MySQL→SQLite fallback — the backend must be built from the
		// RESOLVED type, so it connects only after this call.
		database.setType(type);

		if (database.getDatabase() == null) {
			throw new PluginException("Could not connect to the '" + BartizanSettings.getDatabaseType() +
			                          "' database. Check Database.MySQL.Host / Port / Username / Password in " +
			                          "settings.yml, or enable Database.SQLite.Failed_MySQL to fall back to SQLite.");
		}

		try {
			if (database.getType() == DatabaseHandler.MYSQL) {
				database.createSchema();
			}
			database.connectBackend();
		} catch (SQLException | IOException exception) {
			throw new PluginException("Failed to connect the database backend: " + exception.getMessage(), exception);
		}

		database.getRepositoryRegistry().scanAndRegisterRepositories("org.luckyraven.bartizan.database");

		databaseManager.addDatabase(database);
		databaseManager.initializeDatabases();

		BartizanDatabase resolved = BartizanDatabase.findInstance(databaseManager);
		if (resolved == null) {
			throw new PluginException("Bartizan Database instance is not found.");
		}
		return resolved;
	}

	/**
	 * The connected backend as a first-class bean so later phases can inject {@link DatabaseBackend} directly.
	 */
	@Bean
	public DatabaseBackend databaseBackend(BartizanDatabase database) {
		return database.getBackend();
	}

	/**
	 * Exposes the registry as a first-class bean so {@code BartizanContext}'s DATABASE phase hook can pull it from
	 * the container and republish every repository into the container by its concrete class.
	 */
	@Bean
	public RepositoryRegistry repositoryRegistry(BartizanDatabase database) {
		return database.getRepositoryRegistry();
	}

	/**
	 * One-shot import of the legacy {@code weapon} table out of a Gangland install's own database, per
	 * bartizan.md §1.9: returns immediately if {@code plugins/Bartizan/.weapon-import-done} exists; otherwise reads
	 * {@code plugins/Gangland_Warfare/database/gangland.db} read-only (when present) and upserts every row through
	 * this plugin's own {@code TableBackend}, then writes the marker whether or not anything was imported. Any
	 * {@code SQLException} is reported through {@code Diagnostics.active()} and never aborts boot. This bean
	 * forward-references group J's {@code WeaponTableImportTask} (bartizan.md B15) — not written by this task.
	 */
	@Bean
	public WeaponTableImportTask weaponTableImportTask(BartizanDatabase database) {
		return new WeaponTableImportTask(bartizan, database);
	}
}
