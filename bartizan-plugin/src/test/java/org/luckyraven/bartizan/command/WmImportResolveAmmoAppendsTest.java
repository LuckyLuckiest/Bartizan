package org.luckyraven.bartizan.command;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.importer.wm.WmImportReport;
import org.luckyraven.bartizan.importer.wm.WmWeaponImporter;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BZ-IM-04: two different WM {@code Reload.Ammo} refs that differ only by punctuation (e.g. {@code "5.56mm"} vs
 * {@code "5,56mm"}) sanitize to the same {@code ammunition.yml} id. {@link WmImportCommand#resolveAmmoAppends}
 * decides which {@code AmmoAppend}s actually get written - before the fix, a same-id collision from a genuinely
 * different {@code sourceRef} was silently dropped with no trace, identically to the ordinary case of the same
 * ref legitimately shared by two weapons.
 */
class WmImportResolveAmmoAppendsTest {

	@Test
	@DisplayName("two different refs colliding on the same sanitized id: first kept, second dropped WITH a runNote")
	void differentSourceRefsCollidingOnSameId_keepsFirstAndReportsTheCollision() {
		WmWeaponImporter.AmmoAppend first  = new WmWeaponImporter.AmmoAppend(
				"wm_5_56mm", "IRON_NUGGET", "5.56mm Round", "5.56mm");
		WmWeaponImporter.AmmoAppend second = new WmWeaponImporter.AmmoAppend(
				"wm_5_56mm", "COAL", "5,56mm Round", "5,56mm");

		WmImportReport report = new WmImportReport();
		List<WmWeaponImporter.AmmoAppend> toWrite =
				WmImportCommand.resolveAmmoAppends(List.of(first, second), Set.of(), report);

		assertEquals(List.of(first), toWrite, "only the first colliding ref's Material/Name should be written");
		assertTrue(report.render().contains("ammo id collision"),
		          "the collision must be recorded, not silently swallowed like report.render():\n" + report.render());
		assertTrue(report.render().contains("5.56mm") && report.render().contains("5,56mm"),
		          "the run note must name both colliding refs");
	}

	@Test
	@DisplayName("the same ref imported by a second weapon is a plain, silent dedup - no collision note")
	void sameSourceRefTwice_dedupsSilently() {
		WmWeaponImporter.AmmoAppend first  = new WmWeaponImporter.AmmoAppend(
				"wm_5_56mm", "IRON_NUGGET", "5.56mm Round", "5.56mm");
		WmWeaponImporter.AmmoAppend second = new WmWeaponImporter.AmmoAppend(
				"wm_5_56mm", "IRON_NUGGET", "5.56mm Round", "5.56mm");

		WmImportReport report = new WmImportReport();
		List<WmWeaponImporter.AmmoAppend> toWrite =
				WmImportCommand.resolveAmmoAppends(List.of(first, second), Set.of(), report);

		assertEquals(List.of(first), toWrite);
		assertTrue(report.render().indexOf("ammo id collision") < 0,
		          "two weapons legitimately sharing the same ammo ref must not be reported as a collision");
	}

	@Test
	@DisplayName("an id already on disk from an earlier run is skipped without a collision note (unknown prior sourceRef)")
	void idAlreadyOnDisk_skippedWithoutFalseCollisionReport() {
		WmWeaponImporter.AmmoAppend ammo = new WmWeaponImporter.AmmoAppend(
				"wm_5_56mm", "IRON_NUGGET", "5.56mm Round", "5.56mm");

		WmImportReport report = new WmImportReport();
		List<WmWeaponImporter.AmmoAppend> toWrite =
				WmImportCommand.resolveAmmoAppends(List.of(ammo), Set.of("wm_5_56mm"), report);

		assertTrue(toWrite.isEmpty(), "an id already present in ammunition.yml is never re-written");
		assertTrue(report.render().indexOf("ammo id collision") < 0,
		          "a prior run's entry has no recorded sourceRef to compare against - must not be reported");
	}

}
