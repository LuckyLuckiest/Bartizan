package org.luckyraven.bartizan.importer.wm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WmMechanicsTranslatorTest {

	private WmMechanicsTranslator.TranslatedEffect translate(String raw) {
		WmMechanicsTranslator.ParsedMechanic parsed = WmMechanicsTranslator.parse(raw);
		assertTrue(parsed != null, "expected '" + raw + "' to parse");
		return WmMechanicsTranslator.translate(parsed);
	}

	@Test
	void sound() {
		var effect = translate("Sound{sound=item.armor.equip_chain, pitch=0.75, listeners=Source{}}");
		assertEquals("sound", effect.type());
		assertEquals("item.armor.equip_chain", effect.args().get("Sound"));
		assertEquals("0.75", effect.args().get("Pitch"));
		assertEquals("source", effect.args().get("Target"));
	}

	@Test
	void customSound() {
		var effect = translate("CustomSound{sound=shoot.ak47.loud, volume=6, noise=0.1, delayBeforePlay=2}");
		assertEquals("custom_sound", effect.type());
		assertEquals("shoot.ak47.loud", effect.args().get("Sound"));
		assertEquals("6", effect.args().get("Volume"));
		assertNull(effect.args().get("Pitch_Variance"), "noise has no Bartizan key - SoundHookEffect never reads it");
		assertNull(effect.args().get("Delay"), "delayBeforePlay has no Bartizan key - SoundHookEffect never reads it");
	}

	@Test
	void particle() {
		var effect = translate("Particle{particle=WATER_SPLASH, count=20, noise=0.2 0.2 0.2} @Target{}");
		assertEquals("particle", effect.type());
		assertEquals("WATER_SPLASH", effect.args().get("Particle"));
		assertEquals("20", effect.args().get("Count"));
		assertEquals("0.2 0.2 0.2", effect.args().get("Offset"));
		assertEquals("victim", effect.args().get("Target"));
	}

	@Test
	void potion() {
		var effect = translate("Potion{potion=blindness, time=100, level=1, particles=HIDE} @Target{}");
		assertEquals("potion", effect.type());
		assertEquals("blindness", effect.args().get("Potion"));
		assertEquals("100", effect.args().get("Duration"));
		assertEquals("1", effect.args().get("Amplifier"));
		assertEquals("victim", effect.args().get("Target"));
	}

	@Test
	void actionBar() {
		var effect = translate("ActionBar{message=<gold>Hello}");
		assertEquals("action_bar", effect.type());
		assertEquals("<gold>Hello", effect.args().get("Text"));
	}

	@Test
	void title() {
		var effect = translate("Title{title=Hi, subtitle=There, fadeIn=10, stay=70, fadeOut=20}");
		assertEquals("title", effect.type());
		assertEquals("Hi", effect.args().get("Title"));
		assertEquals("There", effect.args().get("Subtitle"));
		assertEquals("10", effect.args().get("Fade_In"));
	}

	@Test
	void message() {
		var effect = translate("Message{message=plain text}");
		assertEquals("message", effect.type());
		assertEquals("plain text", effect.args().get("Text"));
	}

	@Test
	void bossBar() {
		var effect = translate("BossBar{message=Reloading, color=YELLOW, style=SOLID, duration=40}");
		assertEquals("boss_bar", effect.type());
		assertEquals("Reloading", effect.args().get("Text"));
		assertEquals("YELLOW", effect.args().get("Color"));
	}

	@Test
	void command() {
		var consoleCommand = translate("Command{command=say hi, console=true}");
		assertEquals("command", consoleCommand.type());
		assertEquals("console", consoleCommand.args().get("As"));

		var playerCommand = translate("Command{command=say hi, console=false}");
		assertEquals("player", playerCommand.args().get("As"));

		// BZ-IM-07: an absent console key must default to player (least-privilege), never to console - the
		// converter cannot verify WM's own default for this flag and must not silently hand a migrated weapon's
		// effect full console permissions it was never explicitly configured for.
		var noConsoleKey = translate("Command{command=say hi}");
		assertEquals("player", noConsoleKey.args().get("As"));
	}

	@Test
	void pushAndLeap() {
		var push = translate("Push{speed=1.5, direction=look}");
		assertEquals("push", push.type());
		assertEquals("1.5", push.args().get("Strength"));

		var leap = translate("Leap{height=1.0}");
		assertEquals("push", leap.type());
		assertEquals("1.0", leap.args().get("Strength"));
	}

	@Test
	void ignite() {
		var effect = translate("Ignite{ticks=60}");
		assertEquals("ignite", effect.type());
		assertEquals("60", effect.args().get("Ticks"));
	}

	@Test
	void firework() {
		var effect = translate("Firework{effects=[(shape=BALL, color=RED, flicker=true, trail=true)]}");
		assertEquals("firework", effect.type());
		assertEquals("BALL", effect.args().get("Firework_Type"));
		assertEquals("#FF0000", effect.args().get("Color"));
	}

	@Test
	void lightning() {
		var effect = translate("Lightning{}");
		assertEquals("lightning", effect.type());
	}

	@Test
	void cameraShake() {
		var effect = translate("CameraShake{yaw=1.0, pitch=0.5}");
		assertEquals("camera_shake", effect.type());
		assertEquals("1.0", effect.args().get("Yaw"));
		assertEquals("0.5", effect.args().get("Pitch"));
	}

	@Test
	void unmappedMechanicReturnsNull() {
		WmMechanicsTranslator.ParsedMechanic parsed = WmMechanicsTranslator.parse(
				"Damage{damage=10.0} @World{} ?Range{max=4} ?InCone{direction=~0 0 -1, angle=28}");
		assertTrue(parsed != null);
		assertNull(WmMechanicsTranslator.translate(parsed));
		assertEquals("nearby", parsed.target());
		assertEquals(2, parsed.conditions().size());
		assertTrue(WmMechanicsTranslator.isKnownUnmapped("Damage"));
	}

	@Test
	void malformedStringDoesNotParse() {
		assertNull(WmMechanicsTranslator.parse("not a mechanic"));
		assertNull(WmMechanicsTranslator.parse(null));
	}

	@Test
	void worldTargeterCarriesRange() {
		WmMechanicsTranslator.ParsedMechanic parsed = WmMechanicsTranslator.parse(
				"Sound{sound=explosion.frag} @World{range=8}");
		assertEquals("nearby", parsed.target());
		assertEquals(8.0, parsed.radius());
	}

	@Test
	void sourceTargeterOverridesDefault() {
		WmMechanicsTranslator.ParsedMechanic parsed = WmMechanicsTranslator.parse("Sound{sound=x} @Source{}");
		assertEquals("source", parsed.target());
	}

	@Test
	void noTargeterLeavesTargetUnset() {
		WmMechanicsTranslator.ParsedMechanic parsed = WmMechanicsTranslator.parse("Sound{sound=x}");
		assertNull(parsed.target());
		assertFalse(WmMechanicsTranslator.translate(parsed).args().containsKey("Target"));
	}

}
