package org.luckyraven.bartizan.effect.impl;

import org.luckyraven.keystone.sound.SoundEffect;

/**
 * {@code Custom_Sound}: same keys as {@link SoundHookEffect}, resource-pack ({@link SoundEffect.SoundType#CUSTOM})
 * key instead of a vanilla one.
 */
public class CustomSoundHookEffect extends SoundHookEffect {

	@Override
	protected SoundEffect.SoundType soundType() {
		return SoundEffect.SoundType.CUSTOM;
	}

}
