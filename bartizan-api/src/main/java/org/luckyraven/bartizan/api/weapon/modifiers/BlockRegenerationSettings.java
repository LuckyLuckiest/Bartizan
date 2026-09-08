package org.luckyraven.bartizan.api.weapon.modifiers;

/**
 * Provides block-regeneration tuning values to {@link BlockDamageManager}.
 * <p>
 * Implemented by {@code BartizanSettings}, keeping this api module decoupled from the plugin's own
 * file-loading infrastructure.
 */
public interface BlockRegenerationSettings {

	/**
	 * Ticks between a block fully breaking and the moment it reappears in {@code RESTORE} mode.
	 */
	int getRestoreDelayTicks();

	/**
	 * Ticks between the last hit on a cracked block and the start of crack reverse-decay.
	 */
	int getRegenerationDelayTicks();

	/**
	 * Ticks between each crack-stage decrement during reverse-decay.
	 */
	int getRegenerationStepTicks();
}
