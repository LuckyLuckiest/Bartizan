package org.luckyraven.bartizan.configuration.parser;

import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.weapon.dto.HudData;
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
 * Covers {@link HudSectionParser} (weapons-roadmap.md gate {@code HD}): a full {@code HUD:} block, an absent
 * section, missing/empty sub-sections falling back to defaults, and a bad {@code Boss_Bar.Color}/{@code Style}
 * producing a {@code ConfigReport} warning instead of an error.
 */
@DisplayName("HudSectionParser")
class HudSectionParserTest {

	private static final Path FIXTURE = Path.of("weapon.yml");

	private ConfigReport report;

	@Test
	@DisplayName("a full block parses every key")
	void fullBlock_parsesEveryKey() {
		NodeReader hud = hudReaderFor("""
				HUD:
				   Action_Bar: "&6%weapon% &8«&e%ammo_left%&7/&e%ammo_max%&8»"
				   Boss_Bar:
				      Title: "&6%weapon% &7%ammo_left%/%ammo_max%"
				      Color: YELLOW
				      Style: SEGMENTED_10
				   Reload_Item_Cooldown: true
				""");

		HudData data = HudSectionParser.parse(hud, report);

		assertNotNull(data);
		assertEquals("&6%weapon% &8«&e%ammo_left%&7/&e%ammo_max%&8»", data.getActionBar());
		assertNotNull(data.getBossBar());
		assertEquals("&6%weapon% &7%ammo_left%/%ammo_max%", data.getBossBar().title());
		assertEquals(BarColor.YELLOW, data.getBossBar().color());
		assertEquals(BarStyle.SEGMENTED_10, data.getBossBar().style());
		assertTrue(data.isReloadItemCooldown());
		assertFalse(report.hasErrors());
		assertTrue(report.issues().isEmpty());
	}

	@Test
	@DisplayName("no HUD: section -> null (no HUD at all)")
	void noSection_returnsNull() {
		NodeReader hud = hudReaderFor("Information:\n   Name: test\n");

		assertNull(HudSectionParser.parse(hud, report));
	}

	@Test
	@DisplayName("an empty HUD: block defaults every key")
	void emptyBlock_defaultsEveryKey() {
		NodeReader hud = hudReaderFor("HUD: {}\n");

		HudData data = HudSectionParser.parse(hud, report);

		assertNotNull(data);
		assertNull(data.getActionBar());
		assertNull(data.getBossBar());
		assertFalse(data.isReloadItemCooldown());
		assertFalse(report.hasErrors());
	}

	@Test
	@DisplayName("Boss_Bar present without Color/Style falls back to WHITE/SOLID, no warning")
	void bossBarWithoutColorOrStyle_fallsBackWithoutWarning() {
		NodeReader hud = hudReaderFor("""
				HUD:
				   Boss_Bar:
				      Title: "&6Test"
				""");

		HudData data = HudSectionParser.parse(hud, report);

		assertNotNull(data.getBossBar());
		assertEquals(BarColor.WHITE, data.getBossBar().color());
		assertEquals(BarStyle.SOLID, data.getBossBar().style());
		assertTrue(report.issues().isEmpty());
	}

	@Test
	@DisplayName("a bad Boss_Bar.Color falls back to WHITE with a ConfigReport warning")
	void badColor_warnsAndFallsBackToWhite() {
		NodeReader hud = hudReaderFor("""
				HUD:
				   Boss_Bar:
				      Title: "&6Test"
				      Color: NOT_A_COLOR
				      Style: SOLID
				""");

		HudData data = HudSectionParser.parse(hud, report);

		assertEquals(BarColor.WHITE, data.getBossBar().color());
		assertFalse(report.hasErrors());
		assertTrue(report.issues().stream().anyMatch(
				issue -> issue.severity() == Severity.WARNING && issue.code().equals("hud.unknown_color")));
	}

	@Test
	@DisplayName("a bad Boss_Bar.Style falls back to SOLID with a ConfigReport warning")
	void badStyle_warnsAndFallsBackToSolid() {
		NodeReader hud = hudReaderFor("""
				HUD:
				   Boss_Bar:
				      Title: "&6Test"
				      Color: WHITE
				      Style: NOT_A_STYLE
				""");

		HudData data = HudSectionParser.parse(hud, report);

		assertEquals(BarStyle.SOLID, data.getBossBar().style());
		assertFalse(report.hasErrors());
		assertTrue(report.issues().stream().anyMatch(
				issue -> issue.severity() == Severity.WARNING && issue.code().equals("hud.unknown_style")));
	}

	private NodeReader hudReaderFor(String yaml) {
		report = new ConfigReport();
		ConfigDocument doc  = new ConfigParser().parse(FIXTURE, new StringReader(yaml), report);
		NodeReader     root = NodeReader.of(doc.root(), report);

		MappingNode hudSection = root.get("HUD").asMapping().orNull();
		return hudSection != null ? NodeReader.of(hudSection, report) : null;
	}

}
