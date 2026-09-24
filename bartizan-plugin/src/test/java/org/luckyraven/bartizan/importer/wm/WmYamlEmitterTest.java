package org.luckyraven.bartizan.importer.wm;

import org.junit.jupiter.api.Test;
import org.luckyraven.keystone.persistence.config.ConfigDocument;
import org.luckyraven.keystone.persistence.config.ConfigParser;
import org.luckyraven.keystone.persistence.config.ConfigReport;
import org.luckyraven.keystone.persistence.config.MappingNode;
import org.luckyraven.keystone.persistence.config.NodeReader;

import java.io.StringReader;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trip test (weapons-roadmap.md gate {@code HM}, §6.5): the emitted text must parse cleanly with the real
 * {@link ConfigParser} (no {@code yaml.parse}/{@code yaml.top_level} issues) and the values read back through
 * {@link NodeReader} must match what was put into the tree.
 */
class WmYamlEmitterTest {

	private static final Path FIXTURE = Path.of("weapon.yml");

	@Test
	void roundTripsNestedMapsListsAndComments() {
		Map<String, Object> root = new LinkedHashMap<>();

		Map<String, Object> information = new LinkedHashMap<>();
		information.put("Name", "&6AK-47");
		information.put("Category", "gun");
		information.put("Custom_Model_Data", 5);
		information.put("Lore", List.of("&7Line one", "&7Line two"));
		root.put("Information", information);

		root.put("#a_note", "imported: Info.Weapon_Item.Type -> Information.Material");

		Map<String, Object> effects = new LinkedHashMap<>();
		Map<String, Object> soundSpec = new LinkedHashMap<>();
		soundSpec.put("Type", "Sound");
		soundSpec.put("Sound", "ENTITY_GENERIC_EXPLODE");
		soundSpec.put("Volume", 2.0);
		soundSpec.put("Target", "source");
		effects.put("On_Shoot", List.of(soundSpec));
		root.put("Effects", effects);

		root.put("Allowed_Modes", List.of("single", "auto"));
		root.put("EmptyMap", new LinkedHashMap<>());
		root.put("EmptyList", List.of());
		root.put("NullValue", null);

		String text = WmYamlEmitter.emit(root);

		assertTrue(text.contains("# imported: Info.Weapon_Item.Type -> Information.Material"));
		// House style: a list's "- " sits one indent level deeper than its own key, matching a nested map.
		assertTrue(text.contains("   Lore:\n      - \"&7Line one\"\n      - \"&7Line two\"\n"),
		           "Lore list should be indented one level deeper than its key:\n" + text);
		assertFalse(text.contains("EmptyMap"));
		assertFalse(text.contains("EmptyList"));
		assertFalse(text.contains("NullValue"));

		ConfigReport   report = new ConfigReport();
		ConfigDocument doc    = new ConfigParser().parse(FIXTURE, new StringReader(text), report);

		assertFalse(report.hasErrors(), "emitted YAML failed to parse:\n" + text + "\n" + report.issues());

		NodeReader rootReader = NodeReader.of(doc.root(), report);
		MappingNode informationNode = rootReader.get("Information").asMapping().orNull();
		assertTrue(informationNode != null);

		NodeReader informationReader = NodeReader.of(informationNode, report);
		assertEquals("&6AK-47", informationReader.get("Name").asString().orNull());
		assertEquals("gun", informationReader.get("Category").asString().orNull());
		assertEquals(5, informationReader.get("Custom_Model_Data").asInt().orDefault(-1));
		assertEquals(List.of("&7Line one", "&7Line two"), informationReader.get("Lore").asList().ofStrings().orEmpty());

		assertEquals(List.of("single", "auto"), rootReader.get("Allowed_Modes").asList().ofStrings().orEmpty());

		MappingNode effectsNode = rootReader.get("Effects").asMapping().orNull();
		assertTrue(effectsNode != null);
		NodeReader effectsReader = NodeReader.of(effectsNode, report);
		var onShoot = effectsReader.get("On_Shoot").asList().ofMappings().orEmpty();
		assertEquals(1, onShoot.size());
		NodeReader specReader = NodeReader.of(onShoot.get(0), report);
		assertEquals("Sound", specReader.get("Type").asString().orNull());
		assertEquals("ENTITY_GENERIC_EXPLODE", specReader.get("Sound").asString().orNull());
	}

	@Test
	void quotesColoredAndSpecialStrings() {
		Map<String, Object> root = new LinkedHashMap<>();
		root.put("Plain", "gun");
		root.put("Colored", "&6Rifle");
		root.put("WithColon", "a: b");
		root.put("WithSpace", "0.3 -0.2 0");
		root.put("LookupId", "sound.gta.bullet_generic_shoot");

		String text = WmYamlEmitter.emit(root);
		assertTrue(text.contains("Plain: gun\n"));
		assertTrue(text.contains("LookupId: sound.gta.bullet_generic_shoot\n"));
		assertTrue(text.contains("Colored: \"&6Rifle\"\n"));
		assertTrue(text.contains("WithColon: \"a: b\"\n"));
		assertTrue(text.contains("WithSpace: \"0.3 -0.2 0\"\n"));

		ConfigReport report = new ConfigReport();
		new ConfigParser().parse(FIXTURE, new StringReader(text), report);
		assertFalse(report.hasErrors(), report.issues().toString());
	}

	/**
	 * BZ-IM-02: a value ending in a literal backslash-then-quote (e.g. a WM name copy-pasted with a stray
	 * {@code \"}) used to only have its quote escaped, leaving the pre-existing backslash free to pair with the
	 * inserted one into an escaped-backslash-then-bare-quote that closes the scalar early and corrupts every
	 * sibling key after it in the document.
	 */
	@Test
	void quotedStringWithBackslashBeforeQuote_roundTrips() {
		Map<String, Object> root = new LinkedHashMap<>();
		root.put("Evil", "Evil\\\" ammo=BAD"); // Evil\" ammo=BAD
		root.put("Sibling", "still here");

		String text = WmYamlEmitter.emit(root);

		ConfigReport   report = new ConfigReport();
		ConfigDocument doc    = new ConfigParser().parse(FIXTURE, new StringReader(text), report);
		assertFalse(report.hasErrors(), "emitted YAML failed to parse:\n" + text + "\n" + report.issues());

		NodeReader rootReader = NodeReader.of(doc.root(), report);
		assertEquals("Evil\\\" ammo=BAD", rootReader.get("Evil").asString().orNull());
		assertEquals("still here", rootReader.get("Sibling").asString().orNull(),
		            "an unescaped backslash before the closing quote must not swallow the sibling key");
	}

}
