package org.luckyraven.bartizan.configuration.parser;

import org.bukkit.NamespacedKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.weapon.SkinState;
import org.luckyraven.bartizan.api.weapon.dto.SkinsData;
import org.luckyraven.keystone.persistence.config.ConfigDocument;
import org.luckyraven.keystone.persistence.config.ConfigParser;
import org.luckyraven.keystone.persistence.config.ConfigReport;
import org.luckyraven.keystone.persistence.config.MappingNode;
import org.luckyraven.keystone.persistence.config.NodeReader;
import org.luckyraven.keystone.persistence.config.Severity;

import java.io.StringReader;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link SkinSectionParser} (weapons-roadmap.md gate {@code HJ}): a full {@code Skins:} block with a
 * {@code Named} skin, an absent section, a state with no root {@code Default} configured, an unknown key (both at
 * the root and inside a {@code Named} entry) producing {@code ConfigReport}'s generic unknown-key warning, and a
 * malformed {@code Item_Model}.
 */
@DisplayName("SkinSectionParser")
class SkinSectionParserTest {

	private static final Path FIXTURE = Path.of("weapon.yml");

	private ConfigReport report;

	@Test
	@DisplayName("a full block parses every state plus one named skin")
	void fullBlock_parsesEveryKey() {
		NodeReader skins = skinsReaderFor("""
				Skins:
				   Default: 1000
				   Scope: 1001
				   Reload: 1002
				   Sprint: 1003
				   No_Ammo: 1004
				   Named:
				      gold:
				         Default: 2000
				         Scope: 2001
				         Item_Model: "bartizan:rifle_gold"
				""");

		SkinsData data = SkinSectionParser.parse(skins, 0, report);

		assertNotNull(data);
		assertEquals(1000, data.state(SkinState.DEFAULT).intValue());
		assertEquals(1001, data.state(SkinState.SCOPE).intValue());
		assertEquals(1002, data.state(SkinState.RELOAD).intValue());
		assertEquals(1003, data.state(SkinState.SPRINT).intValue());
		assertEquals(1004, data.state(SkinState.NO_AMMO).intValue());

		SkinsData.NamedSkin gold = data.named("gold");
		assertNotNull(gold);
		assertEquals(2000, gold.state(SkinState.DEFAULT).intValue());
		assertEquals(2001, gold.state(SkinState.SCOPE).intValue());
		assertEquals(NamespacedKey.fromString("bartizan:rifle_gold"), gold.itemModel());
		assertFalse(report.hasErrors());
		assertTrue(report.issues().isEmpty());
	}

	@Test
	@DisplayName("no Skins: section -> null")
	void noSection_returnsNull() {
		NodeReader skins = skinsReaderFor("Information:\n   Name: test\n");

		assertNull(SkinSectionParser.parse(skins, 0, report));
	}

	@Test
	@DisplayName("an empty Skins: block has no states and no named skins")
	void emptyBlock_hasNoStates() {
		NodeReader skins = skinsReaderFor("Skins: {}\n");

		SkinsData data = SkinSectionParser.parse(skins, 0, report);

		assertNotNull(data);
		for (SkinState state : SkinState.values()) {
			assertNull(data.state(state));
		}
		assertTrue(data.namedKeys().isEmpty());
		assertFalse(report.hasErrors());
	}

	@Test
	@DisplayName("a state with no root Default configured resolves to null (caller falls back to Information)")
	void noDefaultConfigured_stateStaysNull() {
		NodeReader skins = skinsReaderFor("""
				Skins:
				   Scope: 1001
				""");

		SkinsData data = SkinSectionParser.parse(skins, 0, report);

		assertEquals(1001, data.state(SkinState.SCOPE).intValue());
		assertNull(data.state(SkinState.DEFAULT));
		assertNull(data.state(SkinState.RELOAD));

		// review finding 3: no Default and no Information.Custom_Model_Data -> every unconfigured state falls
		// all the way through to "no custom model data at all", worth flagging since it's easy to hit by accident.
		assertTrue(report.issues().stream().anyMatch(
				issue -> issue.severity() == Severity.WARNING && issue.code().equals("skins.no_default_fallback")));
	}

	@Test
	@DisplayName("no root Default, but Information.Custom_Model_Data is set -> no fallback warning")
	void noDefaultConfigured_butInformationCmdSet_noWarning() {
		NodeReader skins = skinsReaderFor("""
				Skins:
				   Scope: 1001
				""");

		SkinSectionParser.parse(skins, 1000, report);

		assertFalse(report.issues().stream().anyMatch(issue -> issue.code().equals("skins.no_default_fallback")));
	}

	@Test
	@DisplayName("a non-numeric state value is a warning, and the state is left unset rather than silently 0")
	void nonNumericStateValue_warnsAndLeavesStateUnset() {
		NodeReader skins = skinsReaderFor("""
				Skins:
				   Default: 1000
				   Scope: not_a_number
				""");

		SkinsData data = SkinSectionParser.parse(skins, 1000, report);

		assertEquals(1000, data.state(SkinState.DEFAULT).intValue());
		assertNull(data.state(SkinState.SCOPE));
		assertFalse(report.hasErrors());
		assertTrue(report.issues().stream().anyMatch(
				issue -> issue.severity() == Severity.WARNING && issue.code().equals("skins.bad_state_value")));
	}

	@Test
	@DisplayName("an unknown key inside Skins: produces a ConfigReport unknown-key warning")
	void unknownKey_warns() {
		NodeReader skins = skinsReaderFor("""
				Skins:
				   Default: 1000
				   Scoep: 1001
				""");

		SkinSectionParser.parse(skins, 0, report);

		assertFalse(report.hasErrors());
		assertTrue(report.issues().stream().anyMatch(
				issue -> issue.severity() == Severity.WARNING && issue.code().equals("config.unknown_key")));
	}

	@Test
	@DisplayName("an unknown key inside a Named skin also warns")
	void unknownKeyInNamedSkin_warns() {
		NodeReader skins = skinsReaderFor("""
				Skins:
				   Named:
				      gold:
				         Defualt: 2000
				""");

		SkinSectionParser.parse(skins, 0, report);

		assertTrue(report.issues().stream().anyMatch(
				issue -> issue.severity() == Severity.WARNING && issue.code().equals("config.unknown_key")));
	}

	@Test
	@DisplayName("a malformed Named.<name>.Item_Model is a warning and resolves to null")
	void badItemModel_warnsAndIsNull() {
		NodeReader skins = skinsReaderFor("""
				Skins:
				   Named:
				      gold:
				         Item_Model: "NOT A VALID KEY"
				""");

		SkinsData data = SkinSectionParser.parse(skins, 0, report);

		SkinsData.NamedSkin gold = data.named("gold");
		assertNotNull(gold);
		assertNull(gold.itemModel());
		assertFalse(report.hasErrors());
		assertTrue(report.issues().stream().anyMatch(
				issue -> issue.severity() == Severity.WARNING && issue.code().equals("skins.bad_item_model")));
	}

	private NodeReader skinsReaderFor(String yaml) {
		report = new ConfigReport();
		ConfigDocument doc  = new ConfigParser().parse(FIXTURE, new StringReader(yaml), report);
		NodeReader     root = NodeReader.of(doc.root(), report);

		MappingNode skinsSection = root.get("Skins").asMapping().orNull();
		return skinsSection != null ? NodeReader.of(skinsSection, report) : null;
	}

}
