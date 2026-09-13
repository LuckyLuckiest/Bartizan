package org.luckyraven.bartizan.effect.impl;

import org.bukkit.Bukkit;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.luckyraven.bartizan.api.weapon.dto.EffectSpec;
import org.luckyraven.bartizan.effect.Effect;
import org.luckyraven.bartizan.effect.EffectContext;

import java.util.List;
import java.util.Map;

/**
 * {@code Command}: {@code Command, As (console|player, console), Target (source)} with {@code %player%}/
 * {@code %victim%} substitution. Gated on {@code Target} resolving to at least one entity; {@code As: console} runs
 * once, {@code As: player} runs once per resolved player target.
 */
public class CommandHookEffect implements Effect {

	@Override
	public void run(EffectSpec spec, EffectContext ctx) {
		String command = spec.arg("Command");
		if (command == null) return;

		String target = spec.arg("Target", "source");
		List<LivingEntity> targets = ctx.targets(target, spec.doubleArg("Radius", 0));
		if (targets.isEmpty()) return;

		String  resolved = substitute(command, ctx);
		boolean asPlayer = "player".equalsIgnoreCase(spec.arg("As", "console"));

		if (asPlayer) {
			for (LivingEntity entity : targets) {
				if (entity instanceof Player player) player.performCommand(resolved);
			}
		} else {
			Bukkit.dispatchCommand(Bukkit.getConsoleSender(), resolved);
		}
	}

	private String substitute(String command, EffectContext ctx) {
		Map<String, String> placeholders = ctx.placeholders();
		return command.replace("%player%", placeholders.getOrDefault("%player%", ""))
		              .replace("%victim%", placeholders.getOrDefault("%victim%", ""));
	}

}
