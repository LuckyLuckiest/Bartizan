package org.luckyraven.bartizan.raytrace;

import org.bukkit.entity.LivingEntity;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;
import org.luckyraven.bartizan.api.weapon.BodyZone;

/**
 * Resolves which body zone a raytrace impact landed in, plus whether the shot came from behind the victim
 * (weapons-roadmap.md gate {@code HF}, §2). Pure with respect to Bukkit's value types ({@link Vector},
 * {@link BoundingBox}) — no scheduler/world side effects — so it is unit-testable with a mocked
 * {@link LivingEntity}.
 *
 * <p>Zone thresholds are relative height {@code (hitY - bbMinY) / bbHeight}, computed against the impact point
 * clamped into the victim's real bounding box (the raw impact point lies on the {@code hitboxExpansion}-inflated
 * detection surface, not the real box): {@code HEAD >= 0.75}, {@code FEET <= 0.12}, {@code LEGS <= 0.35}, else
 * {@code BODY} — unless the hit is far enough side-to-side, measured perpendicular to the horizontal shot
 * direction ({@code >= 0.75} of the half-width), to count as {@code ARMS} instead.
 *
 * <p>{@code back} compares the victim's horizontal facing direction against the shot's horizontal direction — a
 * shot travelling the same way the victim is looking entered through their back. {@code LivingEntity#getLocation()}
 * only carries head yaw, which approximates body yaw; a victim looking sharply sideways from their body can throw
 * this off.
 *
 * <p>{@code zone} is {@link BodyZone} (moved to {@code bartizan-api} at gate {@code HK}) rather than a
 * plugin-local enum, so {@code WeaponEntityDamageEvent}/{@code stats.StatsService} can read it without depending
 * on this plugin-only record.
 */
public record HitZone(BodyZone zone, boolean back) {

	public static HitZone of(Vector impactPt, LivingEntity victim, Vector shotDir) {
		BoundingBox box      = victim.getBoundingBox();
		double      bbHeight = box.getHeight();

		// impactPt comes from World#rayTraceEntities against the hitboxExpansion-inflated box, so it lies ON
		// that inflated surface, not inside the real one — clamp it back into the real box before measuring
		// anything off it, or every mid-height hit reads as far-from-centre (gate HF review finding 1).
		double cx = clamp(impactPt.getX(), box.getMinX(), box.getMaxX()) - box.getCenterX();
		double cy = clamp(impactPt.getY(), box.getMinY(), box.getMaxY());
		double cz = clamp(impactPt.getZ(), box.getMinZ(), box.getMaxZ()) - box.getCenterZ();

		double relHeight = bbHeight > 1e-9 ? (cy - box.getMinY()) / bbHeight : 0.5;

		BodyZone zone;
		if (relHeight >= 0.75) {
			zone = BodyZone.HEAD;
		} else if (relHeight <= 0.12) {
			zone = BodyZone.FEET;
		} else if (relHeight <= 0.35) {
			zone = BodyZone.LEGS;
		} else {
			double halfWidth = Math.max(box.getWidthX(), box.getWidthZ()) / 2.0;

			// Lateral offset perpendicular to the horizontal shot direction, not raw distance from the centre
			// axis — a ray travelling straight through the box is offset mostly along its own direction
			// (depth), which isn't what "far from the centre" (side-to-side) is supposed to measure. Falls
			// back to hypot for a vertical shot, where "horizontal" has no defined direction.
			Vector shotHorizontal = horizontal(shotDir);
			double lateralDist    = shotHorizontal != null
			                        ? Math.abs(cx * shotHorizontal.getZ() - cz * shotHorizontal.getX())
			                        : Math.hypot(cx, cz);
			zone = (halfWidth > 1e-9 && lateralDist >= 0.75 * halfWidth) ? BodyZone.ARMS : BodyZone.BODY;
		}

		return new HitZone(zone, isBackHit(victim, shotDir));
	}

	private static double clamp(double v, double min, double max) {
		return Math.max(min, Math.min(max, v));
	}

	private static boolean isBackHit(LivingEntity victim, Vector shotDir) {
		Vector facing = horizontal(victim.getLocation().getDirection());
		Vector shot   = horizontal(shotDir);
		if (facing == null || shot == null) return false;

		return facing.dot(shot) > 0.5;
	}

	@Nullable
	private static Vector horizontal(Vector v) {
		Vector flattened = new Vector(v.getX(), 0, v.getZ());
		if (flattened.lengthSquared() < 1e-9) return null;

		return flattened.normalize();
	}

}
