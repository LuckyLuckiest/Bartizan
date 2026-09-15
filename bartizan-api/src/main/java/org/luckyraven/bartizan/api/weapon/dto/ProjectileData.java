package org.luckyraven.bartizan.api.weapon.dto;

import lombok.Builder;
import lombok.Getter;
import org.luckyraven.bartizan.api.weapon.ProjectileType;

@Getter
@Builder
public class ProjectileData {

	private final double         speed;
	private final ProjectileType type;
	private final double         damage;
	private final int            consumed;
	private final int            perShot;
	private final int            cooldown;
	private final int            distance;
	private final boolean        particle;
	@Builder.Default
	private final double         gravity = 0.0;
	/**
	 * Pellets fired per shot — the hitscan "burst" count, historically hardcoded to 8 for
	 * {@link ProjectileType#SPREAD} and 1 for every other type. {@code Projectile.Pellets} in YAML now configures
	 * it directly ({@code GunWeaponParser} still applies that same type-based default); defaults to 1 here for any
	 * caller that never sets it explicitly (test fixtures, older code paths).
	 */
	@Builder.Default
	private final int            pellets = 1;

	@Override
	public String toString() {
		return String.format(
				"ProjectileData{speed=%.2f,type=%s,damage=%.2f,consumed=%d,perShot=%d,cooldown=%d,distance=%d,particle=%b,gravity=%.4f,pellets=%d}",
				speed, type, damage, consumed, perShot, cooldown, distance, particle, gravity, pellets);
	}

}
