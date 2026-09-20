package org.luckyraven.bartizan.command;

import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.ammo.Ammunition;
import org.luckyraven.bartizan.util.BartizanChatUtil;
import org.luckyraven.keystone.datastructure.JsonFormatter;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ammo ids keep their comma ({@code 7,62}), and {@link JsonFormatter#formatToJson} breaks a line at every unquoted
 * comma - the id must reach the player on one line.
 */
class AmmunitionInfoCommandTest {

	@Test
	@DisplayName("a comma ammo id survives the info formatter on one line")
	void commaIdStaysOnOneLine() {
		Ammunition ammo = new Ammunition("7,62", "&c7.62 NATO&r", Material.FLINT, 0, List.of());

		String rendered = new JsonFormatter().formatToJson(BartizanChatUtil.color(AmmunitionInfoCommand.buildInfo(ammo)),
		                                                   " ".repeat(3));
		String[] lines = rendered.split("\n");

		assertEquals(4, lines.length, rendered);
		assertTrue(lines[0].contains("\"7,62\""), lines[0]);
	}

}
