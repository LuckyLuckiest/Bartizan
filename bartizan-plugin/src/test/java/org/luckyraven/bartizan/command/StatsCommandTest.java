package org.luckyraven.bartizan.command;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.file.BartizanSettings;
import org.luckyraven.bartizan.stats.PlayerStats;
import org.luckyraven.bartizan.stats.WeaponStat;
import org.luckyraven.bartizan.util.BartizanChatUtil;
import org.luckyraven.keystone.datastructure.JsonFormatter;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * gi=63: {@link JsonFormatter#formatToJson} breaks a line at every unquoted comma (same mechanism
 * {@code AmmunitionInfoCommandTest} pins for a comma ammo id) - the per-weapon "(N shots, N hits, N kills)" clause
 * {@link StatsCommand#totals} builds has two unquoted commas, splitting one weapon's line into three.
 */
class StatsCommandTest {

	@AfterEach
	void tearDown() throws ReflectiveOperationException {
		Field moneySymbol = BartizanSettings.class.getDeclaredField("moneySymbol");
		moneySymbol.setAccessible(true);
		moneySymbol.set(null, null);
	}

	@Test
	@DisplayName("a weapon's shots/hits/kills clause survives the stats formatter on one line")
	void weaponClause_staysOnOneLine() throws Exception {
		Field moneySymbol = BartizanSettings.class.getDeclaredField("moneySymbol");
		moneySymbol.setAccessible(true);
		moneySymbol.set(null, "$");

		PlayerStats stats  = new PlayerStats();
		WeaponStat  weapon = stats.weapon("AK-47");
		weapon.shots = 15;
		weapon.hits  = 17;
		weapon.kills = 0;

		String rendered = new JsonFormatter().formatToJson(
				BartizanChatUtil.color(StatsCommand.totals("Tester", stats)), " ".repeat(3));

		String weaponLine = rendered.lines().filter(line -> line.contains("AK-47")).findFirst()
				.orElseThrow(() -> new AssertionError("no AK-47 line in:\n" + rendered));

		// 10 summary lines + "Weapons:" + exactly one line per weapon when the clause's commas stay quoted.
		assertEquals(12, rendered.lines().count(),
		             "the shots/hits/kills clause must stay on the weapon's own line, not split into extra "
		             + "lines:\n" + rendered);
		assertTrue(weaponLine.contains("15 shots") && weaponLine.contains("17 hits") &&
		           weaponLine.contains("0 kills"), weaponLine);
	}

}
