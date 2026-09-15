package org.luckyraven.bartizan.api.weapon.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.jetbrains.annotations.Nullable;
import org.luckyraven.keystone.exception.PluginException;

import java.util.Set;

/**
 * Unified AOE-explosion configuration shared by guns (rockets) and throwables (grenades) — gate {@code HI-a}
 * ("explosion parity") replaces the two divergent hand-rolled explosion paths ({@code SteppedProjectileTask
 * .fireExplosion}, {@code ThrowableAction.detonate}'s explosive branch) with one {@code ExplosionHandler} that
 * reads only this DTO. {@code GunWeapon}/{@code ThrowableWeapon} each carry their own instance
 * ({@code getExplosionData()}); the legacy {@code Damage.Explosion_*}/{@code Throw.Explosion_*} keys are lowered
 * into it by {@code ExplosionSectionParser} so every existing weapon file behaves identically by default.
 * <p>
 * Records nested here ({@link Cluster}, {@link Airstrike}, {@link Detonation}) are immutable value objects, so
 * {@link #clone()} is a plain shallow {@code Object.clone()} — safe to share the same record instances between the
 * original and the clone.
 */
@Data
@NoArgsConstructor
public class ExplosionData implements Cloneable {

	private double radius;
	private double damage;
	private int    fireTicks;
	private Shape    shape    = Shape.SPHERE;
	private Exposure exposure = Exposure.DISTANCE;

	/**
	 * {@code Block_Damage} — when {@code true}, the blast also chips/breaks nearby blocks through
	 * {@code BlockDamageManager}. Defaults {@code false}: neither legacy path ever broke blocks.
	 */
	private boolean blockDamage;

	/**
	 * {@code null} adds no knockback; a present value (0 included) is a falloff vector magnitude at the blast
	 * centre, tapering to 0 at {@link #radius} — see {@code DamageMath#explosionKnockbackFactor}.
	 */
	@Nullable
	private Double knockback;

	private boolean ownerImmunity;
	private boolean ignoreTeams;

	/**
	 * Sub-munitions launched outward from the blast centre on detonation. {@code null} (default) spawns none.
	 * Only honoured at raytrace depth 0 — a cluster bomblet's own {@code ExplosionData} carries a {@code null}
	 * {@link #cluster}/{@link #airstrike} in practice since the spawner never recurses past depth 1.
	 */
	@Nullable
	private Cluster cluster;

	/**
	 * Sub-munitions dropped from above the blast centre on detonation. {@code null} (default) spawns none. See
	 * {@link #cluster} for the depth-0-only recursion guard.
	 */
	@Nullable
	private Airstrike airstrike;

	/**
	 * When this explosion actually goes off relative to a projectile's flight. {@code null} is treated the same as
	 * an empty-impact/no-fuse {@link Detonation} by the handler, but {@code ExplosionSectionParser} always fills
	 * this in with the caller's legacy-parity default, so it is never actually {@code null} coming out of a
	 * parsed weapon file.
	 */
	@Nullable
	private Detonation detonation;

	@Override
	public ExplosionData clone() {
		try {
			return (ExplosionData) super.clone();
		} catch (CloneNotSupportedException exception) {
			throw new PluginException(exception);
		}
	}

	/**
	 * AOE falloff shape — {@code ExplosionMath#damageAt} resolves the actual distance metric per shape (Euclidean
	 * for {@link #SPHERE}/{@link #FLAT}, Chebyshev for {@link #CUBE}). {@code ExplosionSectionParser}'s legacy
	 * lowering defaults guns to {@link #SPHERE} (the old rocket falloff) and throwables to {@link #FLAT} (the old
	 * grenade behaviour — full {@code damage} anywhere inside {@code radius}, no vanilla blast stacked on top).
	 */
	public enum Shape {
		SPHERE,
		CUBE,
		/** Full {@code damage} anywhere inside {@code radius}, {@code 0} outside — no linear taper. */
		FLAT
	}

	/**
	 * Whether a block between the blast centre and a candidate victim blocks the damage entirely.
	 */
	public enum Exposure {
		DISTANCE,
		LINE_OF_SIGHT
	}

	/**
	 * {@code count} sub-projectiles launched from the blast centre in random upward directions at {@code speed}
	 * (blocks/tick); each is spawned after a {@code delayTicks} stagger and then behaves like any other stepped
	 * projectile — detonating on its own impact, with {@code delayTicks} otherwise acting as nothing more than the
	 * launch stagger (see {@code ExplosionHandler}).
	 */
	public record Cluster(int count, double speed, int delayTicks) {
	}

	/**
	 * {@code count} projectiles spawned {@code height} blocks above random points within {@code radius} of the
	 * blast centre, launched straight down, after a {@code delayTicks} stagger.
	 */
	public record Airstrike(int count, double height, double radius, int delayTicks) {
	}

	/**
	 * Controls when a projectile carrying this {@code ExplosionData} actually detonates.
	 *
	 * @param impactWhen which impact kinds trigger detonation. Empty means "never on impact" (a throwable that
	 * 		relies purely on its fuse, the historical default).
	 * @param delayAfterImpactTicks once an impact qualifies, wait this many extra ticks before actually exploding
	 * 		(the projectile/grenade freezes in place). {@code 0} explodes immediately.
	 * @param fuseTicks explode after this many ticks have elapsed regardless of impact, {@code 0} disables the
	 * 		fuse entirely. Guns default this to {@code 0} (no fuse; legacy rockets only ever explode on impact);
	 * 		throwables default it to their configured {@code Fuse_Time}.
	 */
	public record Detonation(Set<Trigger> impactWhen, int delayAfterImpactTicks, int fuseTicks) {
	}

	/**
	 * One impact kind a {@link Detonation#impactWhen()} set can name. {@code SPAWN} is reserved for a future
	 * "detonate the instant it's created" use case — nothing produces it yet.
	 */
	public enum Trigger {
		BLOCK,
		ENTITY,
		SPAWN
	}

}
