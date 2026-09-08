package org.luckyraven.bartizan.database;

import lombok.CustomLog;
import org.luckyraven.bartizan.Bartizan;
import org.luckyraven.keystone.diagnostics.Diagnostics;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * One-shot DATABASE-phase import of the legacy {@code weapon} table out of a Gangland install's own database
 * (bartizan.md §1.9). Runs once, in the constructor, during the DATABASE bootstrap phase:
 *
 * <ol>
 *   <li>Returns immediately if {@code plugins/Bartizan/.weapon-import-done} already exists.</li>
 *   <li>If {@code plugins/Gangland_Warfare/database/gangland.db} exists, opens it read-only
 *       ({@code jdbc:sqlite:<path>?open_mode=1}), selects every {@code (uuid, type)} row, and upserts them through
 *       this plugin's own {@link org.luckyraven.keystone.persistence.database.backend.DatabaseBackend}.</li>
 *   <li>If the file does not exist, logs at DEBUG and continues — a MySQL deployment or a fresh install is not an
 *       error.</li>
 *   <li>Writes the marker file whether or not anything was imported, so the read never repeats.</li>
 *   <li>Any {@link SQLException} is reported through {@link Diagnostics#active()} as a fault and never aborts
 *       boot — a failed import costs re-minted UUIDs, not data ({@code WeaponService} falls back to the
 *       catalogue).</li>
 * </ol>
 */
@CustomLog
public class WeaponTableImportTask {

	private static final String MARKER_FILE_NAME = ".weapon-import-done";
	private static final String IMPORT_FAULT_CODE = "bartizan.weapon.import.failed";

	private final Bartizan        bartizan;
	private final BartizanDatabase database;

	public WeaponTableImportTask(Bartizan bartizan, BartizanDatabase database) {
		this.bartizan = bartizan;
		this.database = database;

		run();
	}

	private void run() {
		File marker = new File(bartizan.getDataFolder(), MARKER_FILE_NAME);
		if (marker.exists()) {
			return;
		}

		File ganglandDatabase = new File(bartizan.getDataFolder().getParentFile(),
		                                 "Gangland_Warfare" + File.separator + "database" + File.separator +
		                                 "gangland.db");

		if (!ganglandDatabase.exists()) {
			log.debug("No legacy Gangland database found at {} — fresh install or a MySQL deployment, not an " +
			          "error.", ganglandDatabase);
		} else {
			try {
				importFrom(ganglandDatabase);
			} catch (SQLException exception) {
				Diagnostics hub = Diagnostics.active();
				if (hub != null) {
					hub.report(exception, IMPORT_FAULT_CODE);
				}
				log.error("Failed to import the legacy weapon table from {}; existing weapons keep their " +
				          "re-minted UUIDs from the catalogue instead.", ganglandDatabase, exception);
			}
		}

		writeMarker(marker);
	}

	private void importFrom(File ganglandDatabase) throws SQLException {
		String         url  = "jdbc:sqlite:" + ganglandDatabase.getAbsolutePath() + "?open_mode=1";
		List<Object[]> rows = new ArrayList<>();

		try (Connection connection = DriverManager.getConnection(url);
		     Statement statement = connection.createStatement();
		     ResultSet resultSet = statement.executeQuery("SELECT uuid, type FROM weapon")) {

			while (resultSet.next()) {
				rows.add(new Object[]{resultSet.getString("uuid"), resultSet.getString("type")});
			}
		}

		if (rows.isEmpty()) {
			log.info("Legacy weapon table import: 0 row(s) found at {}", ganglandDatabase);
			return;
		}

		database.getBackend().upsertAll("weapon", List.of("uuid"), List.of("uuid", "type"), rows);
		log.info("Legacy weapon table import: {} row(s) imported from {}", rows.size(), ganglandDatabase);
	}

	private void writeMarker(File marker) {
		try {
			File parent = marker.getParentFile();
			if (parent != null && !parent.exists()) {
				parent.mkdirs();
			}
			if (!marker.exists()) {
				marker.createNewFile();
			}
		} catch (IOException exception) {
			log.warn("Could not write the weapon-import marker file at {}", marker, exception);
		}
	}
}
