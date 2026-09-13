package org.luckyraven.bartizan.configuration.parser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.weapon.dto.ChargeData;
import org.luckyraven.bartizan.api.weapon.dto.EffectHook;
import org.luckyraven.bartizan.api.weapon.dto.EffectSpec;
import org.luckyraven.bartizan.api.weapon.dto.EffectsData;
import org.luckyraven.keystone.persistence.config.ConfigDocument;
import org.luckyraven.keystone.persistence.config.ConfigParser;
import org.luckyraven.keystone.persistence.config.ConfigReport;
import org.luckyraven.keystone.persistence.config.MappingNode;
import org.luckyraven.keystone.persistence.config.NodeReader;

import java.io.StringReader;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link ChargeSectionParser}: the new {@code Shoot.Charge} block, the legacy
 * {@code Charge_Time_Per_Level}/{@code Max_Charge_Level} aliases, defaults, min-clamps, and
 * {@code Charge_Feedback} lowering into {@code On_Charge_Level}/{@code On_Charge_Full} (weapons-roadmap.md gate
 * {@code HB} §2.1).
 */
@DisplayName("ChargeSectionParser")
class ChargeSectionParserTest {

	private static final Path FIXTURE = Path.of("weapon.yml");

	private ConfigReport report;

	@Test
	@DisplayName("new Shoot.Charge block")
	void newBlockParses() {
		NodeReader shoot = shootReaderFor("""
				Shoot:
				   Charge:
				      Time_Per_Level: 8
				      Max_Level: 4
				      Min_Level_To_Fire: 2
				      Auto_Fire_At_Max: true
				""");

		ChargeData data = ChargeSectionParser.parse(shoot, report);

		assertEquals(8, data.getTimePerLevel());
		assertEquals(4, data.getMaxLevel());
		assertEquals(2, data.getMinLevelToFire());
		assertTrue(data.isAutoFireAtMax());
		assertFalse(report.hasErrors());
	}

	@Test
	@DisplayName("legacy Charge_Time_Per_Level / Max_Charge_Level aliases, Min_Level_To_Fire/Auto_Fire_At_Max "
			+ "default to 1/false")
	void legacyAliases() {
		NodeReader shoot = shootReaderFor("""
				Shoot:
				   Charge_Time_Per_Level: 15
				   Max_Charge_Level: 5
				""");

		ChargeData data = ChargeSectionParser.parse(shoot, report);

		assertEquals(15, data.getTimePerLevel());
		assertEquals(5, data.getMaxLevel());
		assertEquals(1, data.getMinLevelToFire());
		assertFalse(data.isAutoFireAtMax());
		assertFalse(report.hasErrors());
	}

	@Test
	@DisplayName("neither the block nor the legacy keys present -> 20/3/1/false")
	void defaultsWhenNothingConfigured() {
		NodeReader shoot = shootReaderFor("""
				Shoot:
				   Range: 30.0
				""");

		ChargeData data = ChargeSectionParser.parse(shoot, report);

		assertEquals(20, data.getTimePerLevel());
		assertEquals(3, data.getMaxLevel());
		assertEquals(1, data.getMinLevelToFire());
		assertFalse(data.isAutoFireAtMax());
	}

	@Test
	@DisplayName("Time_Per_Level/Max_Level below 1 clamp to the default instead of a negative/zero value")
	void minClamps() {
		NodeReader shoot = shootReaderFor("""
				Shoot:
				   Charge:
				      Time_Per_Level: 0
				      Max_Level: -1
				""");

		ChargeData data = ChargeSectionParser.parse(shoot, report);

		assertEquals(20, data.getTimePerLevel());
		assertEquals(3, data.getMaxLevel());
		assertTrue(report.hasErrors());
	}

	@Test
	@DisplayName("Charge_Feedback lowers Sound_Level_Up (+ Pitch_Per_Level) and Sound_Full only when the weapon "
			+ "declared no list for that hook")
	void lowerChargeFeedback_producesTwoSpecs() {
		NodeReader shoot = shootReaderFor("""
				Shoot:
				   Charge_Feedback:
				      Sound_Level_Up: BLOCK_NOTE_BLOCK_PLING
				      Pitch_Per_Level: 0.25
				      Sound_Full: BLOCK_BEACON_ACTIVATE
				""");

		EffectsData undeclared = EffectsData.empty();
		ChargeSectionParser.lowerChargeFeedback(shoot, undeclared);

		List<EffectSpec> onLevel = undeclared.forHook(EffectHook.ON_CHARGE_LEVEL);
		assertEquals(1, onLevel.size());
		assertEquals("sound", onLevel.get(0).type());
		assertEquals("BLOCK_NOTE_BLOCK_PLING", onLevel.get(0).arg("Sound"));
		assertEquals("source", onLevel.get(0).arg("Target"));
		assertEquals("0.25", onLevel.get(0).arg("Pitch_Per_Level"));

		List<EffectSpec> onFull = undeclared.forHook(EffectHook.ON_CHARGE_FULL);
		assertEquals(1, onFull.size());
		assertEquals("sound", onFull.get(0).type());
		assertEquals("BLOCK_BEACON_ACTIVATE", onFull.get(0).arg("Sound"));
		assertEquals("source", onFull.get(0).arg("Target"));
	}

	@Test
	@DisplayName("Charge_Feedback never overrides a hook the weapon already declared its own Effects: list for")
	void lowerChargeFeedback_doesNotMergeOverDeclaredHook() {
		NodeReader shoot = shootReaderFor("""
				Shoot:
				   Charge_Feedback:
				      Sound_Level_Up: BLOCK_NOTE_BLOCK_PLING
				      Sound_Full: BLOCK_BEACON_ACTIVATE
				""");

		EffectsData declared = EffectsData.empty();
		declared.put(EffectHook.ON_CHARGE_LEVEL, List.of(new EffectSpec("message", java.util.Map.of("Text", "hi"))));

		ChargeSectionParser.lowerChargeFeedback(shoot, declared);

		assertEquals(1, declared.forHook(EffectHook.ON_CHARGE_LEVEL).size());
		assertEquals("message", declared.forHook(EffectHook.ON_CHARGE_LEVEL).get(0).type());
		// On_Charge_Full was undeclared, so lowering still fills it.
		assertEquals(1, declared.forHook(EffectHook.ON_CHARGE_FULL).size());
	}

	@Test
	@DisplayName("no Charge_Feedback block -> no-op")
	void lowerChargeFeedback_noBlock_noOp() {
		NodeReader shoot = shootReaderFor("""
				Shoot:
				   Range: 30.0
				""");

		EffectsData data = EffectsData.empty();
		ChargeSectionParser.lowerChargeFeedback(shoot, data);

		assertTrue(data.forHook(EffectHook.ON_CHARGE_LEVEL).isEmpty());
		assertTrue(data.forHook(EffectHook.ON_CHARGE_FULL).isEmpty());
	}

	private NodeReader shootReaderFor(String yaml) {
		report = new ConfigReport();
		ConfigDocument doc  = new ConfigParser().parse(FIXTURE, new StringReader(yaml), report);
		NodeReader     root = NodeReader.of(doc.root(), report);

		MappingNode shootSection = root.get("Shoot").asMapping().orNull();
		return shootSection != null ? NodeReader.of(shootSection, report) : null;
	}

}
