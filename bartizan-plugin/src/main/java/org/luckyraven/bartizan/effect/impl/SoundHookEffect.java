package org.luckyraven.bartizan.effect.impl;

import org.bukkit.Location;
import org.luckyraven.keystone.sound.SoundEffect;
import org.luckyraven.bartizan.api.weapon.dto.EffectSpec;
import org.luckyraven.bartizan.effect.Effect;
import org.luckyraven.bartizan.effect.EffectContext;

/**
 * {@code Sound}: {@code Sound, Volume (1.0), Pitch (1.0), Target (source), At (source)} — plays a vanilla
 * {@link SoundEffect} broadcast at the {@code At} location ({@code At} falls back to {@code Target} when unset, so
 * the shared default resolves to the same spot either way).
 */
public class SoundHookEffect implements Effect {

	@Override
	public void run(EffectSpec spec, EffectContext ctx) {
		String soundName = spec.arg("Sound");
		if (soundName == null) return;

		float volume = (float) spec.doubleArg("Volume", 1.0);
		float pitch  = (float) spec.doubleArg("Pitch", 1.0);

		String   at       = spec.arg("At", spec.arg("Target", "source"));
		Location location = ctx.at(at);
		if (location == null) return;

		new SoundEffect(soundType(), soundName, volume, pitch).playAtLocation(location);
	}

	protected SoundEffect.SoundType soundType() {
		return SoundEffect.SoundType.VANILLA;
	}

}
