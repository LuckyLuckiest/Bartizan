package org.luckyraven.bartizan.command;

import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.importer.wm.WmWeaponImporter;
import org.luckyraven.keystone.persistence.config.ConfigDocument;
import org.luckyraven.keystone.persistence.config.ConfigParser;
import org.luckyraven.keystone.persistence.config.ConfigReport;
import org.luckyraven.keystone.persistence.config.NodeReader;

import java.io.StringReader;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BZ-IM-02: {@code WmImportCommand#appendAmmunition} hand-builds YAML text straight into the ONE shared
 * {@code ammunition.yml} every weapon's {@code Ammunition.Ammo_Type} resolves against - a single SnakeYAML
 * document (Keystone's {@code ConfigParser}). Pins {@link WmImportCommand#ammoBlock}, the extracted per-entry
 * block builder, against a {@code Material}/{@code Name} pair carrying an unescaped backslash-then-quote: before
 * the fix this closed the quoted scalar early and dropped every ammo entry appended after it from the same run,
 * not just the one carrying the bad value.
 */
class WmImportCommandAmmoBlockTest {

	private static final Path FIXTURE = Path.of("ammunition.yml");

	@Test
	void ammoBlock_withBackslashBeforeQuoteInName_doesNotCorruptASiblingEntry() {
		WmWeaponImporter.AmmoAppend evil = new WmWeaponImporter.AmmoAppend(
				"wm_evil", "IRON_NUGGET", "Evil\\\" ammo=BAD", "evil_ref"); // Evil\" ammo=BAD
		WmWeaponImporter.AmmoAppend sibling = new WmWeaponImporter.AmmoAppend(
				"wm_other", "COAL", "Other Ammo", "other_ref");

		String text = WmImportCommand.ammoBlock(evil) + "\n" + WmImportCommand.ammoBlock(sibling);

		ConfigReport   report = new ConfigReport();
		ConfigDocument doc    = new ConfigParser().parse(FIXTURE, new StringReader(text), report);
		assertFalse(report.hasErrors(), "appended ammunition.yml text failed to parse:\n" + text + "\n"
		                                 + report.issues());

		NodeReader rootReader = NodeReader.of(doc.root(), report);
		assertTrue(rootReader.get("wm_evil").asMapping().orNull() != null, "wm_evil must still be present");
		NodeReader evilReader = NodeReader.of(rootReader.get("wm_evil").asMapping().orNull(), report);
		assertEquals("IRON_NUGGET", evilReader.get("Material").asString().orNull());
		assertEquals("Evil\\\" ammo=BAD", evilReader.get("Name").asString().orNull());

		assertTrue(rootReader.get("wm_other").asMapping().orNull() != null,
		          "wm_other, appended after the bad entry, must not have been swallowed by an early-closed scalar");
		NodeReader otherReader = NodeReader.of(rootReader.get("wm_other").asMapping().orNull(), report);
		assertEquals("COAL", otherReader.get("Material").asString().orNull());
		assertEquals("Other Ammo", otherReader.get("Name").asString().orNull());
	}

	@Test
	void ammoBlock_withUnescapedMaterial_stillRoundTrips() {
		// Material had NO escaping at all before the fix - a plain quote in a WM source Item_Ammo.Bullet_Item.Type
		// value (unusual, but the field is a bare, ungoverned string) would have broken parsing on its own.
		WmWeaponImporter.AmmoAppend ammo = new WmWeaponImporter.AmmoAppend(
				"wm_quoted_material", "IRON_NUGGET\" #evil", "Plain Name", "quoted_material_ref");

		String text = WmImportCommand.ammoBlock(ammo);

		ConfigReport   report = new ConfigReport();
		ConfigDocument doc    = new ConfigParser().parse(FIXTURE, new StringReader(text), report);
		assertFalse(report.hasErrors(), "appended ammunition.yml text failed to parse:\n" + text + "\n"
		                                 + report.issues());

		NodeReader rootReader = NodeReader.of(doc.root(), report);
		NodeReader entryReader = NodeReader.of(rootReader.get("wm_quoted_material").asMapping().orNull(), report);
		assertEquals("IRON_NUGGET\" #evil", entryReader.get("Material").asString().orNull());
	}

}
