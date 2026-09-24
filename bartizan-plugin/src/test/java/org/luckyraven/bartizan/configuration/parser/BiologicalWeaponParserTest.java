package org.luckyraven.bartizan.configuration.parser;

import org.bukkit.Color;
import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.ammo.AmmunitionManager;
import org.luckyraven.bartizan.api.weapon.BiologicalWeapon;
import org.luckyraven.bartizan.api.weapon.WeaponType;
import org.luckyraven.bartizan.api.weapon.dto.BiologicalData;
import org.luckyraven.bartizan.api.weapon.dto.EffectHook;
import org.luckyraven.bartizan.api.weapon.dto.EffectSpec;
import org.luckyraven.bartizan.api.weapon.dto.EffectsData;
import org.luckyraven.bartizan.api.weapon.dto.ModifiersData;
import org.luckyraven.bartizan.api.weapon.dto.StatusData;
import org.luckyraven.bartizan.api.weapon.modifiers.action.TracerModifier;
import org.luckyraven.keystone.persistence.config.ConfigDocument;
import org.luckyraven.keystone.persistence.config.ConfigParser;
import org.luckyraven.keystone.persistence.config.ConfigReport;
import org.luckyraven.keystone.persistence.config.MappingNode;
import org.luckyraven.keystone.persistence.config.NodeReader;

import java.io.StringReader;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Covers {@link BiologicalWeaponParser}'s new {@code Status:}/{@code Cumulative_Levels} parsing and
 * {@link StatusSectionParser}'s {@code Feedback:} lowering / tracer default (weapons-roadmap.md gate {@code HB}
 * §2.1).
 */
@DisplayName("BiologicalWeaponParser / StatusSectionParser")
class BiologicalWeaponParserTest {

	private static final Path FIXTURE = Path.of("weapon.yml");

	private ConfigReport report;

	@Test
	@DisplayName("legacy file shape (no Charge/Status/Feedback/Cumulative_Levels) still parses, all defaults apply")
	void legacyShape_parsesWithDefaults() throws Exception {
		Readers readers = readersFor("""
				Information:
				   Name: "&aSyringe Gun"
				   Category: biological
				   Material: GLASS_BOTTLE
				Shoot:
				   Charge_Time_Per_Level: 20
				   Max_Charge_Level: 5
				   Range: 30.0
				   Base_Damage: 4.0
				   Effects_Per_Level:
				      - "POISON-60-1"
				""");

		BiologicalWeapon weapon = parse(readers, "&aSyringe Gun");
		BiologicalData   data   = weapon.getBiologicalData();

		assertFalse(data.isCumulativeLevels());

		StatusData status = data.getStatus();
		assertEquals("&aSyringe Gun", status.getName());
		assertEquals("", status.getIcon());
		assertEquals(200, status.getDurationPerLevel());
		assertEquals(StatusData.Stacking.REFRESH, status.getStacking());
		assertEquals(5, status.getMaxLevel(), "defaults to Shoot.Charge's (legacy-aliased) Max_Level");
		assertEquals(200, status.getKillCreditWindow());
		assertNull(status.getContagion());
		assertTrue(status.getCure().items().isEmpty());
		assertNull(status.getCure().wearableTrait());
		assertEquals("&f%icon% %status% &7Lv %level% · %seconds%s", status.getBossBar().text());
		assertEquals("WHITE", status.getBossBar().color());
		assertEquals("SOLID", status.getBossBar().style());
		assertNull(status.getAmbientParticle());
		assertEquals(20, status.getAmbientInterval());
		assertNull(status.getMessageSpread());

		assertFalse(report.hasErrors());
	}

	@Test
	@DisplayName("full Shoot.Status/Cumulative_Levels block parses every key")
	void fullBlock_parsesEveryKey() throws Exception {
		Readers readers = readersFor("""
				Information:
				   Name: "&aSyringe Gun"
				   Category: biological
				   Material: GLASS_BOTTLE
				Shoot:
				   Charge:
				      Time_Per_Level: 20
				      Max_Level: 3
				   Range: 30.0
				   Base_Damage: 4.0
				   Cumulative_Levels: true
				   Effects_Per_Level:
				      - "POISON-60-1"
				   Status:
				      Name: "&2Infected"
				      Icon: "☣"
				      Duration_Per_Level: 150
				      Stacking: escalate
				      Max_Level: 4
				      Kill_Credit_Window: 100
				      Contagion:
				         Radius: 3.0
				         Chance: 0.15
				         Interval: 40
				         Level_Drop: 1
				      Cure:
				         Items:
				            - MILK_BUCKET
				         Wearable_Trait: sealed
				   Feedback:
				      Victim:
				         Boss_Bar:
				            Text: "&2☣ %status% &7Lv %level%"
				            Color: GREEN
				            Style: SEGMENTED_10
				         Ambient_Particle: SPELL_MOB
				         Ambient_Color: "#3FA34D"
				         Ambient_Interval: 10
				      Shooter:
				         Message_Spread: "%victim% caught it from %carrier%"
				""");

		BiologicalWeapon weapon = parse(readers, "&aSyringe Gun");
		BiologicalData   data   = weapon.getBiologicalData();

		assertTrue(data.isCumulativeLevels());

		StatusData status = data.getStatus();
		assertEquals("&2Infected", status.getName());
		assertEquals("☣", status.getIcon());
		assertEquals(150, status.getDurationPerLevel());
		assertEquals(StatusData.Stacking.ESCALATE, status.getStacking());
		assertEquals(4, status.getMaxLevel());
		assertEquals(100, status.getKillCreditWindow());

		StatusData.ContagionData contagion = status.getContagion();
		assertEquals(3.0, contagion.radius());
		assertEquals(0.15, contagion.chance());
		assertEquals(40, contagion.interval());
		assertEquals(1, contagion.levelDrop());

		assertEquals(List.of("MILK_BUCKET"), status.getCure().items());
		assertEquals("sealed", status.getCure().wearableTrait());

		assertEquals("&2☣ %status% &7Lv %level%", status.getBossBar().text());
		assertEquals("GREEN", status.getBossBar().color());
		assertEquals("SEGMENTED_10", status.getBossBar().style());
		assertEquals("SPELL_MOB", status.getAmbientParticle());
		assertEquals("#3FA34D", status.getAmbientColor());
		assertEquals(10, status.getAmbientInterval());
		assertEquals("%victim% caught it from %carrier%", status.getMessageSpread());

		assertFalse(report.hasErrors());
	}

	@Test
	@DisplayName("Feedback lowering produces On_Status_Apply/On_Status_Expire with the documented targets and "
			+ "substitutes %status%/%icon%/%shooter% at lowering time")
	void lowerFeedback_producesExpectedHooksAndTargets() throws Exception {
		Readers readers = readersFor("""
				Shoot:
				   Feedback:
				      Victim:
				         Title: "&2☣ INFECTED"
				         Subtitle: "&7by %shooter% · %status% %icon%"
				         Sound_Apply: ENTITY_ZOMBIE_VILLAGER_CURE
				         Sound_Expire: ENTITY_EXPERIENCE_ORB_PICKUP
				         Message_Expire: "&aThe infection has passed."
				      Shooter:
				         Hit_Marker_Sound: ENTITY_EXPERIENCE_ORB_PICKUP
				         Action_Bar: "&2☣ %victim% &7infected · Lv %level%"
				""");

		EffectsData effects = EffectsData.empty();
		StatusSectionParser.lowerFeedback(readers.shoot(), effects, "Infected", "☣");

		List<EffectSpec> apply = effects.forHook(EffectHook.ON_STATUS_APPLY);
		assertEquals(4, apply.size());

		EffectSpec title = apply.stream().filter(s -> s.type().equals("title")).findFirst().orElseThrow();
		assertEquals("victim", title.arg("Target"));
		assertEquals("&7by %player% · Infected ☣", title.arg("Subtitle"), "%shooter%->%player%, %status%/%icon% substituted");

		EffectSpec victimSound = apply.stream()
				.filter(s -> s.type().equals("sound") && "victim".equals(s.arg("Target"))).findFirst().orElseThrow();
		assertEquals("ENTITY_ZOMBIE_VILLAGER_CURE", victimSound.arg("Sound"));

		EffectSpec sourceSound = apply.stream()
				.filter(s -> s.type().equals("sound") && "source".equals(s.arg("Target"))).findFirst().orElseThrow();
		assertEquals("ENTITY_EXPERIENCE_ORB_PICKUP", sourceSound.arg("Sound"));

		EffectSpec actionBar = apply.stream().filter(s -> s.type().equals("action_bar")).findFirst().orElseThrow();
		assertEquals("source", actionBar.arg("Target"));

		List<EffectSpec> expire = effects.forHook(EffectHook.ON_STATUS_EXPIRE);
		assertEquals(2, expire.size());
		assertTrue(expire.stream().anyMatch(s -> s.type().equals("sound") && "victim".equals(s.arg("Target"))));
		EffectSpec message = expire.stream().filter(s -> s.type().equals("message")).findFirst().orElseThrow();
		assertEquals("victim", message.arg("Target"));
		assertEquals("&aThe infection has passed.", message.arg("Text"));
	}

	@Test
	@DisplayName("Feedback lowering never overrides an explicit Effects.On_Status_Apply list")
	void lowerFeedback_doesNotOverrideDeclaredHook() throws Exception {
		Readers readers = readersFor("""
				Shoot:
				   Feedback:
				      Shooter:
				         Hit_Marker_Sound: ENTITY_EXPERIENCE_ORB_PICKUP
				""");

		EffectsData declared = EffectsData.empty();
		declared.put(EffectHook.ON_STATUS_APPLY, List.of(new EffectSpec("message", Map.of("Text", "hi"))));

		StatusSectionParser.lowerFeedback(readers.shoot(), declared, "Infected", "☣");

		assertEquals(1, declared.forHook(EffectHook.ON_STATUS_APPLY).size());
		assertEquals("message", declared.forHook(EffectHook.ON_STATUS_APPLY).get(0).type());
	}

	@Test
	@DisplayName("no Feedback: block at all still lowers a default hit-marker sound into On_Status_Apply")
	void lowerFeedback_noBlock_stillLowersDefaultHitMarker() throws Exception {
		Readers readers = readersFor("""
				Shoot:
				   Range: 30.0
				""");

		EffectsData effects = EffectsData.empty();
		StatusSectionParser.lowerFeedback(readers.shoot(), effects, "Infected", "☣");

		List<EffectSpec> apply = effects.forHook(EffectHook.ON_STATUS_APPLY);
		assertEquals(1, apply.size());
		assertEquals("sound", apply.get(0).type());
		assertEquals("ENTITY_EXPERIENCE_ORB_PICKUP", apply.get(0).arg("Sound"));
		assertEquals("source", apply.get(0).arg("Target"));
	}

	@Test
	@DisplayName("Charge_Feedback.Tracer_Color defaults Modifiers.Tracer when the weapon declares no explicit one")
	void lowerTracer_defaultsWhenNoExplicitTracer() throws Exception {
		Readers readers = readersFor("""
				Shoot:
				   Charge_Feedback:
				      Tracer_Color: "#3FA34D"
				""");

		ModifiersData modifiers = new ModifiersData();
		StatusSectionParser.lowerTracer(readers.shoot(), modifiers);

		assertTrue(modifiers.hasTracer());
		TracerModifier tracer = modifiers.getTracer();
		assertEquals(Color.fromRGB(0x3F, 0xA3, 0x4D), tracer.color());
		assertFalse(tracer.glowing());
		assertEquals(0.5f, tracer.particleSize());
	}

	@Test
	@DisplayName("an explicit Modifiers.Tracer is never overridden by Charge_Feedback.Tracer_Color")
	void lowerTracer_doesNotOverrideExplicitTracer() throws Exception {
		Readers readers = readersFor("""
				Shoot:
				   Charge_Feedback:
				      Tracer_Color: "#3FA34D"
				""");

		ModifiersData modifiers = new ModifiersData();
		TracerModifier explicit = new TracerModifier(Color.RED, true, 1.0f);
		modifiers.setTracer(explicit);

		StatusSectionParser.lowerTracer(readers.shoot(), modifiers);

		assertEquals(explicit, modifiers.getTracer());
	}

	@Test
	@DisplayName("BZ-CF-05: missing Effects_Per_Level fails the load instead of shipping a silently inert weapon")
	void missingEffectsPerLevel_failsLoad() {
		Readers readers = readersFor("""
				Information:
				   Name: "&aSyringe Gun"
				   Category: biological
				   Material: GLASS_BOTTLE
				Shoot:
				   Range: 30.0
				   Base_Damage: 4.0
				""");

		assertThrows(org.bukkit.configuration.InvalidConfigurationException.class,
		            () -> parse(readers, "&aSyringe Gun"));
	}

	@Test
	@DisplayName("BZ-CF-05: empty Effects_Per_Level list fails the load the same as a missing key")
	void emptyEffectsPerLevel_failsLoad() {
		Readers readers = readersFor("""
				Information:
				   Name: "&aSyringe Gun"
				   Category: biological
				   Material: GLASS_BOTTLE
				Shoot:
				   Range: 30.0
				   Base_Damage: 4.0
				   Effects_Per_Level: []
				""");

		assertThrows(org.bukkit.configuration.InvalidConfigurationException.class,
		            () -> parse(readers, "&aSyringe Gun"));
	}

	private BiologicalWeapon parse(Readers readers, String displayName) throws Exception {
		WeaponBaseData base = new WeaponBaseData("syringe_gun", displayName, WeaponType.BIOLOGICAL,
		                                         Material.GLASS_BOTTLE, 0, (short) 100, List.of(), false, null);
		BiologicalWeaponParser parser = new BiologicalWeaponParser(mock(AmmunitionManager.class));
		return parser.parse(readers.root(), readers.shoot(), report, base);
	}

	private Readers readersFor(String yaml) {
		report = new ConfigReport();
		ConfigDocument doc  = new ConfigParser().parse(FIXTURE, new StringReader(yaml), report);
		NodeReader     root = NodeReader.of(doc.root(), report);

		MappingNode shootSection = root.get("Shoot").asMapping().orNull();
		NodeReader  shoot        = shootSection != null ? NodeReader.of(shootSection, report) : null;
		return new Readers(root, shoot);
	}

	private record Readers(NodeReader root, NodeReader shoot) {
	}

}
