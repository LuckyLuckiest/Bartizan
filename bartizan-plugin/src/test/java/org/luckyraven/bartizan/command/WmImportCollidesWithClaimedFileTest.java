package org.luckyraven.bartizan.command;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BZ-IM-05: {@code --dry-run} never writes a file, so {@code outFile.exists()} alone only ever reflects state from
 * BEFORE the run - two source weapons sanitizing to the same {@code fileKey} in the SAME dry run both used to
 * report success, giving a false "clean" preview that a real run (which does hit {@code outFile.exists()} on the
 * second weapon's turn) would not repeat. Pins {@link WmImportCommand#collidesWithClaimedFile}, the extracted
 * guard decision {@code importOne} now calls in both dry-run and real mode.
 */
class WmImportCollidesWithClaimedFileTest {

	@TempDir
	Path tempDir;

	@Test
	@DisplayName("a fileKey already claimed THIS run collides even when nothing was ever written to disk (--dry-run)")
	void keyClaimedThisRun_collidesWithNoDiskWrite() {
		File outFile = tempDir.resolve("never_written.yml").toFile();
		Set<String> claimedThisRun = new HashSet<>();
		claimedThisRun.add("never_written");

		assertTrue(WmImportCommand.collidesWithClaimedFile("never_written", claimedThisRun, outFile),
		          "a second source weapon sanitizing to an already-claimed fileKey must collide in a dry run too, "
		          + "not just when the file happens to already exist on disk");
	}

	@Test
	@DisplayName("a fileKey already on disk from an earlier run collides even with an empty claimed-this-run set")
	void keyAlreadyOnDisk_collidesEvenWithNoClaimThisRun() throws IOException {
		File outFile = tempDir.resolve("already_on_disk.yml").toFile();
		Files.writeString(outFile.toPath(), "placeholder");

		assertTrue(WmImportCommand.collidesWithClaimedFile("already_on_disk", new HashSet<>(), outFile),
		          "the original exists()-on-disk check (a real run's own collision detector) must still work");
	}

	@Test
	@DisplayName("a brand new fileKey, neither claimed this run nor on disk, does not collide")
	void freshKey_doesNotCollide() {
		File outFile = tempDir.resolve("brand_new.yml").toFile();

		assertFalse(WmImportCommand.collidesWithClaimedFile("brand_new", new HashSet<>(), outFile));
	}

}
