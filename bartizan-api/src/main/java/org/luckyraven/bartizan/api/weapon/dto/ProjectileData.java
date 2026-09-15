package org.luckyraven.bartizan.api.weapon.dto;

import lombok.Builder;
import lombok.Getter;
import org.bukkit.Particle;
import org.jetbrains.annotations.Nullable;
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
	/**
	 * {@code Projectile.Particle}: draws the weapon's {@code Modifiers.Tracer} (or the default gray dust line when
	 * none is configured) along each tick's travelled segment for a stepped slow projectile (gate {@code HI} part
	 * b). Dead before that gate — parsed but never read.
	 */
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

	/**
	 * {@code Projectile.Drag}: fractional speed loss applied to a stepped slow projectile's velocity every tick,
	 * after gravity (gate {@code HI} part b). {@code 0.0} (default) — no drag, matching pre-{@code HI-b} flight.
	 */
	@Builder.Default
	private final double         drag = 0.0;

	/**
	 * {@code Projectile.Visual}: which cosmetic entity the stepped-flight task drives. {@code null} only for
	 * {@code ProjectileData} instances built outside {@code GunWeaponParser} (e.g. bare test fixtures) — the
	 * parser always fills in the type-based default ({@code ROCKET} -> fireball, {@code FLARE} -> firework) when
	 * the YAML omits {@code Visual:} entirely.
	 */
	@Nullable
	private final VisualData     visual;

	/**
	 * {@code Projectile.Bouncy}: {@code null} means every block hit terminates the projectile — the historical,
	 * pre-{@code HI-b} behaviour.
	 */
	@Nullable
	private final BouncyData     bouncy;

	/**
	 * {@code Projectile.Extinguish_In_Water}: terminates (without exploding) the instant the projectile's current
	 * block is a liquid. Defaults {@code false} — water doesn't stop a rocket/flare unless configured to.
	 */
	private final boolean        extinguishInWater;

	/**
	 * {@code Projectile.Alive_Ticks}: overrides the stepped task's flight lifetime. {@code <= 0} (the default)
	 * means "unset" — the caller ({@code WeaponShooting.fireSlow}) falls back to the historical computed value
	 * ({@code ceil(distance / speedPerTick) * 2 + 20}).
	 */
	private final int            aliveTicks;

	/**
	 * {@code Projectile.Trail}: a particle spawned once at the projectile's position every tick, independent of
	 * the {@link #particle} tracer line. {@code null} (default) — no trail.
	 */
	@Nullable
	private final Particle       trail;

	@Override
	public String toString() {
		return String.format(
				"ProjectileData{speed=%.2f,type=%s,damage=%.2f,consumed=%d,perShot=%d,cooldown=%d,distance=%d,"
				+ "particle=%b,gravity=%.4f,pellets=%d,drag=%.4f,aliveTicks=%d,extinguishInWater=%b}",
				speed, type, damage, consumed, perShot, cooldown, distance, particle, gravity, pellets, drag,
				aliveTicks, extinguishInWater);
	}

}
