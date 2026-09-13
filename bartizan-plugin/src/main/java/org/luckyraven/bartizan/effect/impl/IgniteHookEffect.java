package org.luckyraven.bartizan.effect.impl;

import org.bukkit.entity.LivingEntity;
import org.luckyraven.bartizan.api.weapon.dto.EffectSpec;
import org.luckyraven.bartizan.effect.Effect;
import org.luckyraven.bartizan.effect.EffectContext;

/**
 * {@code Ignite}: {@code Ticks (60), Target (victim)}.
 */
public class IgniteHookEffect implements Effect {

	@Override
	public void run(EffectSpec spec, EffectContext ctx) {
		int ticks = spec.intArg("Ticks", 60);
		if (ticks <= 0) return;

		String target = spec.arg("Target", "victim");
		for (LivingEntity entity : ctx.targets(target, spec.doubleArg("Radius", 0))) {
			entity.setFireTicks(ticks);
		}
	}

}
