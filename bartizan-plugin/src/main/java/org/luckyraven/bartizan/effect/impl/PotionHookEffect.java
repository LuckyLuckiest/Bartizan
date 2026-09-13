package org.luckyraven.bartizan.effect.impl;

import com.cryptomorin.xseries.XPotion;
import org.bukkit.entity.LivingEntity;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.luckyraven.bartizan.api.weapon.dto.EffectSpec;
import org.luckyraven.bartizan.effect.Effect;
import org.luckyraven.bartizan.effect.EffectContext;

/**
 * {@code Potion}: {@code Potion, Duration (ticks), Amplifier (0), Target (victim)}, resolved cross-version through
 * {@link XPotion}.
 */
public class PotionHookEffect implements Effect {

	@Override
	public void run(EffectSpec spec, EffectContext ctx) {
		String potionName = spec.arg("Potion");
		if (potionName == null) return;

		PotionEffectType type = XPotion.of(potionName).map(XPotion::getPotionEffectType).orElse(null);
		if (type == null) return;

		int duration = spec.intArg("Duration", 0);
		if (duration <= 0) return;
		int amplifier = spec.intArg("Amplifier", 0);

		String target = spec.arg("Target", "victim");
		for (LivingEntity entity : ctx.targets(target, spec.doubleArg("Radius", 0))) {
			entity.addPotionEffect(new PotionEffect(type, duration, amplifier));
		}
	}

}
