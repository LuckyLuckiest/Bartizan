package org.luckyraven.bartizan.effect.impl;

import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.luckyraven.bartizan.api.weapon.dto.EffectSpec;
import org.luckyraven.bartizan.effect.Effect;
import org.luckyraven.bartizan.effect.EffectContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code Boss_Bar}: {@code Text, Color (WHITE), Style (SOLID), Duration (ticks, 60), Target (source)} —
 * player-only; the bar is removed by a task scheduled {@code Duration} ticks out.
 */
public class BossBarHookEffect implements Effect {

	private final JavaPlugin plugin;

	public BossBarHookEffect(JavaPlugin plugin) {
		this.plugin = plugin;
	}

	@Override
	public void run(EffectSpec spec, EffectContext ctx) {
		String text = spec.arg("Text");
		if (text == null) return;

		String target = spec.arg("Target", "source");
		List<Player> players = new ArrayList<>();
		for (LivingEntity entity : ctx.targets(target, spec.doubleArg("Radius", 0))) {
			if (entity instanceof Player player) players.add(player);
		}
		if (players.isEmpty()) return;

		BarColor color    = parseEnum(BarColor.class, spec.arg("Color", "WHITE"), BarColor.WHITE);
		BarStyle style    = parseEnum(BarStyle.class, spec.arg("Style", "SOLID"), BarStyle.SOLID);
		int      duration = Math.max(1, spec.intArg("Duration", 60));

		BossBar bar = Bukkit.createBossBar(ctx.format(text), color, style);
		players.forEach(bar::addPlayer);

		Bukkit.getScheduler().runTaskLater(plugin, bar::removeAll, duration);
	}

	private <T extends Enum<T>> T parseEnum(Class<T> type, String value, T fallback) {
		try {
			return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException exception) {
			return fallback;
		}
	}

}
