package org.luckyraven.bartizan.effect.impl;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.luckyraven.bartizan.api.weapon.dto.EffectSpec;
import org.luckyraven.bartizan.effect.Effect;
import org.luckyraven.bartizan.effect.EffectContext;

/**
 * {@code Title}: {@code Title, Subtitle, Fade_In (10), Stay (70), Fade_Out (20), Target (source)} — player-only.
 */
public class TitleHookEffect implements Effect {

	@Override
	public void run(EffectSpec spec, EffectContext ctx) {
		String title    = spec.arg("Title", "");
		String subtitle = spec.arg("Subtitle", "");
		if (title.isEmpty() && subtitle.isEmpty()) return;

		int fadeIn  = spec.intArg("Fade_In", 10);
		int stay    = spec.intArg("Stay", 70);
		int fadeOut = spec.intArg("Fade_Out", 20);

		String target = spec.arg("Target", "source");
		for (LivingEntity entity : ctx.targets(target, spec.doubleArg("Radius", 0))) {
			if (entity instanceof Player player) {
				player.sendTitle(ctx.format(title), ctx.format(subtitle), fadeIn, stay, fadeOut);
			}
		}
	}

}
