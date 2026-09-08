package org.luckyraven.bartizan.file;

import org.luckyraven.bartizan.api.weapon.modifiers.BlockRegenerationSettings;

/**
 * {@link BlockRegenerationSettings} implementation backed by {@link BartizanSettings}.
 */
public class WeaponBlockRegenerationSettings implements BlockRegenerationSettings {

	@Override
	public int getRestoreDelayTicks() {
		return BartizanSettings.getBlockRestoreDelayTicks();
	}

	@Override
	public int getRegenerationDelayTicks() {
		return BartizanSettings.getBlockRegenerationDelayTicks();
	}

	@Override
	public int getRegenerationStepTicks() {
		return BartizanSettings.getBlockRegenerationStepTicks();
	}
}
