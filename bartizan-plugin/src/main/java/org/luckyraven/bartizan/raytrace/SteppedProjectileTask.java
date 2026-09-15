package org.luckyraven.bartizan.raytrace;

import org.luckyraven.bartizan.api.raytrace.WeaponRaytracer;
import org.luckyraven.bartizan.api.raytrace.RaytraceContext;
import org.luckyraven.bartizan.api.raytrace.WeaponVisualSpawner;

import com.cryptomorin.xseries.particles.XParticle;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.EulerAngle;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;
import org.luckyraven.bartizan.api.weapon.GunWeapon;
import org.luckyraven.bartizan.api.weapon.ThrowableWeapon;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.api.weapon.dto.BouncyData;
import org.luckyraven.bartizan.api.weapon.dto.ExplosionData;
import org.luckyraven.bartizan.api.weapon.dto.ExplosionData.Detonation;
import org.luckyraven.bartizan.api.weapon.dto.ExplosionData.Trigger;
import org.luckyraven.bartizan.api.weapon.dto.ProjectileData;
import org.luckyraven.bartizan.api.weapon.dto.SoundData;
import org.luckyraven.bartizan.api.weapon.modifiers.action.TracerModifier;
import org.luckyraven.bartizan.effect.EffectRunner;
import org.luckyraven.keystone.sound.SoundEffect;
import org.luckyraven.keystone.timer.CountdownTimer;
import org.luckyraven.keystone.timer.RepeatingTimer;
import org.luckyraven.keystone.util.ParticleUtil;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Per-tick driver for slow visual projectiles (rockets, flares, throwables). "Server path is the truth"
 * (weapons-roadmap.md gate {@code HI} part b): this task now owns the projectile's velocity and teleports its
 * cosmetic visual entity to a computed position every tick, instead of reading the visual's own Bukkit-physics
 * position — so gravity, drag and bouncing off blocks show correctly regardless of what vanilla would have done
 * with the entity. {@link WeaponRaytracer#advanceSegment} still runs once per tick over the segment actually
 * travelled, for entity-hit detection, damage, tracers and block-hit effects (particles, block break).
 * <p>
 * On terminal impact (an entity is hit, a block stops it with no bounce configured, it comes to rest after a
 * bounce, or its {@code Alive_Ticks} expire), the task removes the visual and — when the impact allows it and
 * {@code explodeOnTerminate} is set — hands off to {@link #onImpact(Location)}, which applies the weapon's
 * {@code Detonation} rules and, via the unified {@code ExplosionHandler} (gate {@code HI-a}), fires the AOE
 * explosion at the impact point.
 */
public class SteppedProjectileTask {

	private final JavaPlugin          plugin;
	private final WeaponRaytracer     raytracer;
	private final WeaponVisualSpawner visualSpawner;
	private final Entity              visual;
	private final RaytraceContext     ctx;
	private final boolean             explodeOnTerminate;
	private final double              explosionRadius;
	private final double              explosionDamage;
	private final int                 aliveTicks;
	private final EffectRunner        effectRunner;

	private final double     gravity;
	private final double     drag;
	@Nullable
	private final BouncyData bouncy;
	private final boolean    extinguishInWater;
	private final boolean    drawTracer;
	@Nullable
	private final Particle   trail;

	private Location         currentLoc;
	private Vector           velocity;
	private RepeatingTimer   timer;
	private int              tickCounter;
	private boolean          finished;
	private final Set<UUID>  flybyNotified = new HashSet<>();

	public SteppedProjectileTask(JavaPlugin plugin, WeaponRaytracer raytracer, WeaponVisualSpawner visualSpawner,
	                             Entity visual, Location spawnLocation, Vector initialVelocity, RaytraceContext ctx,
	                             boolean explodeOnTerminate, double explosionRadius, double explosionDamage,
	                             int aliveTicks, EffectRunner effectRunner, ProjectileData projectileData) {
		this.plugin             = plugin;
		this.raytracer          = raytracer;
		this.visualSpawner      = visualSpawner;
		this.visual             = visual;
		this.ctx                = ctx;
		this.explodeOnTerminate = explodeOnTerminate;
		this.explosionRadius    = explosionRadius;
		this.explosionDamage    = explosionDamage;
		this.aliveTicks         = aliveTicks;
		this.effectRunner       = effectRunner;
		this.gravity            = projectileData.getGravity();
		this.drag               = projectileData.getDrag();
		this.bouncy             = projectileData.getBouncy();
		this.extinguishInWater  = projectileData.isExtinguishInWater();
		this.drawTracer         = projectileData.isParticle();
		this.trail              = projectileData.getTrail();
		this.currentLoc         = spawnLocation.clone();
		this.velocity           = initialVelocity.clone();
		this.tickCounter        = 0;
		this.finished           = false;
	}

	public void start() {
		timer = new RepeatingTimer(plugin, 1L, task -> {
			if (finished) {
				task.stop();
				return;
			}

			if (++tickCounter > aliveTicks) {
				terminate(currentLoc, false);
				task.stop();
				return;
			}

			// Fallback safety net for the projectile visual classes (Fireball/Firework) — vanilla physics can
			// still remove/invalidate the entity out from under us (unloaded chunk, another plugin, etc.).
			if (visual.isDead() || !visual.isValid()) {
				terminate(currentLoc, true);
				task.stop();
				return;
			}

			World world = currentLoc.getWorld();
			if (world == null) {
				terminate(currentLoc, false);
				task.stop();
				return;
			}

			velocity = ProjectileMotion.applyGravityAndDrag(velocity, gravity, drag);
			double speed = velocity.length();

			Location      moveTo   = currentLoc.clone().add(velocity);
			RayTraceResult blockHit = speed > 1e-6
			                          ? world.rayTraceBlocks(currentLoc, velocity.clone().normalize(), speed,
			                                                 FluidCollisionMode.NEVER, true)
			                          : null;
			// The segment fed to advanceSegment, pulled back by advanceSegment's own +0.01 overshoot epsilon when
			// this tick hits a block face. Passing the exact face position would let advanceSegment's internal
			// scan overshoot 0.01 past the face into the block, re-detecting the same block a second time (double
			// Break_Blocks damage; a Ricochet modifier on the same material double-incrementing bounceCount
			// alongside the Bouncy handling below) (HI-b review #6a).
			Location segmentEnd = moveTo;
			if (blockHit != null) {
				moveTo = blockHit.getHitPosition().toLocation(world);
				segmentEnd = moveTo.clone().subtract(velocity.clone().normalize().multiply(0.01));
			}

			// Shared damage/tracer/block-effect pipeline over the segment actually travelled this tick.
			raytracer.advanceSegment(ctx, currentLoc, segmentEnd);
			checkFlyby(currentLoc, moveTo);
			if (drawTracer) drawTracerSegment(currentLoc, moveTo);
			if (trail != null) world.spawnParticle(trail, moveTo, 1);

			// terminalHit (not hitEntity, which a penetrating hit also sets) — a Pierce_Entities hit must keep the
			// flight going, per Modifiers.Penetration, instead of terminating the moment any entity is struck
			// (HI-b review #4).
			if (ctx.isTerminalHit()) {
				Location impact = lastTracerPoint();
				terminate(impact != null ? impact : moveTo, true);
				task.stop();
				return;
			}

			if (blockHit != null) {
				Block    hitBlock   = blockHit.getHitBlock();
				Material material  = hitBlock != null ? hitBlock.getType() : Material.AIR;
				double   multiplier = bouncy != null ? bouncy.multiplierFor(material) : 0.0;

				if (multiplier <= 0 || blockHit.getHitBlockFace() == null) {
					terminate(moveTo, true);
					task.stop();
					return;
				}

				Vector normal = WeaponRaytracerImpl.blockFaceNormal(blockHit.getHitBlockFace());
				velocity = ProjectileMotion.bounce(velocity, normal, multiplier);
				ctx.getState().setBounceCount(ctx.getState().getBounceCount() + 1);
				currentLoc = moveTo.clone().add(normal.clone().multiply(0.05));
				teleportVisual(currentLoc, velocity);

				if (ProjectileMotion.atRest(velocity)) {
					terminate(currentLoc, true);
					task.stop();
				}
				return;
			}

			if (extinguishInWater) {
				// Sample the segment midpoint too, not just the endpoint — at Speed: 3 a single-block-thick water
				// sheet can otherwise be stepped clean over in one tick (HI-b review #6b).
				Location midpoint = currentLoc.clone().add(moveTo.clone().subtract(currentLoc).multiply(0.5));
				if (isWater(midpoint.getBlock()) || isWater(moveTo.getBlock())) {
					terminate(moveTo, false);
					task.stop();
					return;
				}
			}

			currentLoc = moveTo;
			teleportVisual(currentLoc, velocity);
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

	/**
	 * {@code Projectile.Particle: true} — draws this tick's travelled segment as a tracer: the same default gray
	 * dust line {@code WeaponRaytracerImpl.flushTracer} draws for hitscan guns, plus any configured
	 * {@code Modifiers.Tracer} colour. One call per tick (HE-b particle-budget precedent) rather than replaying
	 * the whole flight's {@code ctx.getTracerSegments()} every tick.
	 * <p>
	 * // ponytail: duplicates WeaponRaytracerImpl.drawTracerLine's per-leg point-count math instead of sharing it
	 * — that method is tailored to a full segment list anchored at the muzzle, not one already-known leg. Unify
	 * if a third caller shows up.
	 */
	private void drawTracerSegment(Location from, Location to) {
		World fromWorld = from.getWorld();
		if (fromWorld == null || to.getWorld() == null || !fromWorld.equals(to.getWorld())) return;

		Weapon weapon = ctx.getRequest().getWeapon();
		int    count  = Math.max(2, (int) (from.distance(to) * 4));

		ParticleUtil.spawnLine(from, to, XParticle.DUST.get(), count, new Particle.DustOptions(Color.GRAY, 0.5F));

		if (weapon.getModifiersData().hasTracer()) {
			TracerModifier tracer = weapon.getModifiersData().getTracer();
			ParticleUtil.spawnLine(from, to, XParticle.DUST.get(), count,
			                      new Particle.DustOptions(tracer.color(), tracer.particleSize()));
		}
	}

	/**
	 * Teleports the visual to {@code loc} facing {@code direction} — the "server path is the truth" move (gate
	 * {@code HI} part b). The entity's own velocity is zeroed straight after so vanilla per-tick physics
	 * (gravity-free or not) never adds its own movement on top of this teleport. An {@link ArmorStand} visual also
	 * gets a trivial head pitch matching the direction.
	 */
	private void teleportVisual(Location loc, Vector direction) {
		Location facing = loc.clone();
		boolean  hasDirection = direction.lengthSquared() > 1e-6;
		if (hasDirection) {
			facing.setDirection(direction);
		}

		visual.teleport(facing);
		visual.setVelocity(new Vector(0, 0, 0));

		if (visual instanceof ArmorStand stand && hasDirection) {
			double pitch = -Math.asin(direction.clone().normalize().getY());
			stand.setHeadPose(new EulerAngle(pitch, 0, 0));
		}
	}

	/**
	 * {@code Projectile.Extinguish_In_Water}: true for a liquid block or a {@link Waterlogged} solid block (e.g. a
	 * waterlogged fence or slab) — the block itself isn't a liquid material, but a projectile flying through it is
	 * just as wet (HI-b review #6b).
	 */
	private static boolean isWater(Block block) {
		if (block.isLiquid()) {
			return true;
		}
		return block.getBlockData() instanceof Waterlogged waterlogged && waterlogged.isWaterlogged();
	}

	private Location lastTracerPoint() {
		List<Location> segments = ctx.getTracerSegments();
		if (segments.isEmpty()) {
			return null;
		}
		return segments.get(segments.size() - 1);
	}

	/**
	 * Ends the flight. {@code allowExplosion} is {@code false} for an expiry/water-extinguish termination (no
	 * blast, regardless of {@code explodeOnTerminate}) and {@code true} for every other terminal impact (entity
	 * hit, blocked with no bounce, at rest after a bounce, or the vanilla-death fallback) — matching the historical
	 * behaviour of always exploding on those.
	 */
	private void terminate(@Nullable Location impact, boolean allowExplosion) {
		if (finished) {
			return;
		}
		finished = true;

		visualSpawner.unregisterCosmetic(visual.getEntityId());
		if (!visual.isDead() && visual.isValid()) {
			visual.remove();
		}

		// Gate HI-a/HI-b seam: HI-b decides whether this termination may explode (expiry and water extinguish
		// pass false); HI-a's onImpact applies the Detonation rules and fires the unified ExplosionHandler.
		if (allowExplosion && explodeOnTerminate && explosionRadius > 0 && impact != null) {
			onImpact(impact);
		}
	}

	/**
	 * Gate {@code HI-a} — applies the weapon's {@code Detonation} rules for this impact and, if they allow it,
	 * explodes. Called from {@link #terminate(Location, boolean)} (gate {@code HI-b}) for every terminal impact
	 * that allows an explosion.
	 * <p>
	 * {@code terminate} does not yet tell this method whether the impact was a block or an entity, so every impact
	 * is treated as satisfying both {@link Trigger#BLOCK} and {@link Trigger#ENTITY} — narrow this once that
	 * information reaches here. A configured {@code Detonation.Fuse_Ticks} (explode after N ticks with no impact at
	 * all) is not wired for this gun/rocket path — nothing in this class runs independently of the tick loop
	 * {@code start()} owns; throwables get the equivalent behaviour from {@code ThrowableAction}'s own fuse timer
	 * instead.
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
