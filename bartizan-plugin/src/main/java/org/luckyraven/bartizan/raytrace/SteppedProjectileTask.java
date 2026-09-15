package org.luckyraven.bartizan.raytrace;

import org.luckyraven.bartizan.api.raytrace.WeaponRaytracer;
import org.luckyraven.bartizan.api.raytrace.RaytraceContext;
import org.luckyraven.bartizan.api.raytrace.WeaponVisualSpawner;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;
import org.luckyraven.bartizan.api.weapon.GunWeapon;
import org.luckyraven.bartizan.api.weapon.ThrowableWeapon;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.api.weapon.dto.ExplosionData;
import org.luckyraven.bartizan.api.weapon.dto.ExplosionData.Detonation;
import org.luckyraven.bartizan.api.weapon.dto.ExplosionData.Trigger;
import org.luckyraven.bartizan.api.weapon.dto.SoundData;
import org.luckyraven.bartizan.effect.EffectRunner;
import org.luckyraven.keystone.sound.SoundEffect;
import org.luckyraven.keystone.timer.CountdownTimer;
import org.luckyraven.keystone.timer.RepeatingTimer;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Per-tick driver for slow visual projectiles (rockets, flares, throwables). Wraps a cosmetic Bukkit projectile entity
 * that handles motion via Spigot's normal physics, and calls {@link WeaponRaytracer#advanceSegment} once per tick to
 * perform hit detection along the segment the visual moved through.
 * <p>
 * Decouples hit detection from Spigot's projectile entity lifecycle: even if Spigot kills the visual on first block
 * contact, this task catches the impact during the segment scan that preceded the kill, so penetration and ricochet
 * still work consistently.
 * <p>
 * On terminal impact (the ray runs out of distance, iterations, or hits a non-penetrable target), the task removes the
 * visual and — if {@code explodeOnTerminate} is set — hands off to {@link #onImpact(Location)}, which applies the
 * weapon's {@code Detonation} rules and, via the unified {@code ExplosionHandler} (gate {@code HI-a}), fires the AOE
 * explosion at the impact point.
 */
public class SteppedProjectileTask {

	private final JavaPlugin          plugin;
	private final WeaponRaytracer     raytracer;
	private final WeaponVisualSpawner visualSpawner;
	private final Projectile          visual;
	private final RaytraceContext     ctx;
	private final boolean             explodeOnTerminate;
	private final double              explosionRadius;
	private final double              explosionDamage;
	private final int                 maxTicks;
	private final EffectRunner        effectRunner;

	private Location         lastLoc;
	private RepeatingTimer   timer;
	private int              tickCounter;
	private boolean          finished;
	private final Set<UUID>  flybyNotified = new HashSet<>();

	public SteppedProjectileTask(JavaPlugin plugin, WeaponRaytracer raytracer, WeaponVisualSpawner visualSpawner,
	                             Projectile visual, RaytraceContext ctx, boolean explodeOnTerminate,
	                             double explosionRadius, double explosionDamage, int maxTicks,
	                             EffectRunner effectRunner) {
		this.plugin             = plugin;
		this.raytracer          = raytracer;
		this.visualSpawner      = visualSpawner;
		this.visual             = visual;
		this.ctx                = ctx;
		this.explodeOnTerminate = explodeOnTerminate;
		this.explosionRadius    = explosionRadius;
		this.explosionDamage    = explosionDamage;
		this.maxTicks           = maxTicks;
		this.effectRunner       = effectRunner;
		this.lastLoc            = visual.getLocation();
		this.tickCounter        = 0;
		this.finished           = false;
	}

	public void start() {
		timer = new RepeatingTimer(plugin, 1L, task -> {
			if (finished) {
				task.stop();
				return;
			}

			if (++tickCounter > maxTicks) {
				terminate(visual.getLocation());
				task.stop();
				return;
			}

			// Spigot may have killed the visual on a block contact this tick — fall back to the
			// last known position so the explosion still happens at the right spot.
			if (visual.isDead() || !visual.isValid()) {
				terminate(lastLoc);
				task.stop();
				return;
			}

			Location currentLoc = visual.getLocation();
			Vector   segment    = currentLoc.toVector().subtract(lastLoc.toVector());

			// No movement this tick (e.g. fireball stuck on first frame) — wait for next tick.
			if (segment.lengthSquared() < 1e-6) {
				return;
			}

			raytracer.advanceSegment(ctx, lastLoc, currentLoc);
			checkFlyby(lastLoc, currentLoc);

			// Ray exhausted: either the loop's distance budget is gone, the iteration cap was hit,
			// or a non-penetrable target stopped it. Pick the last tracer point as the impact site
			// and fire the AOE.
			if (ctx.getRemaining() <= 0) {
				Location impactPoint = lastTracerPoint();
				if (impactPoint == null) {
					impactPoint = currentLoc;
				}
				terminate(impactPoint);
				task.stop();
				return;
			}

			lastLoc = currentLoc;
		});
		timer.start(false);
	}

	/**
	 * {@code Shoot.Sound.Flyby_*} for ROCKET/FLARE: same proximity check {@code WeaponRaytracerImpl.playFlybySounds}
	 * runs for hitscan, applied to just the segment travelled this tick since a slow projectile has no full
	 * polyline up front. Each player is notified at most once per projectile (gate {@code HE} part b review
	 * item (g)).
	 */
	private void checkFlyby(Location from, Location to) {
		SoundData sounds = ctx.getRequest().getWeapon().getSoundData();
		if (sounds == null || sounds.getFlybyRange() <= 0) return;

		SoundEffect sound = sounds.getFlybyCustom() != null ? sounds.getFlybyCustom() : sounds.getFlybyDefault();
		if (sound == null) return;

		World world = from.getWorld();
		if (world == null) return;

		double       range   = sounds.getFlybyRange();
		LivingEntity shooter = ctx.getRequest().getShooter();
		for (Player player : world.getPlayers()) {
			if (player.equals(shooter) || flybyNotified.contains(player.getUniqueId())) continue;

			double dist = WeaponRaytracerImpl.pointToSegmentDistance(
					player.getEyeLocation().toVector(), from.toVector(), to.toVector());
			if (dist <= range) {
				sound.playSound(player);
				flybyNotified.add(player.getUniqueId());
			}
		}
	}

	private Location lastTracerPoint() {
		List<Location> segments = ctx.getTracerSegments();
		if (segments.isEmpty()) {
			return null;
		}
		return segments.get(segments.size() - 1);
	}

	private void terminate(Location impactLocation) {
		if (finished) {
			return;
		}
		finished = true;

		visualSpawner.unregisterCosmetic(visual.getEntityId());
		if (!visual.isDead() && visual.isValid()) {
			visual.remove();
		}

		// Gate HI-a seam: HI-b's future terminate(Location, boolean, ImpactKind) calls onImpact(Location) itself
		// when it decides an explosion is allowed. Until that lands, route the current gate here directly so
		// rockets keep exploding on every terminal impact exactly as before.
		if (explodeOnTerminate && explosionRadius > 0 && impactLocation != null) {
			onImpact(impactLocation);
		}
	}

	/**
	 * Gate {@code HI-a} — applies the weapon's {@code Detonation} rules for this impact and, if they allow it,
	 * explodes. This is the seam {@code terminate(Location, boolean)} (gate {@code HI-b}) will call once it lands;
	 * for now {@link #terminate(Location)} above routes here directly.
	 * <p>
	 * {@code HI-b}'s {@code terminate} does not yet tell this method whether the impact was a block or an entity,
	 * so every impact is treated as satisfying both {@link Trigger#BLOCK} and {@link Trigger#ENTITY} — narrow this
	 * once that information reaches here. A configured {@code Detonation.Fuse_Ticks} (explode after N ticks with
	 * no impact at all) is not wired for this gun/rocket path — nothing in this class runs independently of the
	 * tick loop {@code start()} owns; throwables get the equivalent behaviour from {@code ThrowableAction}'s own
	 * fuse timer instead.
	 */
	void onImpact(Location impact) {
		ExplosionData data = explosionDataOf(ctx.getRequest().getWeapon());
		if (data == null || data.getRadius() <= 0) return;

		Detonation detonation = data.getDetonation();
		boolean impactQualifies = detonation == null
		                          || detonation.impactWhen().contains(Trigger.BLOCK)
		                          || detonation.impactWhen().contains(Trigger.ENTITY);
		if (!impactQualifies) return;

		int delay = detonation != null ? detonation.delayAfterImpactTicks() : 0;
		if (delay > 0) {
			new CountdownTimer(plugin, 0L, 1L, delay, null, null, expired -> fireExplosion(impact)).start(false);
		} else {
			fireExplosion(impact);
		}
	}

	/**
	 * Thin call into the unified {@code ExplosionHandler} (gate {@code HI-a}) — replaces the old hand-rolled
	 * linear-falloff/no-block-damage explosion this class used to do inline.
	 */
	private void fireExplosion(Location loc) {
		ExplosionHandler handler = ExplosionHandler.get();
		if (handler == null) return;

		Weapon        weapon = ctx.getRequest().getWeapon();
		ExplosionData data   = explosionDataOf(weapon);
		if (data == null) return;

		handler.explode(weapon, data, loc, ctx.getRequest().getShooter(), ctx.getState().getDepth());
	}

	/**
	 * Resolves the {@code ExplosionData} carried by {@code weapon}, regardless of whether it's a gun (rocket) or a
	 * throwable (a cluster/airstrike sub-munition {@code ExplosionHandler} spawns via {@code WeaponShooting#launch}
	 * may carry either).
	 */
	@Nullable
	static ExplosionData explosionDataOf(Weapon weapon) {
		if (weapon instanceof GunWeapon gun) return gun.getExplosionData();
		if (weapon instanceof ThrowableWeapon throwable) return throwable.getExplosionData();
		return null;
	}

}
