package org.luckyraven.bartizan.api.raytrace;

import org.bukkit.Location;

/**
 * Unified server-side raytracer used by every weapon action (gun, incendiary, biological, melee, throwable)
 * (bartizan.md §1.6(7)). The runtime is {@code WeaponRaytracerImpl}, holding {@code WeaponManager},
 * {@code WearableAddon}, {@code BlockDamageManager} and {@link WeaponVisualSpawner} - it cannot live in
 * {@code bartizan-api} without dragging the whole runtime with it. This interface carries only the four members
 * every consumer (and this same module's {@code WeaponShooting}) actually calls.
 *
 * <p>The {@code ServicesManager} key stays this interface, matching today's
 * {@code getRegistration(WeaponRaytracer.class)} call shape for third-party consumers (bartizan.md §C.3).
 */
public interface WeaponRaytracer {

	/**
	 * True while a {@code WeaponRaytracer} implementation is inside a {@code LivingEntity#damage} call it triggered
	 * itself. Listeners that handle {@code EntityDamageByEntityEvent} for weapon-system reactions check this flag
	 * and skip - they receive the canonical signal via {@code WeaponRaytraceImpactEvent} instead, so they would
	 * otherwise double-process the same shot.
	 */
	static boolean isRaytraceDamageInProgress() {
		return RaytraceDamageFlag.THREAD_LOCAL.get();
	}

	/**
	 * For {@code WeaponRaytracer} implementations only - flips the flag {@link #isRaytraceDamageInProgress()}
	 * reads, around the implementation's own {@code LivingEntity#damage} call.
	 */
	static void setRaytraceDamageInProgress(boolean inProgress) {
		RaytraceDamageFlag.THREAD_LOCAL.set(inProgress);
	}

	/** The cosmetic-projectile spawner this raytracer's stepped (slow-projectile) path uses. */
	WeaponVisualSpawner getVisualSpawner();

	/**
	 * Runs the full hitscan loop for a single ray, synchronously, until the ray stops (no more penetration, no more
	 * ricochet, no more distance). The supplied request must carry an origin already at the muzzle position - see
	 * {@code WeaponMuzzle#compute}.
	 *
	 * @return {@code true} if a living entity took the hit (the impact event was not cancelled and, on the default
	 * 		damage path, the damage was not blocked). Callers use this to decide whether to fire {@code ON_MISS}
	 * 		themselves - the raytracer no longer fires it.
	 */
	boolean fireInstant(RaytraceRequest request);

	/**
	 * Runs the loop over exactly one segment of travel - from {@code from} to {@code to}. Used by stepped slow
	 * projectiles whose visual entity moved a small amount during the current tick. The context must persist across
	 * calls so penetration / ricochet counters carry over.
	 */
	void advanceSegment(RaytraceContext ctx, Location from, Location to);

	/** Holder so the ThreadLocal backing the flag is never a directly-mutable public interface field. */
	final class RaytraceDamageFlag {

		private static final ThreadLocal<Boolean> THREAD_LOCAL = ThreadLocal.withInitial(() -> Boolean.FALSE);

		private RaytraceDamageFlag() {
		}
	}

}
