package org.luckyraven.bartizan.weapon.action;

import com.cryptomorin.xseries.particles.XParticle;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.luckyraven.bartizan.api.event.WeaponBeamFireEvent;
import org.luckyraven.bartizan.api.event.WeaponEntityDamageEvent;
import org.luckyraven.bartizan.api.event.WeaponEntityDamageEvent.DamageKind;
import org.luckyraven.bartizan.api.event.WeaponShootEvent;
import org.luckyraven.bartizan.api.raytrace.RaytraceRequest;
import org.luckyraven.bartizan.api.raytrace.WeaponRaytracer;
import org.luckyraven.bartizan.api.weapon.BeamWeapon;
import org.luckyraven.bartizan.api.weapon.BodyZone;
import org.luckyraven.bartizan.api.weapon.dto.BeamData;
import org.luckyraven.bartizan.api.weapon.dto.EffectHook;
import org.luckyraven.bartizan.api.weapon.modifiers.BlockDamageManager;
import org.luckyraven.bartizan.api.weapon.modifiers.BreakMode;
import org.luckyraven.bartizan.api.weapon.modifiers.action.BlockBreakModifier;
import org.luckyraven.bartizan.effect.EffectContext;
import org.luckyraven.bartizan.effect.EffectRunner;
import org.luckyraven.bartizan.raytrace.BeamRenderer;
import org.luckyraven.bartizan.raytrace.HitZone;
import org.luckyraven.bartizan.raytrace.WeaponMuzzle;
import org.luckyraven.bartizan.util.EmptyMagSoundGate;
import org.luckyraven.bartizan.weapon.WeaponService;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Charge-then-release beam weapon action (weapons-roadmap.md gate {@code HC}, §3). Mirrors {@link BiologicalAction}:
 * {@link ChargeController} owns the charge cycle and invokes {@link #fire(Player, int)} as its release callback.
 * Also owns the charge-preview particle draw ({@link #previewTick}) since that is beam-specific — biological
 * weapons have no beam shape to preview. The preview piggybacks on {@code ChargeController}'s own timer via
 * {@link ChargeController.TickListener} rather than running a second timer of its own, so it starts and stops
 * exactly when the charge itself does.
 */
public class BeamAction {

	private final JavaPlugin         plugin;
	private final BeamWeapon         weapon;
	private final WeaponRaytracer    raytracer;
	private final WeaponService      weaponService;
	private final EffectRunner       effectRunner;
	private final BlockDamageManager blockDamageManager;

	public BeamAction(JavaPlugin plugin, BeamWeapon weapon, WeaponRaytracer raytracer, WeaponService weaponService,
	                  EffectRunner effectRunner, BlockDamageManager blockDamageManager) {
		this.plugin             = plugin;
		this.weapon             = weapon;
		this.raytracer          = raytracer;
		this.weaponService      = weaponService;
		this.effectRunner       = effectRunner;
		this.blockDamageManager = blockDamageManager;
	}

	/**
	 * Resolves the level actually fireable given the current magazine: the largest level whose ammo cost
	 * ({@code ammoPerLevel * level}) still fits in {@code magazine}, clamped to the requested {@code level}.
	 * Returns {@code 0} when even level 1 can't be afforded. {@code ammoPerLevel} is always {@code >= 1} —
	 * {@code BeamWeaponParser} clamps {@code Beam.Ammo_Per_Level} to that floor.
	 */
	static int affordableLevel(int magazine, int ammoPerLevel, int level) {
		return Math.min(level, magazine / ammoPerLevel);
	}

	/**
	 * {@code Base + Per_Level * (level - 1)}.
	 */
	static double damageForLevel(BeamData.BeamDamageData data, int level) {
		return data.base() + data.perLevel() * (level - 1);
	}

	/**
	 * Finalises a charged shot — {@link ChargeController}'s {@code onFire} callback for this weapon. A level of 0
	 * (released before reaching {@code Min_Level_To_Fire}) never reaches here.
	 */
	public void fire(Player player, int level) {
		if (level <= 0) return;

		BeamData beamData     = weapon.getBeam();
		int      ammoPerLevel = beamData.getAmmoPerLevel();
		boolean  tracksAmmo   = weapon.getReloadData() != null;
		int      actualLevel;

		if (!tracksAmmo) {
			// No Reload:/Ammunition: configured — infinite ammo, matches Weapon#isMagazineEmpty's own convention.
			actualLevel = level;
		} else {
			actualLevel = affordableLevel(weapon.getCurrentMagCapacity(), ammoPerLevel, level);
			if (actualLevel <= 0) {
				EmptyMagSoundGate.play(plugin, player, weapon, effectRunner);
				return;
			}
		}

		Location origin    = player.getEyeLocation();
		Vector   direction = origin.getDirection().normalize();

		// HK: both events fired, and checked, BEFORE the ammo block below — matches the "fire before
		// consumption" contract used across the other custom-path actions, so a listener cancelling either one
		// costs the shooter nothing (no magazine decrement to refund).
		WeaponBeamFireEvent fireEvent = new WeaponBeamFireEvent(weapon, player, actualLevel, origin, direction);
		Bukkit.getPluginManager().callEvent(fireEvent);
		if (fireEvent.isCancelled()) return;

		WeaponShootEvent shootEvent = new WeaponShootEvent(weapon, player);
		Bukkit.getPluginManager().callEvent(shootEvent);
		if (shootEvent.isCancelled()) return;

		if (tracksAmmo) {
			weapon.setCurrentMagCapacity(weapon.getCurrentMagCapacity() - ammoPerLevel * actualLevel);
			weaponService.persistHeldWeapon(weapon, player);
		}

		if (weapon.getRecoilData() != null) {
			weapon.getRecoil().applyRecoil(player);
			weapon.applyPush(player);
		}

		EffectContext shootCtx = EffectContext.shot(weapon, player, direction).level(actualLevel).build();
		effectRunner.run(weapon, EffectHook.ON_SHOOT, shootCtx);

		double damage = damageForLevel(beamData.getDamage(), actualLevel);

		// Visual/impact endpoint only — always the first solid block, even when Pierce.Blocks > 0 lets the damage
		// ray (below, via the raytracer's own block-penetration handling) continue past it. The beam's glow and
		// the ON_BEAM_FIRE hook always land here, not at the ray's actual final stop.
		World          world    = origin.getWorld();
		RayTraceResult blockHit = world != null
		                         ? world.rayTraceBlocks(origin, direction, beamData.getRange(),
		                                                FluidCollisionMode.NEVER, true)
		                         : null;
		Location endPoint = blockHit != null
		                   ? blockHit.getHitPosition().toLocation(world)
		                   : origin.clone().add(direction.clone().multiply(beamData.getRange()));

		AtomicReference<Block> scorchBlockRef = new AtomicReference<>();
		int                    fireLevel      = actualLevel;

		RaytraceRequest request = RaytraceRequest.builder()
		                                         .shooter(player)
		                                         .weapon(weapon)
		                                         .origin(origin)
		                                         .direction(direction)
		                                         .maxDistance(beamData.getRange())
		                                         .baseDamage(damage)
		                                         .hitboxExpansion(beamData.getWidth() / 2)
		                                         .maxIterations(64)
		                                         .impactHandler(event -> {
														 Block hitBlock = event.getHitBlock();
														 if (hitBlock != null) {
															 scorchBlockRef.set(hitBlock);
															 return;
														 }
														 if (!(event.getHitEntity() instanceof LivingEntity living)) {
															 return;
														 }

														 BodyZone hitZone = HitZone.of(
																 event.getImpactPoint().toVector(), living, direction).zone();
														 boolean headshot = hitZone == BodyZone.HEAD;
														 double dmg = event.getDamage()
														              + (headshot ? beamData.getDamage().head() : 0.0);

														 living.setNoDamageTicks(0);
														 living.setInvulnerable(false);
														 living.damage(dmg, player);

														 // HK: canonical WeaponEntityDamageEvent (DIRECT) for the beam's short-circuited impact
														 // handler, so StatsService/other listeners see beam hits the same way gun hits are seen.
														 Bukkit.getPluginManager().callEvent(
															 new WeaponEntityDamageEvent(weapon, living, dmg, player, weapon.getName(),
																 DamageKind.DIRECT, hitZone, origin.distance(event.getImpactPoint())));

														 double knockback = beamData.getDamage().knockback();
														 if (knockback != 0.0) {
															 living.setVelocity(direction.clone().multiply(knockback));
														 }

														 int fireTicks = beamData.getDamage().fireTicks();
														 if (fireTicks > 0) living.setFireTicks(fireTicks);

														 EffectContext hitCtx = EffectContext.builder()
														         .weapon(weapon)
														         .source(player)
														         .victim(living)
														         .impact(event.getImpactPoint())
														         .damage(dmg)
														         .distance(origin.distance(event.getImpactPoint()))
														         .level(fireLevel)
														         .build();

														 effectRunner.run(weapon, EffectHook.ON_HIT, hitCtx);
														 if (headshot) {
															 effectRunner.run(weapon, EffectHook.ON_HEADSHOT, hitCtx);
														 }
													 })
		                                         .build();

		boolean hitEntity = raytracer.fireInstant(request);
		if (!hitEntity) {
			effectRunner.run(weapon, EffectHook.ON_MISS, shootCtx);
		}

		EffectContext beamCtx = EffectContext.builder()
		                                     .weapon(weapon)
		                                     .source(player)
		                                     .impact(endPoint)
		                                     .level(actualLevel)
		                                     .build();
		effectRunner.run(weapon, EffectHook.ON_BEAM_FIRE, beamCtx);

		new BeamRenderer().render(plugin, world, WeaponMuzzle.compute(player, direction, weapon), endPoint,
		                         beamData.getRender());

		if (beamData.isScorchBlocks()) {
			Block block = scorchBlockRef.get();
			if (block != null) {
				blockDamageManager.applyDamage(block,
						new BlockBreakModifier(Set.of(block.getType()), 1, BreakMode.CRACK_ONLY));
			}
		}
	}

	/**
	 * {@link ChargeController.TickListener} callback: redraws the charge-preview beam segment — from the muzzle
	 * along the look vector, scaled by the current charge level — every {@code Preview.Interval} ticks. Lives and
	 * dies with the charge timer {@code ChargeController} already registers in {@code activeTasks}, so it never
	 * outlives a weapon swap or keeps drawing past {@code Auto_Fire_At_Max}.
	 */
	public void previewTick(Player player, int level, long tickCount) {
		if (level <= 0) return;

		BeamData.PreviewData preview = weapon.getBeam().getPreview();
		if (tickCount % Math.max(1, preview.interval()) != 0) return;

		drawPreview(player, level);
	}

	private void drawPreview(Player player, int level) {
		BeamData             beamData = weapon.getBeam();
		BeamData.PreviewData preview  = beamData.getPreview();

		Vector   direction = player.getEyeLocation().getDirection().normalize();
		Location muzzle    = WeaponMuzzle.compute(player, direction, weapon);
		World    world     = muzzle.getWorld();
		if (world == null) return;

		Particle particle = XParticle.of(preview.particle()).map(XParticle::get).orElse(null);
		if (particle != null) {
			Location end = muzzle.clone().add(direction.clone().multiply(preview.lengthPerLevel() * level));
			for (Location point : BeamRenderer.points(muzzle, end, 0.5)) {
				world.spawnParticle(particle, point, 1, 0, 0, 0, 0, null);
			}
		}

		if (preview.guideLine()) {
			Particle guideParticle = particle != null ? particle : XParticle.CRIT.get();
			if (guideParticle == null) return;

			Location farEnd = muzzle.clone().add(direction.clone().multiply(beamData.getRange()));
			for (Location point : BeamRenderer.points(muzzle, farEnd, 2.0)) {
				world.spawnParticle(guideParticle, point, 1, 0, 0, 0, 0, null);
			}
		}
	}

}
