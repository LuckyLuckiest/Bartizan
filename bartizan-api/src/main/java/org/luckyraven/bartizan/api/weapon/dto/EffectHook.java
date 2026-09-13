package org.luckyraven.bartizan.api.weapon.dto;

import java.util.Locale;
import java.util.Optional;

/**
 * The v1 feedback hook points a weapon's {@code Effects:} section (or {@code settings.yml}'s
 * {@code Default_Effects:}) may bind a list of {@link EffectSpec}s to (weapons-roadmap.md gate {@code HA}, §1).
 * {@link #key()} is the {@code Capitalized_Underscore} YAML spelling; {@link #fromKey(String)} parses it back,
 * case-insensitively, for {@code EffectsSectionParser}.
 */
public enum EffectHook {

	ON_SHOOT,
	ON_HIT,
	ON_HEADSHOT,
	ON_KILL,
	ON_MISS,
	ON_EMPTY,
	ON_DENY,
	ON_EQUIP,
	ON_HOLSTER,
	ON_RELOAD_START,
	ON_RELOAD_END,
	ON_RELOAD_CANCEL,
	ON_SCOPE_IN,
	ON_SCOPE_OUT,
	ON_EXPLODE,
	ON_CHARGE_LEVEL,
	ON_CHARGE_FULL,
	ON_BEAM_FIRE,
	ON_STATUS_APPLY,
	ON_STATUS_EXPIRE;

	/**
	 * @return the YAML key form, e.g. {@link #ON_RELOAD_START} → {@code "On_Reload_Start"}.
	 */
	public String key() {
		String[]      parts  = name().split("_");
		StringBuilder result = new StringBuilder();

		for (int i = 0; i < parts.length; i++) {
			if (i > 0) result.append('_');
			result.append(parts[i].charAt(0)).append(parts[i].substring(1).toLowerCase(Locale.ROOT));
		}

		return result.toString();
	}

	/**
	 * Case-insensitive parse of a YAML hook key (e.g. {@code "On_Shoot"}, {@code "on_shoot"}) back to the enum
	 * constant.
	 */
	public static Optional<EffectHook> fromKey(String key) {
		if (key == null || key.isBlank()) return Optional.empty();

		String normalized = key.trim().toUpperCase(Locale.ROOT);

		for (EffectHook hook : values()) {
			if (hook.name().equals(normalized)) return Optional.of(hook);
		}

		return Optional.empty();
	}

}
