package org.luckyraven.bartizan.configuration.parser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.weapon.dto.EffectHook;
import org.luckyraven.bartizan.api.weapon.dto.EffectSpec;
import org.luckyraven.bartizan.api.weapon.dto.EffectsData;
import org.luckyraven.bartizan.api.weapon.dto.SoundData;
import org.luckyraven.keystone.persistence.config.ConfigDocument;
import org.luckyraven.keystone.persistence.config.ConfigParser;
import org.luckyraven.keystone.persistence.config.ConfigReport;
import org.luckyraven.keystone.persistence.config.MappingNode;
import org.luckyraven.keystone.persistence.config.NodeReader;
import org.luckyraven.keystone.persistence.config.Severity;
import org.luckyraven.keystone.sound.SoundEffect;

import java.io.StringReader;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link EffectsSectionParser}: every v1 effect type parsing with its args, an unknown type/hook reporting a
 * warning and being skipped, and legacy sound lowering only filling a hook the weapon left undeclared.
 */
@DisplayName("EffectsSectionParser")
class EffectsSectionParserTest {

	private static final Path FIXTURE = Path.of("weapon.yml");

	private ConfigReport report;

	@Test
	@DisplayName("every v1 effect type parses with its args")
	void allTypesParse() {
		NodeReader effects = effectsReaderFor("""
				Effects:
				   On_Shoot:
				      - Type: Sound
				        Sound: ENTITY_GENERIC_EXPLODE
				        Volume: 1.0
				        Pitch: 1.4
				        Target: source
				      - Type: Custom_Sound
				        Sound: mypack:siren
				        Target: victim
				      - Type: Particle
				        Particle: FLAME
				        Count: 4
				        Offset: 0.05 0.05 0.05
				        At: muzzle
				      - Type: Potion
				        Potion: SPEED
				        Duration: 100
				        Amplifier: 1
				        Target: victim
				      - Type: Action_Bar
				        Text: "&c+ Headshot"
				        Target: source
				      - Type: Title
				        Title: "&cKILL"
				        Subtitle: "&7%victim%"
				        Fade_In: 2
				        Stay: 20
				        Fade_Out: 5
				        Target: source
				      - Type: Boss_Bar
				        Text: "&cIncoming"
				        Color: RED
				        Style: SEGMENTED_6
				        Duration: 100
				        Target: source
				      - Type: Message
				        Text: "&7Hit!"
				        Target: source
				      - Type: Command
				        Command: "say hi %player%"
				        As: console
				        Target: source
				      - Type: Push
				        Strength: 1.5
				        Direction: away
				        Target: victim
				      - Type: Camera_Shake
				        Yaw: 4
				        Pitch: 2
				        Target: source
				      - Type: Ignite
				        Ticks: 60
				        Target: victim
				      - Type: Cooldown
				        Ticks: 20
				        Target: source
				      - Type: Firework
				        Power: 0
				        Color: "#FF0000"
				        Firework_Type: BALL
				        At: impact
				      - Type: Lightning
				        At: impact
				""");

		EffectsData data = EffectsSectionParser.parse(effects, report);

		List<EffectSpec> onShoot = data.forHook(EffectHook.ON_SHOOT);
		assertEquals(15, onShoot.size());

		assertEquals("sound", onShoot.get(0).type());
		assertEquals("ENTITY_GENERIC_EXPLODE", onShoot.get(0).arg("Sound"));
		assertEquals("1.0", onShoot.get(0).arg("Volume"));

		assertEquals("custom_sound", onShoot.get(1).type());
		assertEquals("mypack:siren", onShoot.get(1).arg("Sound"));

		assertEquals("particle", onShoot.get(2).type());
		assertEquals("0.05 0.05 0.05", onShoot.get(2).arg("Offset"));
		assertEquals("muzzle", onShoot.get(2).arg("At"));

		assertEquals("potion", onShoot.get(3).type());
		assertEquals("100", onShoot.get(3).arg("Duration"));
		assertEquals(1, onShoot.get(3).intArg("Amplifier", -1));

		assertEquals("action_bar", onShoot.get(4).type());
		assertEquals("title", onShoot.get(5).type());
		assertEquals("boss_bar", onShoot.get(6).type());
		assertEquals("message", onShoot.get(7).type());
		assertEquals("command", onShoot.get(8).type());
		assertEquals("push", onShoot.get(9).type());
		assertEquals("camera_shake", onShoot.get(10).type());
		assertEquals("ignite", onShoot.get(11).type());
		assertEquals("cooldown", onShoot.get(12).type());

		assertEquals("firework", onShoot.get(13).type());
		assertEquals("BALL", onShoot.get(13).arg("Firework_Type"));

		assertEquals("lightning", onShoot.get(14).type());
		assertEquals("impact", onShoot.get(14).arg("At"));

		assertFalse(report.hasErrors());
	}

	@Test
	@DisplayName("On_Critical round-trips through EffectHook.key()/fromKey (gate HA follow-up: the crit hook)")
	void onCritical_roundTripsThroughFromKey() {
		assertEquals("On_Critical", EffectHook.ON_CRITICAL.key());
		assertEquals(EffectHook.ON_CRITICAL, EffectHook.fromKey("On_Critical").orElseThrow());
		assertEquals(EffectHook.ON_CRITICAL, EffectHook.fromKey("on_critical").orElseThrow());
	}

	@Test
	@DisplayName("unknown effect type is a warning and the entry is skipped")
	void unknownType_warnsAndSkips() {
		NodeReader effects = effectsReaderFor("""
				Effects:
				   On_Shoot:
				      - Type: Not_A_Real_Type
				        Foo: bar
				""");

		EffectsData data = EffectsSectionParser.parse(effects, report);

		assertTrue(data.forHook(EffectHook.ON_SHOOT).isEmpty());
		assertTrue(report.issues().stream().anyMatch(
				issue -> issue.severity() == Severity.WARNING && issue.code().equals("effects.unknown_type")));
	}

	@Test
	@DisplayName("unknown hook name is a warning and the whole entry is skipped")
	void unknownHook_warnsAndSkips() {
		NodeReader effects = effectsReaderFor("""
				Effects:
				   On_Not_A_Hook:
				      - Type: Sound
				        Sound: ENTITY_GENERIC_EXPLODE
				""");

		EffectsData data = EffectsSectionParser.parse(effects, report);

		for (EffectHook hook : EffectHook.values()) {
			assertTrue(data.forHook(hook).isEmpty());
		}
		assertTrue(report.issues().stream().anyMatch(
				issue -> issue.severity() == Severity.WARNING && issue.code().equals("effects.unknown_hook")));
	}

	@Test
	@DisplayName("legacy sound lowering fills On_Shoot only when the weapon declared no On_Shoot list")
	void legacyLowering_onlyWhenNoExplicitList() {
		SoundData sounds = new SoundData();
		sounds.setShotDefault(new SoundEffect(SoundEffect.SoundType.VANILLA, "ENTITY_GENERIC_EXPLODE", 1.0F, 1.0F));

		// No On_Shoot declared -> lowering fills it.
		EffectsData undeclared = EffectsData.empty();
		EffectsSectionParser.lowerLegacySounds(sounds, undeclared);
		assertEquals(1, undeclared.forHook(EffectHook.ON_SHOOT).size());
		assertEquals("sound", undeclared.forHook(EffectHook.ON_SHOOT).get(0).type());
		assertEquals("ENTITY_GENERIC_EXPLODE", undeclared.forHook(EffectHook.ON_SHOOT).get(0).arg("Sound"));

		// Weapon already declared its own On_Shoot list -> lowering must not touch it.
		EffectsData declared = EffectsData.empty();
		declared.put(EffectHook.ON_SHOOT, List.of(new EffectSpec("message", Map.of("Text", "hi"))));
		EffectsSectionParser.lowerLegacySounds(sounds, declared);
		assertEquals(1, declared.forHook(EffectHook.ON_SHOOT).size());
		assertEquals("message", declared.forHook(EffectHook.ON_SHOOT).get(0).type());
	}

	@Test
	@DisplayName("legacy sound lowering with both slots set emits exactly one custom_sound spec, never both")
	void legacyLowering_bothSlotsSet_emitsOnlyCustomSound() {
		SoundData sounds = new SoundData();
		sounds.setShotDefault(new SoundEffect(SoundEffect.SoundType.VANILLA, "ENTITY_GENERIC_EXPLODE", 1.0F, 1.0F));
		sounds.setShotCustom(new SoundEffect(SoundEffect.SoundType.CUSTOM, "mypack:shoot", 1.0F, 1.0F));

		EffectsData data = EffectsData.empty();
		EffectsSectionParser.lowerLegacySounds(sounds, data);

		List<EffectSpec> onShoot = data.forHook(EffectHook.ON_SHOOT);
		assertEquals(1, onShoot.size());
		assertEquals("custom_sound", onShoot.get(0).type());
		assertEquals("mypack:shoot", onShoot.get(0).arg("Sound"));
	}

	@Test
	@DisplayName("builtInDefaults mirrors settings.yml's shipped Default_Effects entries (gate HA follow-up item C)")
	void builtInDefaults_mirrorsShippedEntries() {
		EffectsData data = EffectsSectionParser.builtInDefaults();

		List<EffectSpec> critical = data.forHook(EffectHook.ON_CRITICAL);
		assertEquals(1, critical.size());
		assertEquals("sound", critical.get(0).type());
		assertEquals("ITEM_SHIELD_BREAK", critical.get(0).arg("Sound"));
		assertEquals("source", critical.get(0).arg("Target"));

		List<EffectSpec> deny = data.forHook(EffectHook.ON_DENY);
		assertEquals(1, deny.size());
		assertEquals("action_bar", deny.get(0).type());
		assertEquals("&c%deny_reason%", deny.get(0).arg("Text"));
		assertEquals("source", deny.get(0).arg("Target"));

		List<EffectSpec> explode = data.forHook(EffectHook.ON_EXPLODE);
		assertEquals(1, explode.size());
		assertEquals("sound", explode.get(0).type());
		assertEquals("ENTITY_GENERIC_EXPLODE", explode.get(0).arg("Sound"));
		assertEquals("impact", explode.get(0).arg("At"));
	}

	private NodeReader effectsReaderFor(String yaml) {
		report = new ConfigReport();
		ConfigDocument doc  = new ConfigParser().parse(FIXTURE, new StringReader(yaml), report);
		NodeReader     root = NodeReader.of(doc.root(), report);

		MappingNode effectsSection = root.get("Effects").asMapping().orNull();
		return effectsSection != null ? NodeReader.of(effectsSection, report) : null;
	}

}
