package org.luckyraven.bartizan.raytrace;

import org.luckyraven.bartizan.api.raytrace.WeaponRaytracer;
import org.luckyraven.bartizan.api.raytrace.RaytraceContext;
import org.luckyraven.bartizan.api.raytrace.WeaponVisualSpawner;

import com.cryptomorin.xseries.particles.XParticle;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;
import org.luckyraven.bartizan.api.weapon.GunWeapon;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.api.weapon.dto.DamageData;
import org.luckyraven.bartizan.api.weapon.dto.EffectHook;
import org.luckyraven.bartizan.api.weapon.dto.SoundData;
import org.luckyraven.bartizan.api.weapon.modifiers.DamageMath;
import org.luckyraven.bartizan.effect.EffectContext;
import org.luckyraven.bartizan.effect.EffectRunner;
import org.luckyraven.bartizan.weapon.DamageRules;
import org.luckyraven.keystone.sound.SoundEffect;
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
 * visual and — if {@code explodeOnTerminate} is set — fires an AOE explosion at the impact point with linear damage
 * falloff. Mirrors the legacy {@code ProjectileDamageListener#explosiveProjectile} behaviour for backwards
 * compatibility with rocket weapons.
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

		if (explodeOnTerminate && explosionRadius > 0 && impactLocation != null) {
			fireExplosion(impactLocation);
		}
	}

	/**
	 * Linear damage falloff from the blast centre. {@code explosionDamage} is dealt at distance 0 and tapers to 0 at
	 * {@code explosionRadius}; anything outside the radius (or a weapon that configured no blast) takes nothing.
	 */
	static double falloffDamage(double explosionDamage, double explosionRadius, double distance) {
		if (explosionDamage <= 0 || explosionRadius <= 0 || distance >= explosionRadius) {
			return 0;
		}
		return explosionDamage * (1 - (distance / explosionRadius));
	}

	/**
	 * Adds a {@code Damage.Knockback} falloff vector (gate HF, §6) pointing away from the blast centre. A no-op
	 * when {@code knockback} is {@code null} (unconfigured) or the falloff factor is {@code 0}.
	 */
	private void applyExplosionKnockback(LivingEntity target, Location center, double dist, @Nullable Double knockback) {
		if (knockback == null) return;

		double factor = DamageMath.explosionKnockbackFactor(knockback, dist, explosionRadius);
		if (factor <= 0) return;

		Vector direction = dist > 1e-6
		                    ? target.getLocation().toVector().subtract(center.toVector()).normalize()
		                    : new Vector(0, 1, 0);
		target.setVelocity(target.getVelocity().add(direction.multiply(factor)));
	}

	private void fireExplosion(Location loc) {
		World world = loc.getWorld();
		if (world == null) {
			return;
		}

		Weapon weapon = ctx.getRequest().getWeapon();
		// SteppedProjectileTask only ever drives guns (bullets/rockets) — DamageData lives on GunWeapon.
		DamageData damageData = weapon instanceof GunWeapon gun ? gun.getDamageData() : null;
		Double     knockback  = damageData != null ? damageData.getKnockback() : null;

		LivingEntity shooter = ctx.getRequest().getShooter();
		for (Entity entity : world.getNearbyEntities(loc, explosionRadius, explosionRadius, explosionRadius)) {
			if (!(entity instanceof LivingEntity target)) continue;

			// Damage.Owner_Immunity / Ignore_Teams (gate HF, §4) replace the old hardcoded shooter skip.
			boolean protectedTarget = damageData != null
			                          ? DamageRules.isProtected(damageData, shooter, target)
			                          : target.equals(shooter);
			if (protectedTarget) continue;

			double dist   = target.getLocation().distance(loc);
			double damage = falloffDamage(explosionDamage, explosionRadius, dist);
			if (damage > 0) {
				// Without this flag, WeaponInteract.onEntityDamage cancels the damage whenever the shooter
				// still holds a weapon — mirrors WeaponRaytracerImpl.handleEntityImpact's guard exactly.
				WeaponRaytracer.setRaytraceDamageInProgress(true);
				try {
					target.damage(damage, shooter);
				} finally {
					WeaponRaytracer.setRaytraceDamageInProgress(false);
				}
				applyExplosionKnockback(target, loc, dist, knockback);
			}
		}

		world.spawnParticle(XParticle.EXPLOSION.get(), loc, 5, 0.5, 0.5, 0.5, 0.1);
		world.spawnParticle(XParticle.SMOKE.get(), loc, 30, 1.0, 1.0, 1.0, 0.1);
		world.spawnParticle(XParticle.FLAME.get(), loc, 20, 1.0, 1.0, 1.0, 0.1);

		EffectContext effectCtx = EffectContext.builder()
		                                       .weapon(ctx.getRequest().getWeapon())
		                                       .source(shooter)
		                                       .impact(loc)
		                                       .build();
		effectRunner.run(ctx.getRequest().getWeapon(), EffectHook.ON_EXPLODE, effectCtx);
	}

}
