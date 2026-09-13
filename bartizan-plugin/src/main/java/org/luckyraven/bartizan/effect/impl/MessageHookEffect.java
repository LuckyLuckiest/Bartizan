package org.luckyraven.bartizan.effect.impl;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.luckyraven.bartizan.api.weapon.dto.EffectSpec;
import org.luckyraven.bartizan.effect.Effect;
import org.luckyraven.bartizan.effect.EffectContext;

/**
 * {@code Message}: {@code Text, Target (source)} — a plain chat line, player-only.
 */
public class MessageHookEffect implements Effect {

	@Override
	public void run(EffectSpec spec, EffectContext ctx) {
		String text = spec.arg("Text");
		if (text == null) return;

		String formatted = ctx.format(text);
		String target     = spec.arg("Target", "source");

		for (LivingEntity entity : ctx.targets(target, spec.doubleArg("Radius", 0))) {
			if (entity instanceof Player player) player.sendMessage(formatted);
		}
	}

}
