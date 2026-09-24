package org.luckyraven.bartizan.effect.impl;

import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.entity.Firework;
import org.bukkit.inventory.meta.FireworkMeta;
import org.luckyraven.bartizan.api.weapon.dto.EffectSpec;
import org.luckyraven.bartizan.effect.Effect;
import org.luckyraven.bartizan.effect.EffectContext;
import org.luckyraven.bartizan.listener.projectile.CosmeticTag;

import java.util.Locale;

/**
 * {@code Firework}: {@code Power (0), Color (#RRGGBB), Firework_Type (BALL), At (impact)} — spawns and immediately
 * detonates a firework for an effect-only burst (no flight, no damage). Keyed {@code Firework_Type} rather than {@code Type}:
 * every effect entry's own {@code Type} key already picks the effect (this one), so the firework shape needs a
 * distinct key.
 */
public class FireworkHookEffect implements Effect {

	@Override
	public void run(EffectSpec spec, EffectContext ctx) {
		Location location = ctx.at(spec.arg("At", "impact"));
		if (location == null || location.getWorld() == null) return;

		Firework     firework = location.getWorld().spawn(location, Firework.class);
		FireworkMeta meta     = firework.getFireworkMeta();
		meta.setPower(Math.max(0, spec.intArg("Power", 0)));
		meta.addEffect(FireworkEffect.builder()
		                             .with(parseType(spec.arg("Firework_Type", "BALL")))
		                             .withColor(parseColor(spec.arg("Color", "#FFFFFF")))
		                             .build());
		firework.setFireworkMeta(meta);
		// Effect-only: the tag makes ProjectileDamageListener cancel the burst's vanilla splash damage.
		CosmeticTag.mark(firework);

		firework.detonate();
	}

	private FireworkEffect.Type parseType(String value) {
		try {
			return FireworkEffect.Type.valueOf(value.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException exception) {
			return FireworkEffect.Type.BALL;
		}
	}

	private Color parseColor(String hex) {
		try {
			return Color.fromRGB(Integer.parseInt(hex.replace("#", "").trim(), 16));
		} catch (NumberFormatException exception) {
			return Color.WHITE;
		}
	}

}
