package org.luckyraven.bartizan.effect.impl;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.luckyraven.keystone.util.ActionBarManager;
import org.luckyraven.bartizan.api.weapon.dto.EffectSpec;
import org.luckyraven.bartizan.effect.Effect;
import org.luckyraven.bartizan.effect.EffectContext;

/**
 * {@code Action_Bar}: {@code Text, Target (source)} — player-only, skips a non-player target (e.g. an NPC
 * shooter).
 */
public class ActionBarHookEffect implements Effect {

	@Override
	public void run(EffectSpec spec, EffectContext ctx) {
		String text = spec.arg("Text");
		if (text == null) return;

		String formatted = ctx.format(text);
		String target     = spec.arg("Target", "source");

		for (LivingEntity entity : ctx.targets(target, spec.doubleArg("Radius", 0))) {
			if (entity instanceof Player player) ActionBarManager.send(player, formatted);
		}
	}

}
