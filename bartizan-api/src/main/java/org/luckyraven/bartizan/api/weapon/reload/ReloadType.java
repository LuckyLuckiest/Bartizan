package org.luckyraven.bartizan.api.weapon.reload;

import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.api.ammo.Ammunition;
import org.luckyraven.bartizan.api.weapon.reload.InstantReload;
import org.luckyraven.bartizan.api.weapon.reload.NumberedReload;

import java.util.Locale;
import java.util.Optional;

public enum ReloadType {

	INSTANT,
	ONE,
	NUM;

	/**
	 * @return the matching {@link ReloadType} for a recognised key, or {@link Optional#empty()} for an
	 * 		unrecognised/blank/{@code null} one — callers that can report a {@code ConfigReport} warning (BZ-CF-14)
	 * 		should use this instead of {@link #getType(String)}, which silently defaults.
	 */
	public static Optional<ReloadType> fromKey(String type) {
		if (type == null || type.isBlank()) return Optional.empty();

		return switch (type.trim().toLowerCase(Locale.ROOT)) {
			case "one" -> Optional.of(ONE);
			case "num" -> Optional.of(NUM);
			case "instant" -> Optional.of(INSTANT);
			default -> Optional.empty();
		};
	}

	/**
	 * Thin default-on-unrecognised wrapper around {@link #fromKey(String)} for callers with no {@code ConfigReport}
	 * to warn on.
	 */
	public static ReloadType getType(String type) {
		return fromKey(type).orElse(INSTANT);
	}

	/**
	 * @param amount the per-weapon {@code num-N}/one-shell insertion amount, read off {@link
	 *               org.luckyraven.bartizan.api.weapon.dto.ReloadData#getAmount()} — never shared state on this
	 *               enum constant (BZ-WM-03: it used to be a mutable field here, so every weapon of the same
	 *               {@link ReloadType} silently collided on whichever weapon was parsed last).
	 */
	public Reload createInstance(Weapon weapon, Ammunition ammunition, int amount) {
		return switch (weapon.getReloadData().getType()) {
			case INSTANT -> new InstantReload(weapon, ammunition);
			case ONE, NUM -> new NumberedReload(weapon, ammunition, amount);
		};
	}

}
