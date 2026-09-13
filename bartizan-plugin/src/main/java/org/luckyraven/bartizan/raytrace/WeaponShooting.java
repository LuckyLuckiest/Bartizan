package org.luckyraven.bartizan.raytrace;

import org.luckyraven.bartizan.api.raytrace.WeaponRaytracer;
import org.luckyraven.bartizan.api.raytrace.RaytraceContext;
import org.luckyraven.bartizan.api.raytrace.RaytraceRequest;

import org.bukkit.Location;
import org.bukkit.entity.Fireball;
import org.bukkit.entity.Firework;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Projectile;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;
import org.luckyraven.bartizan.api.weapon.dto.DamageData;
import org.luckyraven.bartizan.api.weapon.dto.ProjectileData;
import org.luckyraven.bartizan.api.weapon.ProjectileState;
import org.luckyraven.bartizan.api.weapon.ProjectileType;
import org.luckyraven.bartizan.api.weapon.GunWeapon;
import org.luckyraven.bartizan.effect.EffectRunner;

/**
 * Shared dispatch helper that fires a {@link GunWeapon} through the unified weapon raytracer, regardless of whether the
 * shooter is a {@link org.bukkit.entity.Player} or an NPC. Routes BULLET / SPREAD through
 * {@link WeaponRaytracer#fireInstant} and ROCKET / FLARE through a {@link SteppedProjectileTask} that drives a cosmetic
 * Bukkit projectile entity.
 * <p>
 * Both {@code GunAction} (player firing path) and {@code NpcWeaponControllerImpl} (NPC firing path)
 * call this helper so the dispatch logic stays in one place. Player-specific concerns (durability decrement, recoil,
 * sound to shooter, item update) live in the callers.
 */
public final class WeaponShooting {

	/**
	 * Default pellet count for SPREAD weapons. Mirrors the hardcoded value in legacy {@code Spread}.
	 */
	public static final int SPREAD_PELLET_COUNT = 8;

	private WeaponShooting() {
	}

	/**
	 * @return {@code true} for the two hitscan projectile types ({@link ProjectileType#BULLET},
	 * 		{@link ProjectileType#SPREAD}) that resolve their hit synchronously via {@link WeaponRaytracer#fireInstant}
	 * 		— {@code false} for {@link ProjectileType#ROCKET}/{@link ProjectileType#FLARE}, whose hit resolves later
	 * 		through {@code SteppedProjectileTask}. Callers use this to decide whether firing {@code ON_MISS} off
	 * 		{@link #fire}'s return value makes sense for a given weapon.
	 */
	public static boolean isHitscan(ProjectileType type) {
		return type == ProjectileType.BULLET || type == ProjectileType.SPREAD;
	}

	/**
	 * Fires the given gun weapon from the given shooter through the unified raytracer.
	 *
	 * @return {@code true} if a living entity took the hit (always {@code false} for the slow-projectile path —
	 * 		its hit resolves later, asynchronously, via {@code SteppedProjectileTask}).
	 */
	public static boolean fire(JavaPlugin plugin, WeaponRaytracer raytracer, LivingEntity shooter, GunWeapon weapon,
	                        EffectRunner effectRunner) {
		ProjectileData projectileData = weapon.getProjectileData();
		ProjectileType type           = projectileData.getType();

		return switch (type) {
			case BULLET, SPREAD -> fireHitscan(raytracer, shooter, weapon, projectileData, type);
			case ROCKET, FLARE -> {
				fireSlow(plugin, raytracer, shooter, weapon, projectileData, type, effectRunner);
				yield false;
			}
		};
	}

	private static boolean fireHitscan(WeaponRaytracer raytracer, LivingEntity shooter, GunWeapon weapon,
	                                ProjectileData projectileData, ProjectileType type) {
		int      pelletCount = type == ProjectileType.SPREAD ? SPREAD_PELLET_COUNT : 1;
		Vector   aimDir      = shooter.getEyeLocation().getDirection();
		Location origin      = shooter.getEyeLocation();
		double   distance    = projectileData.getDistance();
		double   baseDamage  = projectileData.getDamage();

		boolean hitEntity = false;

		for (int i = 0; i < pelletCount; i++) {
			Vector pelletDir = weapon.getSpread().applySpread(aimDir.clone()).normalize();

			RaytraceRequest request = RaytraceRequest.builder()
			                                         .shooter(shooter)
			                                         .weapon(weapon)
			                                         .origin(origin.clone())
			                                         .direction(pelletDir)
			                                         .maxDistance(distance)
			                                         .baseDamage(baseDamage)
			                                         .gravity(projectileData.getGravity())
			                                         .projectileSpeed(projectileData.getSpeed())
			                                         .build();

			if (raytracer.fireInstant(request)) hitEntity = true;
		}

		return hitEntity;
	}

	private static void fireSlow(JavaPlugin plugin, WeaponRaytracer raytracer, LivingEntity shooter, GunWeapon weapon,
	                             ProjectileData projectileData, ProjectileType type, EffectRunner effectRunner) {
		Vector   aimDir         = shooter.getEyeLocation().getDirection();
		Location muzzle         = WeaponMuzzle.compute(shooter, aimDir);
		Vector   spreadDir      = weapon.getSpread().applySpread(aimDir).normalize();
		Vector   launchVelocity = spreadDir.clone().multiply(projectileData.getSpeed());

		Class<? extends Projectile> visualClass = type == ProjectileType.ROCKET ? Fireball.class : Firework.class;
		Projectile visual = raytracer.getVisualSpawner()
		                             .spawnCosmetic(visualClass, shooter, muzzle, launchVelocity);
		if (visual == null) {
			return;
		}

		RaytraceRequest request = RaytraceRequest.builder()
		                                         .shooter(shooter)
		                                         .weapon(weapon)
		                                         .origin(shooter.getEyeLocation())
		                                         .direction(spreadDir)
		                                         .maxDistance(projectileData.getDistance())
		                                         .baseDamage(projectileData.getDamage())
		                                         .build();

		ProjectileState state = new ProjectileState(weapon);
		RaytraceContext ctx   = new RaytraceContext(request, state);

		double speedPerTick = Math.max(0.1, projectileData.getSpeed());
		int    maxTicks     = (int) Math.ceil(projectileData.getDistance() / speedPerTick) * 2 + 20;

		DamageData damageData = weapon.getDamageData();

		// Radius and damage are two distinct config values: Explosion_Radius sizes the blast, Explosion_Damage is
		// the damage dealt at its centre. Reading the radius off the damage produced 50-block rocket blasts.
		boolean explode         = type == ProjectileType.ROCKET;
		double  explosionRadius = explode ? damageData.getExplosionRadius() : 0;
		double  explosionDamage = explode ? damageData.getExplosionDamage() : 0;

		new SteppedProjectileTask(plugin, raytracer, raytracer.getVisualSpawner(), visual, ctx, explode,
		                          explosionRadius, explosionDamage, maxTicks, effectRunner).start();
	}

}
