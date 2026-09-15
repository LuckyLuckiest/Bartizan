package org.luckyraven.bartizan.raytrace;

import org.luckyraven.bartizan.api.raytrace.WeaponRaytracer;
import org.luckyraven.bartizan.api.raytrace.RaytraceContext;
import org.luckyraven.bartizan.api.raytrace.RaytraceRequest;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;
import org.luckyraven.bartizan.api.weapon.dto.ExplosionData;
import org.luckyraven.bartizan.api.weapon.dto.ProjectileData;
import org.luckyraven.bartizan.api.weapon.dto.SpreadData;
import org.luckyraven.bartizan.api.weapon.dto.VisualData;
import org.luckyraven.bartizan.api.weapon.ProjectileState;
import org.luckyraven.bartizan.api.weapon.ProjectileType;
import org.luckyraven.bartizan.api.weapon.GunWeapon;
import org.luckyraven.bartizan.api.weapon.Weapon;
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
			case BULLET, SPREAD -> fireHitscan(raytracer, shooter, weapon, projectileData);
			case ROCKET, FLARE -> {
				fireSlow(plugin, raytracer, shooter, weapon, projectileData, type, effectRunner);
				yield false;
			}
		};
	}

	private static boolean fireHitscan(WeaponRaytracer raytracer, LivingEntity shooter, GunWeapon weapon,
	                                ProjectileData projectileData) {
		int      pelletCount      = projectileData.getPellets();
		Vector   aimDir           = shooter.getEyeLocation().getDirection();
		Location origin           = shooter.getEyeLocation();
		double   distance         = projectileData.getDistance();
		double   baseDamage       = projectileData.getDamage();
		double   spreadMultiplier = spreadMultiplier(shooter, weapon);

		boolean hitEntity = false;

		for (int i = 0; i < pelletCount; i++) {
			Vector pelletDir = weapon.getSpread().applySpread(aimDir.clone(), spreadMultiplier).normalize();

			RaytraceRequest request = RaytraceRequest.builder()
			                                         .shooter(shooter)
			                                         .weapon(weapon)
			                                         .origin(origin.clone())
			                                         .direction(pelletDir)
			                                         .maxDistance(distance)
			                                         .baseDamage(baseDamage)
			                                         .gravity(projectileData.getGravity())
			                                         .projectileSpeed(projectileData.getSpeed())
			                                         .playFlyby(i == 0)
			                                         .build();

			if (raytracer.fireInstant(request)) hitEntity = true;
		}

		return hitEntity;
	}

	/**
	 * {@code Spread.Modify_Spread_When}: sums the percent deltas for every currently-active condition
	 * (zooming/sneaking/sprinting/midair/swimming) and converts to the multiplier
	 * {@link org.luckyraven.bartizan.api.weapon.spread.SpreadManager#applySpread(Vector, double)} expects, floored
	 * at 0 so a large negative sum can't invert the spread direction.
	 */
	private static double spreadMultiplier(LivingEntity shooter, GunWeapon weapon) {
		SpreadData data = weapon.getSpreadData();
		if (data == null) return 1.0;

		double deltaPercent = 0.0;

		if (weapon.getScopeData() != null && weapon.getScopeData().isScoped()) {
			deltaPercent += data.getZoomingModifier();
		}
		if (shooter instanceof Player player) {
			if (player.isSneaking()) deltaPercent += data.getSneakingModifier();
			if (player.isSprinting()) deltaPercent += data.getSprintingModifier();
		}
		if (!shooter.isOnGround()) deltaPercent += data.getInMidairModifier();
		if (shooter.isSwimming()) deltaPercent += data.getSwimmingModifier();

		return Math.max(0.0, 1 + deltaPercent / 100.0);
	}

	private static void fireSlow(JavaPlugin plugin, WeaponRaytracer raytracer, LivingEntity shooter, GunWeapon weapon,
	                             ProjectileData projectileData, ProjectileType type, EffectRunner effectRunner) {
		Vector   aimDir         = shooter.getEyeLocation().getDirection();
		Location muzzle         = WeaponMuzzle.compute(shooter, aimDir, weapon);
		Vector   spreadDir      = weapon.getSpread().applySpread(aimDir, spreadMultiplier(shooter, weapon)).normalize();
		Vector   launchVelocity = spreadDir.clone().multiply(projectileData.getSpeed());

		VisualData visualData = projectileData.getVisual() != null ? projectileData.getVisual() : defaultVisual(type);
		Entity     visual     = raytracer.getVisualSpawner().spawnVisual(visualData, muzzle, launchVelocity, shooter);
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

		// Projectile.Alive_Ticks (gate HI part b) overrides this historical computed lifetime when configured
		// (> 0); <= 0 means "unset".
		double speedPerTick    = Math.max(0.1, projectileData.getSpeed());
		int    computedMaxTicks = (int) Math.ceil(projectileData.getDistance() / speedPerTick) * 2 + 20;
		int    aliveTicks       = projectileData.getAliveTicks() > 0 ? projectileData.getAliveTicks() : computedMaxTicks;

		// Radius and damage are two distinct config values: Explosion_Radius sizes the blast, Explosion_Damage is
		// the damage dealt at its centre. Reading the radius off the damage produced 50-block rocket blasts.
		// Gate HI-a review fix: reads the unified ExplosionData (mirrors #launch below) instead of the legacy
		// DamageData.explosionRadius/explosionDamage — an Explosion.Radius override under Shoot.Projectile now
		// actually takes effect for rockets.
		ExplosionData ed              = weapon.getExplosionData();
		boolean       explode         = type == ProjectileType.ROCKET && ed.getRadius() > 0;
		double        explosionRadius = explode ? ed.getRadius() : 0;
		double        explosionDamage = explode ? ed.getDamage() : 0;

		new SteppedProjectileTask(plugin, raytracer, raytracer.getVisualSpawner(), visual, muzzle.clone(),
		                          launchVelocity, ctx, explode, explosionRadius, explosionDamage, aliveTicks,
		                          effectRunner, projectileData).start();
	}

	/**
	 * Falls back to the historical hardcoded visual (ROCKET -> fireball, FLARE -> firework) for a
	 * {@code ProjectileData} built without a {@code Visual:} — {@code GunWeaponParser} always fills one in, so
	 * this only guards bare test fixtures/older code paths.
	 */
	private static VisualData defaultVisual(ProjectileType type) {
		VisualData.VisualType visualType = type == ProjectileType.FLARE
		                                    ? VisualData.VisualType.FIREWORK
		                                    : VisualData.VisualType.FIREBALL;
		return new VisualData(visualType, null, 0, null);
	}

	/**
	 * Gate {@code HI-a}: launches a single stepped projectile from an explicit {@code origin}/{@code direction}
	 * rather than the shooter's eye — {@code ExplosionHandler} uses this to spawn cluster/airstrike sub-munitions.
	 * Mirrors {@link #fireSlow}. A sub-munition inherits the parent gun's {@code Projectile} block (visual,
	 * gravity, drag, bounce, trail) so it flies like the parent; a throwable parent has no such block, so it gets
	 * a bare fireball. Deals no direct hit damage of its own ({@code baseDamage: 0}) — a sub-munition's only
	 * payload is the explosion it carries — and never plays its own fly-by sound (the parent explosion already did).
	 */
	public static void launch(JavaPlugin plugin, WeaponRaytracer raytracer, LivingEntity shooter, Weapon weapon,
	                          Location origin, Vector direction, EffectRunner effectRunner, int depth) {
		Vector unitDir = direction.clone().normalize();

		ProjectileData projectileData = weapon instanceof GunWeapon gun
		                                ? gun.getProjectileData()
		                                : ProjectileData.builder().type(ProjectileType.ROCKET).speed(direction.length()).build();
		VisualData visualData = projectileData.getVisual() != null
		                        ? projectileData.getVisual()
		                        : defaultVisual(ProjectileType.ROCKET);
		Entity visual = raytracer.getVisualSpawner().spawnVisual(visualData, origin, direction.clone(), shooter);
		if (visual == null) {
			return;
		}

		double gravity = projectileData.getGravity();

		RaytraceRequest request = RaytraceRequest.builder()
		                                         .shooter(shooter)
		                                         .weapon(weapon)
		                                         .origin(origin.clone())
		                                         .direction(unitDir)
		                                         .maxDistance(64.0)
		                                         .baseDamage(0.0)
		                                         .gravity(gravity)
		                                         .playFlyby(false)
		                                         .build();

		ProjectileState state = new ProjectileState(weapon, 0.0);
		state.setDepth(depth);
		RaytraceContext ctx = new RaytraceContext(request, state);

		ExplosionData explosionData = SteppedProjectileTask.explosionDataOf(weapon);
		boolean explode         = explosionData != null && explosionData.getRadius() > 0;
		double  explosionRadius = explode ? explosionData.getRadius() : 0;
		double  explosionDamage = explode ? explosionData.getDamage() : 0;

		new SteppedProjectileTask(plugin, raytracer, raytracer.getVisualSpawner(), visual, origin.clone(),
		                          direction.clone(), ctx, explode, explosionRadius, explosionDamage, 200, effectRunner,
		                          projectileData).start();
	}

}
