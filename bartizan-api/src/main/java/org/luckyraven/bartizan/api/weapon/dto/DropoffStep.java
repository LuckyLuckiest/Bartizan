package org.luckyraven.bartizan.api.weapon.dto;

import org.jetbrains.annotations.Nullable;

/**
 * One entry of a gun's {@code Damage.Dropoff} list — {@code "<distance> <delta>"} (e.g. {@code "20 -2"}). The entry
 * with the largest {@link #distance()} that is still {@code <=} the actual shot distance applies its
 * {@link #delta()} (weapons-roadmap.md gate {@code HF}, §1). Parsed by {@code GunWeaponParser}.
 */
public record DropoffStep(double distance, double delta) {

	/**
	 * Parses one {@code "<distance> <delta>"} entry. Returns {@code null} on any malformed entry (wrong token count,
	 * non-numeric token) — the caller logs a warning and skips it.
	 */
	@Nullable
	public static DropoffStep parse(@Nullable String raw) {
		if (raw == null) return null;

		String[] parts = raw.trim().split("\\s+");
		if (parts.length != 2) return null;

		try {
			return new DropoffStep(Double.parseDouble(parts[0]), Double.parseDouble(parts[1]));
		} catch (NumberFormatException exception) {
			return null;
		}
	}

}
