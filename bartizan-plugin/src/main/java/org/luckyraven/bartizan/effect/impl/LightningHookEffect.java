package org.luckyraven.bartizan.effect.impl;

import org.bukkit.Location;
import org.luckyraven.bartizan.api.weapon.dto.EffectSpec;
import org.luckyraven.bartizan.effect.Effect;
import org.luckyraven.bartizan.effect.EffectContext;

/**
 * {@code Lightning}: {@code At (impact)} — a visual/sound-only strike ({@code World#strikeLightningEffect}, never
 * damages blocks or entities).
 */
public class LightningHookEffect implements Effect {

	@Override
	public void run(EffectSpec spec, EffectContext ctx) {
		Location location = ctx.at(spec.arg("At", "impact"));
		if (location == null || location.getWorld() == null) return;

		location.getWorld().strikeLightningEffect(location);
	}

}
