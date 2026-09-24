package org.luckyraven.bartizan.raytrace;

import com.cryptomorin.xseries.XAttribute;
import com.cryptomorin.xseries.particles.XParticle;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.*;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;
import org.luckyraven.keystone.util.ParticleUtil;
import org.luckyraven.bartizan.effect.EffectContext;
import org.luckyraven.bartizan.effect.EffectRunner;
import org.luckyraven.bartizan.api.weapon.dto.EffectHook;
import org.luckyraven.bartizan.file.BartizanSettings;
import org.luckyraven.bartizan.weapon.DamageRules;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.api.weapon.dto.DamageData;
import org.luckyraven.bartizan.api.weapon.dto.SoundData;
import org.luckyraven.keystone.sound.SoundEffect;
import org.luckyraven.bartizan.api.event.WeaponEntityDamageEvent;
import org.luckyraven.bartizan.api.event.WeaponEntityDamageEvent.DamageKind;
import org.luckyraven.bartizan.api.event.WeaponRaytraceImpactEvent;
import org.luckyraven.bartizan.api.weapon.modifiers.BlockDamageManager;
import org.luckyraven.bartizan.api.weapon.modifiers.DamageMath;
import org.luckyraven.bartizan.api.weapon.modifiers.ModifierHandler;
import org.luckyraven.bartizan.api.weapon.modifiers.action.BlockBreakModifier;
import org.luckyraven.bartizan.api.weapon.modifiers.action.RicochetModifier;
import org.luckyraven.bartizan.api.weapon.modifiers.action.TracerModifier;
import org.luckyraven.bartizan.api.weapon.ProjectileState;
import org.luckyraven.bartizan.api.weapon.BodyZone;
import org.luckyraven.bartizan.api.weapon.GunWeapon;
import org.luckyraven.bartizan.api.raytrace.RaytraceContext;
import org.luckyraven.bartizan.api.raytrace.RaytraceRequest;
import org.luckyraven.bartizan.api.raytrace.WeaponRaytracer;
import org.luckyraven.bartizan.api.raytrace.WeaponVisualSpawner;
import org.luckyraven.bartizan.wearable.WearableService;
import org.luckyraven.keystone.bean.BeanLifecycle;

import java.util.List;
import java.util.Random;

/**
 * Unified server-side raytracer used by every weapon action (gun, incendiary, biological, melee, throwable). Decouples
 * hit detection from the Bukkit projectile entity lifecycle so penetration, ricochet, and block-break modifiers all
 * work even when Spigot destroys the visual projectile on the first contact.
 * <p>
 * Two entry points share the same {@link #advanceRay} loop:
 * <ul>
 *   <li>{@link #fireInstant(RaytraceRequest)} — runs the loop synchronously to completion in one
 *       tick. Used for hitscan shots (rifles, shotgun pellets, fire spray, biological clouds,
 *       melee swings).</li>
 *   <li>{@link #advanceSegment(RaytraceContext, Location, Location)} — runs zero or more iterations
 *       covering exactly one segment travelled by a slow visual projectile in a single tick. Used
 *       by {@code SteppedProjectileTask} for rockets and throwables.</li>
 * </ul>
 * <p>
 * Each impact fires a {@link WeaponRaytraceImpactEvent}. Listeners (cops-n-crooks NPC AI, vehicle
 * damage handlers) react uniformly regardless of which weapon action originated the shot. If a
 * weapon needs non-standard impact behaviour (incendiary fire ticks, biological potion effects),
 * it supplies a custom {@code impactHandler} on its {@link RaytraceRequest}.
 *
 * <p>bartizan.md §1.6(7): the runtime implementation of the {@link WeaponRaytracer} api interface. The
 * {@code isRaytraceDamageInProgress()}/{@code setRaytraceDamageInProgress(boolean)} ThreadLocal flag lives on the
 * interface itself now (static members), not on this class.
 */
public class WeaponRaytracerImpl implements WeaponRaytracer, BeanLifecycle {

	/**
	 * Length of each straight-line segment used to approximate the parabolic drop arc beyond the effective range.
	 * Smaller values produce a smoother curve but require more raytrace calls per shot. 3 blocks gives sub-pixel arc
	 * error even at high gravity values while keeping raytrace call count low (20 calls for a 60-block drop phase).
	 */
	private static final double GRAVITY_STEP   = 3.0;
	/**
	 * Maximum distance (blocks) a bullet can travel in the gravity drop phase beyond its configured effective range.
	 * Acts as a safety cap to prevent runaway raytrace loops for bullets that never hit geometry (e.g. fired straight
	 * up).
	 */
	private static final double MAX_DROP_RANGE = 200.0;

	private final WearableService      wearableService;
	private final BlockDamageManager   blockDamageManager;
	private final WeaponVisualSpawner  visualSpawner;
	private final EffectRunner         effectRunner;
	private final Random               random;

	public WeaponRaytracerImpl(WearableService wearableService, BlockDamageManager blockDamageManager,
	                           WeaponVisualSpawner visualSpawner, EffectRunner effectRunner) {
		this.wearableService    = wearableService;
		this.blockDamageManager = blockDamageManager;
		this.visualSpawner      = visualSpawner;
		this.effectRunner       = effectRunner;
		this.random             = new Random();
	}

	// Package-visible (not private) so SteppedProjectileTask's own block raytrace — used for Projectile.Bouncy
	// physics rather than the shared damage pipeline below — can reuse the same face-to-normal mapping.
	static Vector blockFaceNormal(BlockFace face) {
		return switch (face) {
			case DOWN -> new Vector(0, -1, 0);
			case NORTH -> new Vector(0, 0, -1);
			case SOUTH -> new Vector(0, 0, 1);
			case EAST -> new Vector(1, 0, 0);
			case WEST -> new Vector(-1, 0, 0);
			default -> new Vector(0, 1, 0);
		};
	}

	// ------------------------------------------------------------------
	// Public entry points
	// ------------------------------------------------------------------

	// Delegates to ProjectileMotion (gate HI part b) so bullet ricochet and Projectile.Bouncy share one reflection
	// formula instead of two copies drifting apart.
	private static Vector reflect(Vector velocity, Vector normal) {
		return ProjectileMotion.reflect(velocity, normal);
	}

	/**
	 * Computes the point at which a ray exits the given axis-aligned bounding box, given an entry point on (or inside)
	 * the box and a direction. Used after a penetration to advance the raytrace origin to <em>just past</em> the back
	 * face of the penetrated block or entity, so the next iteration of the loop doesn't immediately re-detect the same
	 * target.
	 * <p>
	 * Implements the standard slab method: for each axis, compute the parameter {@code t} at which the ray crosses the
	 * far face of the box; the smallest positive {@code t} gives the exit point. A small epsilon is added so the
	 * returned location is strictly past the back face.
	 */
	private static Location advancePastBox(BoundingBox box, Location entry, Vector dir) {
		Vector origin = entry.toVector();

		double tMaxX = Double.POSITIVE_INFINITY;
		double tMaxY = Double.POSITIVE_INFINITY;
		double tMaxZ = Double.POSITIVE_INFINITY;

		if (dir.getX() > 1e-9) {
			tMaxX = (box.getMaxX() - origin.getX()) / dir.getX();
		} else if (dir.getX() < -1e-9) {
			tMaxX = (box.getMinX() - origin.getX()) / dir.getX();
		}

		if (dir.getY() > 1e-9) {
			tMaxY = (box.getMaxY() - origin.getY()) / dir.getY();
		} else if (dir.getY() < -1e-9) {
			tMaxY = (box.getMinY() - origin.getY()) / dir.getY();
		}

		if (dir.getZ() > 1e-9) {
			tMaxZ = (box.getMaxZ() - origin.getZ()) / dir.getZ();
		} else if (dir.getZ() < -1e-9) {
			tMaxZ = (box.getMinZ() - origin.getZ()) / dir.getZ();
		}

		double exitT = Math.min(Math.min(tMaxX, tMaxY), tMaxZ);
		if (exitT == Double.POSITIVE_INFINITY || exitT < 0) {
			exitT = 0;
		}

		// Tiny epsilon ensures the new origin is strictly past the back face.
		return entry.clone().add(dir.clone().multiply(exitT + 0.01));
	}

	// ------------------------------------------------------------------
	// Inner loop
	// ------------------------------------------------------------------

	@Override
	public WeaponVisualSpawner getVisualSpawner() {
		return visualSpawner;
	}

	/**
	 * Removes every still-flying cosmetic visual on plugin disable / server stop (HI-b review #5) — otherwise a
	 * visual type that never self-expires on its own (an {@code ARMOR_STAND} or a {@code PRIMED_TNT} with its fuse
	 * held at {@code Integer.MAX_VALUE}) is orphaned in the world once its driving {@code SteppedProjectileTask}
	 * stops ticking.
	 */
	@Override
	public void onShutdown() {
		visualSpawner.removeAll();
	}

	// ------------------------------------------------------------------
	// Impact handling
	// ------------------------------------------------------------------

	/**
	 * Runs the full hitscan loop for a single ray, synchronously, until the ray stops (no more penetration, no more
	 * ricochet, no more distance). The supplied request must carry an origin already at the muzzle position — see
	 * {@code WeaponMuzzle#compute}.
	 *
	 * @return {@code true} if a living entity took the hit — {@code ctx.isHitEntity()}, set by
	 * 		{@link #handleEntityImpact} just before it fires {@code ON_HIT} (or, for a custom impact handler, as soon
	 * 		as the impact lands on a {@code LivingEntity}). {@code ON_MISS} is not fired here — callers (the firing
	 * 		actions) decide whether and when to run it off this return value.
	 */
	@Override
	public boolean fireInstant(RaytraceRequest request) {
		ProjectileState state = new ProjectileState(request.getWeapon(), request.getBaseDamage());
		RaytraceContext ctx   = new RaytraceContext(request, state);

		while (advanceRay(ctx, ctx.getRemaining())) {
			// loop until advanceRay reports stop
		}

		// If the ray exhausted its effective range without a terminal hit and gravity is configured,
		// continue the bullet beyond the effective range with a parabolic drop until it lands. !isHitEntity()
		// guards against the entity-hit branch below now also zeroing remaining (gate HI part b) — without it, a
		// gravity gun that lands a direct hit within its effective range would incorrectly keep extending the
		// arc past the entity it just hit.
		if (request.getGravity() > 0 && ctx.getRemaining() <= 0 && !ctx.isHitEntity()) {
			extendWithGravity(ctx);
		}

		flushTracer(ctx);
		playFlybySounds(ctx);

		return ctx.isHitEntity();
	}

	/**
	 * Runs the loop over exactly one segment of travel — from {@code from} to {@code to}. Used by stepped slow
	 * projectiles whose visual entity moved a small amount during the current tick. The context must persist across
	 * calls so penetration / ricochet counters carry over.
	 */
	@Override
	public void advanceSegment(RaytraceContext ctx, Location from, Location to) {
		Vector segment = to.toVector().subtract(from.toVector());
		if (segment.lengthSquared() < 1e-6) {
			return;
		}

		ctx.setCurrentOrigin(from.clone());
		ctx.setCurrentDir(segment.clone().normalize());
		ctx.setRemaining(segment.length() + 0.01);
		// Reset here (not just at the extendWithGravity call site) so a stepped slow projectile — which reuses one
		// ctx across its whole flight, one advanceSegment call per tick — gets a full iteration budget every tick
		// instead of hitting getMaxIterations() from tick 9 onward and going blind (HI-b review #1).
		// ponytail: not pinned with a mocked-World test — that needs a full World/RayTraceResult mock harness this
		// suite doesn't have yet; RaytraceContextTest pins the sibling terminalHit flag (review #4) instead.
		ctx.setIterations(0);

		while (advanceRay(ctx, ctx.getRemaining())) {
			// loop until advanceRay reports stop
		}
	}

	/**
	 * Continues a bullet beyond its effective range with a parabolic gravity arc. Called when the straight-line hitscan
	 * phase exhausted its distance budget without a terminal hit. The bullet maintains its horizontal direction but
	 * curves downward according to the kinematic equation:
	 * <pre>
	 *   y(s) = startY + dirY * s − 0.5 * gravity * (s / speed)²
	 * </pre>
	 * where {@code s} is the distance traveled beyond the effective range and {@code speed} is the projectile speed
	 * from the weapon config. Faster bullets drop less because gravity has less "time" to act. The extension continues
	 * until the ray hits a block or entity, or the {@link #MAX_DROP_RANGE} safety cap is reached.
	 */
	private void extendWithGravity(RaytraceContext ctx) {
		RaytraceRequest request = ctx.getRequest();
		double          gravity = request.getGravity();
		double          speed   = Math.max(0.1, request.getProjectileSpeed());
		Vector          dir     = ctx.getCurrentDir().clone().normalize();

		// Start from the last known position — the endpoint of the straight-line phase.
		List<Location> segments = ctx.getTracerSegments();
		Location startPos = segments.isEmpty()
		                    ? ctx.getCurrentOrigin().clone()
		                    : segments.get(segments.size() - 1).clone();

		double   traveled = 0;
		Location current  = startPos.clone();

		while (traveled < MAX_DROP_RANGE) {
			double step         = Math.min(GRAVITY_STEP, MAX_DROP_RANGE - traveled);
			double nextTraveled = traveled + step;

			// Parabolic arc: horizontal displacement continues at the same rate while
			// vertical position drops quadratically with distance.
			double tNext = nextTraveled / speed;
			Location next = startPos.clone().add(
					dir.getX() * nextTraveled,
					dir.getY() * nextTraveled - 0.5 * gravity * tNext * tNext,
					dir.getZ() * nextTraveled
			);

			// advanceSegment resets the iteration budget itself now (HI-b review #1).
			advanceSegment(ctx, current, next);

			// advanceSegment sets remaining = segment.length + 0.01, then advanceRay zeros it on miss but leaves it
			// positive on a terminal (non-penetrating) hit. isHitEntity() must also stop the arc — without it, a
			// non-penetrating entity hit (which also zeroes remaining, see advanceRay) reads as a miss here and the
			// arc keeps extending straight through the entity, re-running handleEntityImpact on later segments
			// (HI-b review #2).
			if (ctx.getRemaining() > 0.02 || ctx.isHitEntity()) {
				break;
			}

			current  = next;
			traveled = nextTraveled;
		}
	}

	/**
	 * Performs a single iteration of the raytrace loop. Returns {@code true} if the caller should call again (the ray
	 * continued past a penetration or ricochet) and {@code false} if the ray has terminated.
	 */
	private boolean advanceRay(RaytraceContext ctx, double segmentLimit) {
		RaytraceRequest request = ctx.getRequest();

		if (ctx.getIterations() >= request.getMaxIterations()) {
			return false;
		}
		ctx.setIterations(ctx.getIterations() + 1);

		if (ctx.getRemaining() <= 0) {
			return false;
		}

		World world = ctx.getCurrentOrigin().getWorld();
		if (world == null) {
			return false;
		}

		double scanDist = Math.min(ctx.getRemaining(), segmentLimit);

		LivingEntity shooter = request.getShooter();
		RayTraceResult blockHit = world.rayTraceBlocks(
				ctx.getCurrentOrigin(), ctx.getCurrentDir(), scanDist,
				FluidCollisionMode.NEVER, true);
		RayTraceResult entityHit = world.rayTraceEntities(
				ctx.getCurrentOrigin(), ctx.getCurrentDir(), scanDist,
				request.getHitboxExpansion(),
				e -> e != shooter
				     && !(e instanceof ItemFrame || e instanceof ArmorStand)
				     // Damage.Owner_Immunity / Ignore_Teams (gate HF, §4): a protected entity is skipped and the
				     // ray continues past it rather than stopping on it. Guns only — DamageData lives on GunWeapon.
				     && !(request.getWeapon() instanceof GunWeapon gun && e instanceof LivingEntity candidate
				          && DamageRules.isProtected(gun.getDamageData(), shooter, candidate))
				     && request.getEntityFilter().test(e));

		// Discard hits on entities that are behind or exactly beside the ray origin — these are
		// caught only because hitboxExpansion inflates the detection sphere at the start point.
		if (entityHit != null) {
			Entity candidate = entityHit.getHitEntity();
			if (candidate != null) {
				Vector toCandidate = candidate.getLocation().toVector()
				                              .subtract(ctx.getCurrentOrigin().toVector());
				if (toCandidate.dot(ctx.getCurrentDir()) <= 0) {
					entityHit = null;
				}
			}
		}

		double blockDist = blockHit != null ?
		                   blockHit.getHitPosition().distance(ctx.getCurrentOrigin().toVector()) :
		                   Double.POSITIVE_INFINITY;
		double entityDist = entityHit != null ?
		                    entityHit.getHitPosition().distance(ctx.getCurrentOrigin().toVector()) :
		                    Double.POSITIVE_INFINITY;

		// Entity hit takes precedence on ties — a body in front of a wall takes the shot.
		if (entityHit != null && entityDist <= blockDist) {
			Entity hit = entityHit.getHitEntity();
			if (hit == null) return false;

			Location impactPt = entityHit.getHitPosition().toLocation(world);

			handleEntityImpact(hit, impactPt, ctx);
			ctx.getTracerSegments().add(impactPt);

			if (ModifierHandler.handleEntityPenetration(ctx.getState())) {
				Location pastEntity  = advancePastBox(hit.getBoundingBox(), impactPt, ctx.getCurrentDir());
				double   advanceDist = pastEntity.toVector().distance(ctx.getCurrentOrigin().toVector());
				ctx.setCurrentOrigin(pastEntity);
				ctx.setRemaining(ctx.getRemaining() - advanceDist);
				return true;
			}

			// gate HI part b: a non-penetrating entity hit now zeroes remaining too (previously only a clean miss
			// did), so SteppedProjectileTask can detect "this tick hit something" without polling the vanilla
			// visual entity's isDead()/isValid() state. Guarded above for fireInstant's gravity-extension check.
			ctx.setRemaining(0);
			// Distinct from hitEntity (set unconditionally by handleEntityImpact above, penetrating or not) so a
			// Pierce_Entities hit — which continues in the branch above instead of reaching here — doesn't make
			// SteppedProjectileTask terminate the flight early (HI-b review #4).
			ctx.setTerminalHit(true);
			return false;
		}

		if (blockHit != null) {
			Location impactPt = blockHit.getHitPosition().toLocation(world);
			Block    block    = blockHit.getHitBlock();
			if (block == null) return false;

			BlockFace face = blockHit.getHitBlockFace();
			if (face == null) return false;

			applyBlockBreak(block, request.getWeapon());
			handleBlockImpact(impactPt, block, face, ctx);
			ctx.getTracerSegments().add(impactPt);

			// Penetration before ricochet — matches existing precedence
			if (ModifierHandler.handleBlockPenetration(ctx.getState(), block)) {
				Location pastBlock   = advancePastBox(block.getBoundingBox(), impactPt, ctx.getCurrentDir());
				double   advanceDist = pastBlock.toVector().distance(ctx.getCurrentOrigin().toVector());
				ctx.setCurrentOrigin(pastBlock);
				ctx.setRemaining(ctx.getRemaining() - advanceDist);
				return true;
			}

			RicochetModifier ricochet = matchingRicochet(block.getType(), ctx);
			if (ricochet != null) {
				Vector normal = blockFaceNormal(face);
				ctx.setCurrentDir(reflect(ctx.getCurrentDir(), normal).normalize());
				ctx.getState().setBounceCount(ctx.getState().getBounceCount() + 1);
				ctx.getState().applyRicochetReduction(ricochet.damageRetention());
				ctx.setCurrentOrigin(impactPt.clone().add(normal.clone().multiply(0.05)));
				ctx.setRemaining(ctx.getRemaining() - blockDist);
				return true;
			}

			return false;
		}

		// No hit in this segment — record the end point for tracer rendering and stop.
		ctx.getTracerSegments().add(
				ctx.getCurrentOrigin().clone().add(ctx.getCurrentDir().clone().multiply(scanDist)));
		ctx.setRemaining(0);
		return false;
	}

	// ------------------------------------------------------------------
	// Modifier helpers
	// ------------------------------------------------------------------

	/**
	 * Computes damage, fires {@link WeaponRaytraceImpactEvent}, and (if the event is not cancelled and no custom impact
	 * handler is supplied) applies the standard damage pipeline.
	 */
	private void handleEntityImpact(Entity hit, Location impactPt, RaytraceContext ctx) {
		Weapon       weapon  = ctx.getRequest().getWeapon();
		LivingEntity shooter = ctx.getRequest().getShooter();

		// Computed once up front (gate HF, §1) and reused both for Dropoff and the EffectContext below.
		double distance = ctx.getRequest().getOrigin().distance(impactPt);

		// --- Compute damage (gun-specific extras only when applicable) ---
		boolean  criticalHit = false;
		BodyZone zone        = null;
		boolean  backHit     = false;
		double   damage      = ctx.getState().getCurrentDamage();

		if (hit instanceof LivingEntity living) {
			if (weapon instanceof GunWeapon gun) {
				DamageData dd = gun.getDamageData();
				criticalHit = random.nextDouble() < dd.getCriticalHitChance() / 100D;
				if (criticalHit) {
					damage += wearableService.reduceCritBonus(dd.getCriticalHitDamage(), living);
				}
				damage += DamageMath.dropoff(dd.getDropoff(), distance);
			}
			damage = ModifierHandler.calculateArmorPiercingDamage(damage, living, weapon);
			damage = wearableService.applyWearableReduction(damage, living, weapon instanceof GunWeapon);
			damage = ModifierHandler.applyFlatDamage(damage, weapon);

			// Hit zones (gate HF, §2) replace the old isHeadPosition check — guns only, MeleeAction/BeamAction
			// keep their own head-only semantics via HitZone directly.
			if (weapon instanceof GunWeapon gun) {
				DamageData dd      = gun.getDamageData();
				HitZone    hitZone = HitZone.of(impactPt.toVector(), living, ctx.getCurrentDir());
				zone    = hitZone.zone();
				backHit = hitZone.back();

				damage += zoneDelta(dd, zone);
				if (backHit) {
					damage += dd.getBackDamage();
				}

				// Global Damage_Modifiers (gate HF, §5).
				damage *= DamageMath.percentMultiplier(damageModifierPercent(living));
			}
		} else {
			// Non-living: skip armor / wearable / headshot. Flat damage still applies so vehicle hits
			// see the configured bonus.
			damage = ModifierHandler.applyFlatDamage(damage, weapon);
		}

		// Additive negative deltas (dropoff, zone, wearable reduction) can sum below zero; a negative damage
		// value reaches living.damage() as damageBlocked (health doesn't drop), silently no-opping the hit.
		damage = Math.max(0.0, damage);

		// --- Fire the event ---
		WeaponRaytraceImpactEvent event = new WeaponRaytraceImpactEvent(
				weapon, shooter, hit, null, null, impactPt, damage, ctx.getState());
		Bukkit.getPluginManager().callEvent(event);
		if (event.isCancelled()) {
			return;
		}

		// --- Custom impact handler short-circuits the default pipeline ---
		if (ctx.getRequest().getImpactHandler() != null) {
			// "A living entity took the hit" for this path: the event wasn't cancelled (checked above) and the
			// impact landed on a LivingEntity — the custom handler (incendiary burn, biological potion effects,
			// melee damage) has no "blocked" concept to additionally gate on, unlike the default path below.
			if (hit instanceof LivingEntity) ctx.setHitEntity(true);

			// Set the in-progress flag for the handler too, so any target.damage(...) it makes
			// is recognised as raytracer-driven and the legacy listeners skip it.
			WeaponRaytracer.setRaytraceDamageInProgress(true);
			try {
				ctx.getRequest().getImpactHandler().accept(event);
			} finally {
				WeaponRaytracer.setRaytraceDamageInProgress(false);
			}
			return;
		}

		// --- Default damage application (LivingEntity only) ---
		if (hit instanceof LivingEntity living) {
			living.setNoDamageTicks(0);
			living.setInvulnerable(false);

			double healthBefore = living.getHealth();

			// Damage.Knockback (gate HF, §6): saved before living.damage() so a configured value of 0 can
			// override vanilla knockback back to zero; absent (null) leaves vanilla knockback untouched.
			Double knockback     = weapon instanceof GunWeapon gun ? gun.getDamageData().getKnockback() : null;
			Vector savedVelocity = knockback != null ? living.getVelocity().clone() : null;

			WeaponRaytracer.setRaytraceDamageInProgress(true);
			// BZ-EV-19: names the weapon dealing this specific damage() call for any PlayerDeathEvent Bukkit fires
			// synchronously nested inside it — the only way to attribute a slow rocket/flare's fatal hit correctly
			// once the shooter has swapped weapons since firing (the WeaponEntityDamageEvent below fires too late).
			FatalDamageAttribution.set(weapon.getName());
			try {
				living.damage(event.getDamage(), shooter);
			} finally {
				FatalDamageAttribution.clear();
				WeaponRaytracer.setRaytraceDamageInProgress(false);
			}

			// If health didn't decrease, the damage was blocked (e.g. Citizens spawn protection).
			// Skip all post-damage effects — the hit didn't land.
			boolean damageBlocked = living.isValid() && !living.isDead()
			                        && living.getHealth() >= healthBefore;
			if (damageBlocked) {
				return;
			}

			if (knockback != null) {
				// Horizontal-only push: the full 3D ray direction would drive a downward shot's knockback
				// into the ground instead of away from the shooter.
				Vector push = ctx.getCurrentDir().clone();
				push.setY(Math.max(push.getY(), 0.0));
				living.setVelocity(savedVelocity.add(push.multiply(knockback)));
			}

			// Fire the canonical WeaponEntityDamageEvent for the default (non-short-circuited) pipeline too, so one
			// listener (WeaponDeathListener) sees weapon damage regardless of which action fired the ray. Only a
			// player-attributed shot can be described as a WeaponEntityDamageEvent shooter.
			if (shooter instanceof Player player) {
				Bukkit.getPluginManager().callEvent(
						new WeaponEntityDamageEvent(weapon, living, event.getDamage(), player, weapon.getName(),
						                            DamageKind.DIRECT, zone, distance));
			}

			// On_Hit_Taken (gate HL review, §1): moved out of the GunWeapon-only block below so every weapon that
			// reaches this default (non-short-circuited) pipeline fires it — not guns only. The custom-impactHandler
			// weapons (melee/biological/beam/incendiary) never reach here at all; they fire the same call directly
			// from their own impact handlers via WearableService#resolveLazily.
			wearableService.onHitTaken(living, shooter, event.getDamage(), effectRunner);

			if (weapon instanceof GunWeapon gun) {
				DamageData dd         = gun.getDamageData();
				int        fireTicks  = wearableService.reduceFireTicks(dd.getFireTicks(), living);
				living.setFireTicks(fireTicks);

				// Damage.Armor_Damage (gate HF, §3) — guns only.
				wearableService.damageArmor(living, dd.getArmorDamage());
			}

			EffectContext effectCtx = EffectContext.builder()
			                                       .weapon(weapon)
			                                       .source(shooter)
			                                       .victim(living)
			                                       .impact(impactPt)
			                                       .damage(event.getDamage())
			                                       .distance(distance)
			                                       .zone(zone != null ? zone.name() : null)
			                                       .build();
			ctx.setHitEntity(true);
			effectRunner.run(weapon, EffectHook.ON_HIT, effectCtx);

			if (criticalHit) {
				effectRunner.run(weapon, EffectHook.ON_CRITICAL, effectCtx);
			}

			if (zone != null) {
				EffectHook zoneHook = switch (zone) {
					case HEAD -> EffectHook.ON_HEADSHOT;
					case ARMS -> EffectHook.ON_ARMS;
					case LEGS -> EffectHook.ON_LEGS;
					case FEET -> EffectHook.ON_FEET;
					case BODY -> null;
				};
				if (zoneHook != null) {
					effectRunner.run(weapon, zoneHook, effectCtx);
				}
				if (backHit) {
					effectRunner.run(weapon, EffectHook.ON_BACK, effectCtx);
				}
			}
		}
	}

	private static double zoneDelta(DamageData dd, BodyZone zone) {
		return switch (zone) {
			case HEAD -> dd.getHeadDamage();
			case BODY -> dd.getBodyDamage();
			case ARMS -> dd.getArmsDamage();
			case LEGS -> dd.getLegsDamage();
			case FEET -> dd.getFeetDamage();
		};
	}

	/**
	 * Sums the active {@code settings.yml Damage_Modifiers} percents for {@code living} (gate HF, §5). {@code
	 * Walking} has no cheap signal on Spigot and is deliberately unimplemented.
	 */
	// ponytail: Walking is skipped — no cheap "is this entity walking" signal on Spigot; add if a future gate needs it.
	private static double damageModifierPercent(LivingEntity living) {
		double percent = armorPoints(living) * BartizanSettings.getDamageModifierPerArmorPoint();

		if (living instanceof Player player) {
			if (player.isSneaking()) percent += BartizanSettings.getDamageModifierSneaking();
			if (player.isSprinting()) percent += BartizanSettings.getDamageModifierSprinting();
			if (player.isBlocking()) percent += BartizanSettings.getDamageModifierShielding();
		}
		if (!living.isOnGround()) {
			percent += BartizanSettings.getDamageModifierInMidair();
		}

		return percent;
	}

	private static double armorPoints(LivingEntity living) {
		Attribute armorAttribute = XAttribute.ARMOR.get();
		if (armorAttribute == null) return 0;

		AttributeInstance instance = living.getAttribute(armorAttribute);
		return instance != null ? instance.getValue() : 0;
	}

	/**
	 * Fires a block-only impact event so listeners can react to "weapon X struck block Y" without an entity being
	 * involved. Unlike entity impacts, this never applies damage by itself — block damage is handled separately by
	 * {@link #applyBlockBreak} via {@link BlockDamageManager}. Also spawns the cosmetic block-crack particle and
	 * fires {@link EffectHook#ON_BLOCK_HIT} for every un-cancelled block hit, gun or not.
	 */
	private void handleBlockImpact(Location impactPt, Block block, BlockFace face, RaytraceContext ctx) {
		WeaponRaytraceImpactEvent event = new WeaponRaytraceImpactEvent(
				ctx.getRequest().getWeapon(),
				ctx.getRequest().getShooter(),
				null, block, face, impactPt, 0.0, ctx.getState());
		Bukkit.getPluginManager().callEvent(event);
		if (event.isCancelled()) {
			return;
		}

		spawnBlockCrackParticles(impactPt, block);

		Weapon        weapon  = ctx.getRequest().getWeapon();
		EffectContext effectCtx = EffectContext.builder()
		                                       .weapon(weapon)
		                                       .source(ctx.getRequest().getShooter())
		                                       .impact(impactPt)
		                                       .distance(ctx.getRequest().getOrigin().distance(impactPt))
		                                       .build();
		effectRunner.run(weapon, EffectHook.ON_BLOCK_HIT, effectCtx);

		if (ctx.getRequest().getImpactHandler() != null) {
			ctx.getRequest().getImpactHandler().accept(event);
		}
	}

	/**
	 * Cosmetic block-crack particles at every block hit — {@code XParticle.BLOCK} resolves the legacy
	 * {@code BLOCK_CRACK}/current {@code BLOCK} rename across versions, sized with the struck block's own
	 * {@code BlockData} so the particle matches the block's texture.
	 * <p>
	 * // ponytail: hardcoded 8-particle/0.15 spread cosmetic, no per-weapon config key — add one if anyone asks.
	 */
	private void spawnBlockCrackParticles(Location impactPt, Block block) {
		World world = impactPt.getWorld();
		if (world == null) return;

		world.spawnParticle(XParticle.BLOCK.get(), impactPt, 8, 0.15, 0.15, 0.15, 0.0, block.getBlockData());
	}

	/**
	 * {@code Shoot.Sound.Flyby_*}: plays the fly-by sound to every online player in the shooter's world (except the
	 * shooter) whose eye location comes within {@code Flyby_Range} of the ray's path — the polyline formed by the
	 * raytrace origin followed by {@link RaytraceContext#getTracerSegments()}.
	 * <p>
	 * // ponytail: a ray that hits any entity skips flyby for the whole ray rather than excluding just the struck
	 * player — the ray's entity hit isn't attributable to one player here without listening for
	 * WeaponRaytraceImpactEvent; a penetrating shot that also grazes a second, unhit player is rare enough not to
	 * warrant that. Revisit if that's ever reported as wrong in practice.
	 */
	private void playFlybySounds(RaytraceContext ctx) {
		if (ctx.isHitEntity()) return;
		if (!ctx.getRequest().isPlayFlyby()) return;

		SoundData sounds = ctx.getRequest().getWeapon().getSoundData();
		if (sounds == null || sounds.getFlybyRange() <= 0) return;

		SoundEffect sound = sounds.getFlybyCustom() != null ? sounds.getFlybyCustom() : sounds.getFlybyDefault();
		if (sound == null) return;

		List<Location> segments = ctx.getTracerSegments();
		if (segments.isEmpty()) return;

		Location originLoc = ctx.getRequest().getOrigin();
		World    world      = originLoc.getWorld();
		if (world == null) return;

		double range = sounds.getFlybyRange();

		// Budget: skip players farther than range + the furthest point on the polyline from the origin before
		// doing exact segment math. The furthest point is not necessarily the longest single leg — on a
		// multi-leg ray (penetration, ricochet) the far end can be the SUM of legs travelled from the origin.
		double furthest = 0;
		for (Location point : segments) {
			furthest = Math.max(furthest, originLoc.distance(point));
		}
		double prefilterRadius = range + furthest;

		Vector       origin         = originLoc.toVector();
		List<Vector> segmentVectors = segments.stream().map(Location::toVector).toList();
		LivingEntity shooter        = ctx.getRequest().getShooter();

		for (Player player : world.getPlayers()) {
			if (player.equals(shooter)) continue;

			Vector eye = player.getEyeLocation().toVector();
			if (eye.distance(origin) > prefilterRadius) continue;

			if (distanceToPolyline(eye, origin, segmentVectors) <= range) {
				sound.playSound(player);
			}
		}
	}

	/**
	 * Shortest distance from {@code point} to the polyline {@code origin -> segments[0] -> segments[1] -> ...}.
	 * Pure math (no Bukkit world access) so it's unit-testable without mocking Bukkit — see
	 * {@code WeaponRaytracerFlybyMathTest}.
	 */
	static double distanceToPolyline(Vector point, Vector origin, List<Vector> segments) {
		double closest = Double.MAX_VALUE;
		Vector previous = origin;
		for (Vector segment : segments) {
			closest = Math.min(closest, pointToSegmentDistance(point, previous, segment));
			previous = segment;
		}
		return closest;
	}

	/**
	 * Shortest distance from {@code point} to the segment {@code [a, b]}.
	 */
	static double pointToSegmentDistance(Vector point, Vector a, Vector b) {
		Vector ab            = b.clone().subtract(a);
		double lengthSquared = ab.lengthSquared();
		if (lengthSquared < 1e-9) return point.distance(a);

		double t = point.clone().subtract(a).dot(ab) / lengthSquared;
		t = Math.max(0.0, Math.min(1.0, t));

		Vector closest = a.clone().add(ab.multiply(t));
		return point.distance(closest);
	}

	private void applyBlockBreak(Block block, Weapon weapon) {
		for (BlockBreakModifier mod : weapon.getModifiersData().getBreakBlocks()) {
			if (!mod.appliesTo(block.getType())) {
				continue;
			}
			blockDamageManager.applyDamage(block, mod);
			break;
		}
	}

	@Nullable
	private RicochetModifier matchingRicochet(Material material, RaytraceContext ctx) {
		if (!ctx.getState().canRicochet()) {
			return null;
		}
		for (RicochetModifier mod : ctx.getRequest().getWeapon().getModifiersData().getRicochets()) {
			if (mod.canBounceOff(material) && ctx.getState().getBounceCount() < mod.maxBounces()) {
				return mod;
			}
		}
		return null;
	}

	// ------------------------------------------------------------------
	// Tracer rendering
	// ------------------------------------------------------------------

	/**
	 * Walks {@code ctx.getTracerSegments()} and draws a particle line for each leg of the raytrace. The path is
	 * {@code [origin, segment[0], segment[1], ...]} so penetration and ricochet legs render as a contiguous trail. Two
	 * overlays may be drawn:
	 * <ul>
	 *   <li>A default gray dust line for any {@link GunWeapon} shot, restoring the legacy
	 *       gun-shoot visualization (point count comes from
	 *       {@code ProjectileData.getDistance()}).</li>
	 *   <li>An opt-in colored line driven by the weapon's {@link TracerModifier}, available to
	 *       any weapon type that configures one.</li>
	 * </ul>
	 */
	private void flushTracer(RaytraceContext ctx) {
		List<Location> segments = ctx.getTracerSegments();
		if (segments.isEmpty()) {
			return;
		}

		Weapon weapon = ctx.getRequest().getWeapon();

		// Default gun-shoot tracer: gray dust line, always rendered for guns so the bullet path is
		// visible even when no TracerModifier is configured.
		if (weapon instanceof GunWeapon gun) {
			Particle.DustOptions defaultOptions = new Particle.DustOptions(Color.GRAY, 0.5F);
			drawTracerLine(ctx, segments, defaultOptions, gun.getProjectileData().getDistance());
		}

		// Configured colored tracer (opt-in via TracerModifier).
		if (weapon.getModifiersData().hasTracer()) {
			TracerModifier tracer = weapon.getModifiersData().getTracer();
			Particle.DustOptions tracerOptions =
					new Particle.DustOptions(tracer.color(), tracer.particleSize());
			drawTracerLine(ctx, segments, tracerOptions, -1);
		}
	}

	/**
	 * Draws a single contiguous dust line through the tracer segments. When {@code fixedPointCount} is positive it is
	 * used directly as the per-leg particle count (matching the legacy "distance from {@code ProjectileData}"
	 * semantics); otherwise the count scales with the leg length.
	 */
	private void drawTracerLine(RaytraceContext ctx, List<Location> segments,
	                            Particle.DustOptions options, int fixedPointCount) {
		Particle dustParticle = XParticle.DUST.get();

		// The first leg starts at the visual muzzle (offset right of the shooter, per the weapon's configured
		// Shoot.Muzzle_Offset if any), not at the raytrace origin (which is the eye location). Subsequent legs
		// follow the actual ray.
		Location previous = WeaponMuzzle.compute(ctx.getRequest().getShooter(), ctx.getRequest().getDirection(),
		                                         ctx.getRequest().getWeapon());
		for (Location point : segments) {
			if (previous.getWorld() == null || !previous.getWorld().equals(point.getWorld())) {
				previous = point;
				continue;
			}
			int count = fixedPointCount > 0
			            ? fixedPointCount
			            : Math.max(2, (int) (previous.distance(point) * 4));
			ParticleUtil.spawnLine(previous, point, dustParticle, count, options);
			previous = point;
		}
	}

}
