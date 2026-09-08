package org.luckyraven.bartizan.database;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.luckyraven.bartizan.Bartizan;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link WeaponTableImportTask} — R-B-FINAL review finding M2: a caught {@link java.sql.SQLException} during the
 * one-shot legacy import must NOT write the marker file, so a transient failure (e.g. {@code SQLITE_BUSY} while
 * Gangland's own pool still holds {@code gangland.db} on the first boot) retries on the next boot instead of being
 * permanently skipped.
 */
@DisplayName("WeaponTableImportTask")
class WeaponTableImportTaskTest {

	@Test
	@DisplayName("no legacy Gangland database present writes the marker so the check never repeats")
	void run_sourceFileAbsent_writesMarker(@TempDir(cleanup = CleanupMode.NEVER) File serverFolder) {
		File bartizanFolder = new File(serverFolder, "Bartizan");
		bartizanFolder.mkdirs();
		// The sibling Gangland_Warfare/database/gangland.db is deliberately never created.

		Bartizan          bartizan = mock(Bartizan.class);
		when(bartizan.getDataFolder()).thenReturn(bartizanFolder);
		BartizanDatabase database = mock(BartizanDatabase.class);

		new WeaponTableImportTask(bartizan, database);

		assertTrue(new File(bartizanFolder, ".weapon-import-done").exists());
	}

	@Test
	@DisplayName("a caught SQLException during import does NOT write the marker, so the next boot retries")
	void run_importThrows_doesNotWriteMarker(@TempDir(cleanup = CleanupMode.NEVER) File serverFolder) throws IOException {
		File bartizanFolder = new File(serverFolder, "Bartizan");
		bartizanFolder.mkdirs();
		File ganglandDbDir = new File(serverFolder, "Gangland_Warfare" + File.separator + "database");
		ganglandDbDir.mkdirs();
		File ganglandDb = new File(ganglandDbDir, "gangland.db");
		// Not a real SQLite file — the JDBC driver throws SQLException reading it, exercising the retry path.
		Files.write(ganglandDb.toPath(), "not a real sqlite database".getBytes());

		Bartizan          bartizan = mock(Bartizan.class);
		when(bartizan.getDataFolder()).thenReturn(bartizanFolder);
		BartizanDatabase database = mock(BartizanDatabase.class);

		new WeaponTableImportTask(bartizan, database);

		assertFalse(new File(bartizanFolder, ".weapon-import-done").exists(),
				"a transient/corrupt read failure must retry on the next boot, not be permanently skipped");
	}

}
