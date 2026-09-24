package org.luckyraven.bartizan.raytrace;

import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;
import org.luckyraven.bartizan.api.event.WeaponEntityDamageEvent;
import org.luckyraven.bartizan.api.event.WeaponEntityDamageEvent.DamageKind;
import org.luckyraven.bartizan.api.raytrace.WeaponRaytracer;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.api.weapon.dto.EffectHook;
import org.luckyraven.bartizan.api.weapon.dto.ExplosionData;
import org.luckyraven.bartizan.api.weapon.dto.ExplosionData.Airstrike;
import org.luckyraven.bartizan.api.weapon.dto.ExplosionData.Cluster;
import org.luckyraven.bartizan.api.weapon.dto.ExplosionData.Shape;
import org.luckyraven.bartizan.api.weapon.modifiers.BlockDamageManager;
import org.luckyraven.bartizan.api.weapon.modifiers.BreakMode;
import org.luckyraven.bartizan.api.weapon.modifiers.DamageMath;
import org.luckyraven.bartizan.api.weapon.modifiers.ExplosionMath;
import org.luckyraven.bartizan.api.weapon.modifiers.action.BlockBreakModifier;
import org.luckyraven.bartizan.effect.EffectContext;
import org.luckyraven.bartizan.effect.EffectRunner;
import org.luckyraven.bartizan.weapon.DamageRules;
import org.luckyraven.keystone.util.ParticleUtil;

import java.util.Random;
import java.util.Set;
import java.util.function.BiPredicate;

/**
 * Gate {@code HI-a} — the single AOE explosion path for both guns (rockets) and throwables (grenades), replacing
 * the old divergent {@code SteppedProjectileTask#fireExplosion} (linear falloff, no block damage, no
 * {@link WeaponEntityDamageEvent}) and {@code ThrowableAction#detonate}'s explosive branch (vanilla
 * {@code World#createExplosion} blast, its own damage loop). Reads only {@link ExplosionData} — callers on either
 * side fold in whatever legacy-DTO extras still apply (e.g. a throwable's {@code Flat_Damage} modifier bonus)
 * before calling {@link #explode}.
 */
public class ExplosionHandler {

	/**
	 * // ponytail: single mutable holder, not a proper DI seam. {@code SteppedProjectileTask} and
	 * {@code ThrowableAction} both predate this handler and neither's constructor is this gate's to change
	 * (the former is gate {@code HI-b}'s; the latter is only ever manually {@code new}'d by
	 * {@code WeaponInteract}, never through the bean graph) — a static accessor to the one {@code @Bean} instance
	 * is the smallest way to reach it from either. Upgrade path: once HI-b's constructor is free to change, thread
	 * this through it as a normal constructor param instead.
	 */
	@Nullable
	private static ExplosionHandler instance;

	/** Hard cap on blocks broken per explosion — a naive full bounding-box scan below this. */
	private static final int MAX_BLOCKS_PER_EXPLOSION = 512;

	private static final Random RANDOM = new Random();

	private final JavaPlugin          plugin;
	private final WeaponRaytracer     raytracer;
	private final BlockDamageManager  blockDamageManager;
	private final EffectRunner        effectRunner;

	public ExplosionHandler(JavaPlugin plugin, WeaponRaytracer raytracer, BlockDamageManager blockDamageManager,
	                        EffectRunner effectRunner) {
		this.plugin             = plugin;
		this.raytracer          = raytracer;
		this.blockDamageManager = blockDamageManager;
		this.effectRunner       = effectRunner;
		instance                = this;
	}

	@Nullable
	public static ExplosionHandler get() {
		return instance;
	}

	/**
	 * Detonates {@code data} at {@code centre}: cosmetic particle burst, per-victim damage/fire/knockback, optional
	 * block damage, {@code ON_EXPLODE}, then (only at {@code depth == 0}) any configured cluster/airstrike
	 * sub-munitions.
	 *
	 * @param depth 0 for the original projectile/throw; 1 for a cluster/airstrike sub-munition — gates recursion,
	 * 		since a sub-munition's own {@link ExplosionData} may still carry the parent's {@code Cluster}/
	 * 		{@code Airstrike} config.
	 */
	public void explode(Weapon weapon, ExplosionData data, Location centre, @Nullable LivingEntity shooter,
	                    int depth) {
		World world = centre.getWorld();
		if (world == null || data.getRadius() <= 0) return;

		double radius = data.getRadius();

		ParticleUtil.spawnExplosionBurst(centre);
		if (data.getFireTicks() > 0) {
			ParticleUtil.spawnFireBurst(centre, radius);
		}

		for (Entity entity : world.getNearbyEntities(centre, radius, radius, radius)) {
			if (!(entity instanceof LivingEntity target)) continue;
			if (!isEligible(data, shooter, target, centre, this::hasLineOfSight)) continue;

			Vector offset = target.getLocation().toVector().subtract(centre.toVector());
			double damage = ExplosionMath.damageAt(data.getShape(), radius, data.getDamage(), offset);

			if (damage > 0) {
				if (shooter instanceof Player playerShooter) {
					Bukkit.getPluginManager().callEvent(new WeaponEntityDamageEvent(
							weapon, target, damage, playerShooter, weapon.getName(), DamageKind.EXPLOSION));
				}

				// Without this flag, WeaponInteract.onEntityDamage cancels the damage whenever the shooter still
				// holds a weapon — mirrors the raytracer's own entity-impact guard exactly.
				WeaponRaytracer.setRaytraceDamageInProgress(true);
				try {
					target.damage(damage, shooter);
				} finally {
					WeaponRaytracer.setRaytraceDamageInProgress(false);
				}

				applyKnockback(target, offset, data);

				// Gate HI-a review fix: getNearbyEntities(...) above is a bounding box, not the actual blast
				// shape — gating fire on damage > 0 (which ExplosionMath.damageAt already zeroes outside the
				// shape) keeps a victim standing just outside a sphere/cube blast from catching fire anyway.
				if (data.getFireTicks() > 0) {
					target.setFireTicks(data.getFireTicks());
				}
			}
		}

		if (data.isBlockDamage()) {
			applyBlockDamage(world, centre, data, shooter instanceof Player playerShooter ? playerShooter : null);
		}

		EffectContext effectCtx = EffectContext.builder().weapon(weapon).source(shooter).impact(centre).build();
		effectRunner.run(weapon, EffectHook.ON_EXPLODE, effectCtx);

		if (depth == 0 && shooter != null) {
			spawnCluster(weapon, data, centre, shooter);
			spawnAirstrike(weapon, data, centre, shooter);
		}
	}

	/**
	 * Pure filter: {@code Owner_Immunity}/{@code Ignore_Teams} skip, then (only for
	 * {@link ExplosionData.Exposure#LINE_OF_SIGHT}) the injected line-of-sight check. Kept static and Bukkit-world
	 * free below the {@code hasLineOfSight} seam so it is unit-testable without a running server.
	 */
	static boolean isEligible(ExplosionData data, @Nullable LivingEntity shooter, LivingEntity target,
	                          Location centre, BiPredicate<Location, Location> hasLineOfSight) {
		if (DamageRules.isProtected(data, shooter, target)) return false;

		if (data.getExposure() == ExplosionData.Exposure.LINE_OF_SIGHT &&
		    !hasLineOfSight.test(centre, target.getEyeLocation())) {
			return false;
		}
		return true;
	}

	/**
	 * {@code true} when nothing solid stands between {@code centre} and {@code targetEye} — a blocked line of sight
	 * means the target takes no damage regardless of shape/radius. Ignores passable blocks (grass, torches, signs,
	 * …) — {@code Exposure.LINE_OF_SIGHT} is meant to model real cover, not tall grass.
	 */
	private boolean hasLineOfSight(Location centre, Location targetEye) {
		World world = centre.getWorld();
		if (world == null) return true;

		Vector to   = targetEye.toVector().subtract(centre.toVector());
		double dist = to.length();
		if (dist < 1e-6) return true;

		return world.rayTraceBlocks(centre, to.normalize(), dist, FluidCollisionMode.NEVER, true) == null;
	}

	/**
	 * Adds a {@code Knockback} falloff vector (mirrors gate {@code HF}'s knockback) pointing away from the blast
	 * centre. A no-op when {@code knockback} is {@code null} or the falloff factor is {@code 0}.
	 */
	private void applyKnockback(LivingEntity target, Vector offset, ExplosionData data) {
		Double knockback = data.getKnockback();
		if (knockback == null) return;

		double dist   = offset.length();
		double factor = DamageMath.explosionKnockbackFactor(knockback, dist, data.getRadius());
		if (factor <= 0) return;

		Vector direction = dist > 1e-6 ? offset.clone().normalize() : new Vector(0, 1, 0);
		target.setVelocity(target.getVelocity().add(direction.multiply(factor)));
	}

	/**
	 * Breaks every eligible block within {@code data}'s shape/radius through {@link BlockDamageManager}, one hit
	 * each (immediate {@link BreakMode#RESTORE}), capped at {@link #MAX_BLOCKS_PER_EXPLOSION}.
	 * <p>
	 * // ponytail: a naive full bounding-box triple loop, not a spatial/priority scan — fine below the 512-block
	 * cap; revisit with a surface-first scan if huge-radius block-damage explosions become common.
	 */
	private void applyBlockDamage(World world, Location centre, ExplosionData data, @Nullable Player player) {
		double radius = data.getRadius();
		int    r      = (int) Math.ceil(radius);
		int    hits   = 0;

		outer:
		for (int x = -r; x <= r; x++) {
			for (int y = -r; y <= r; y++) {
				for (int z = -r; z <= r; z++) {
					if (hits >= MAX_BLOCKS_PER_EXPLOSION) break outer;

					Vector offset = new Vector(x, y, z);
					boolean contains = data.getShape() == Shape.CUBE
					                   ? ExplosionMath.cubeContains(radius, offset)
					                   : ExplosionMath.sphereContains(radius, offset);
					if (!contains) continue;

					Block block = world.getBlockAt(centre.getBlockX() + x, centre.getBlockY() + y,
					                               centre.getBlockZ() + z);
					if (!isBreakable(block)) continue;

					blockDamageManager.applyDamage(block,
							new BlockBreakModifier(Set.of(block.getType()), 1, BreakMode.RESTORE), player);
					hits++;
				}
			}
		}
	}

	private boolean isBreakable(Block block) {
		Material type = block.getType();
		if (type.isAir() || block.isLiquid()) return false;
		return type.getHardness() >= 0;
	}

	/**
	 * {@code count} sub-projectiles launched outward from the blast centre in random, slightly downward-biased
	 * directions at {@code speed}, staggered by {@code delayTicks}. Each detonates on its own impact like any
	 * other stepped projectile (gate {@code HI-a}, §3) — {@code delayTicks} here only staggers the launch, not a
	 * separate per-bomblet fuse; the projectile's usual impact/maxTicks lifecycle already guarantees it eventually
	 * goes off.
	 */
	private void spawnCluster(Weapon weapon, ExplosionData data, Location centre, LivingEntity shooter) {
		Cluster cluster = data.getCluster();
		if (cluster == null) return;

		for (int i = 0; i < cluster.count(); i++) {
			Vector direction = randomUpwardDirection().multiply(Math.max(0.1, cluster.speed()));
			Bukkit.getScheduler().runTaskLater(plugin,
					() -> WeaponShooting.launch(plugin, raytracer, shooter, weapon, centre.clone(), direction,
					                            effectRunner, 1),
					Math.max(0, cluster.delayTicks()));
		}
	}

	/**
	 * {@code count} projectiles spawned {@code height} blocks above random points within {@code radius} of the
	 * blast centre, launched straight down, after a {@code delayTicks} stagger.
	 */
	private void spawnAirstrike(Weapon weapon, ExplosionData data, Location centre, LivingEntity shooter) {
		Airstrike airstrike = data.getAirstrike();
		if (airstrike == null) return;

		for (int i = 0; i < airstrike.count(); i++) {
			double   angle  = RANDOM.nextDouble() * Math.PI * 2;
			double   dist   = RANDOM.nextDouble() * airstrike.radius();
			Location origin = centre.clone().add(Math.cos(angle) * dist, airstrike.height(), Math.sin(angle) * dist);
			Vector   down   = new Vector(0, -1, 0);

			Bukkit.getScheduler().runTaskLater(plugin,
					() -> WeaponShooting.launch(plugin, raytracer, shooter, weapon, origin, down, effectRunner, 1),
					Math.max(0, airstrike.delayTicks()));
		}
	}

	/**
	 * Gate {@code HI-a} review fix: {@code y} was {@code [0.5, 1.0]} (steeply upward), sending cluster bomblets
	 * straight into the sky where they detonated harmlessly. Biasing {@code y} to {@code [-0.3, 0.3]} spreads them
	 * outward with only a slight up/down tilt so they arc back down (once {@code RaytraceRequest.gravity}, now set
	 * by {@code WeaponShooting#launch}, is applied) instead of flying dead straight.
	 */
	private Vector randomUpwardDirection() {
		double angle = RANDOM.nextDouble() * Math.PI * 2;
		double y     = -0.3 + RANDOM.nextDouble() * 0.6;
		return new Vector(Math.cos(angle), y, Math.sin(angle)).normalize();
	}

}
