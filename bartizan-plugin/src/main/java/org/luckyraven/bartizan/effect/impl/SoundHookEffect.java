package org.luckyraven.bartizan.effect.impl;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.luckyraven.keystone.sound.SoundEffect;
import org.luckyraven.bartizan.api.weapon.dto.EffectSpec;
import org.luckyraven.bartizan.effect.Effect;
import org.luckyraven.bartizan.effect.EffectContext;

/**
 * {@code Sound}: {@code Sound, Volume (1.0), Pitch (1.0), Target (source|victim|nearby), At (source|muzzle|impact|
 * victim)} — with an explicit {@code At} the vanilla {@link SoundEffect} is broadcast once at that location;
 * otherwise it is played privately to each player resolved from {@code Target} (non-players are skipped).
 */
public class SoundHookEffect implements Effect {

	@Override
	public void run(EffectSpec spec, EffectContext ctx) {
		String soundName = spec.arg("Sound");
		if (soundName == null) return;

		float volume = (float) spec.doubleArg("Volume", 1.0);
		float pitch  = (float) spec.doubleArg("Pitch", 1.0);

		SoundEffect sound = new SoundEffect(soundType(), soundName, volume, pitch);

		String at = spec.arg("At");
		if (at != null) {
			Location location = ctx.at(at);
			if (location != null) sound.playAtLocation(location);
			return;
		}

		double radius = spec.doubleArg("Radius", 0);
		for (LivingEntity target : ctx.targets(spec.arg("Target", "source"), radius)) {
			if (target instanceof Player player) sound.playSound(player);
		}
	}

	protected SoundEffect.SoundType soundType() {
		return SoundEffect.SoundType.VANILLA;
	}

}
