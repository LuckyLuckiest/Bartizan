package org.luckyraven.bartizan.weapon.action;

import org.bukkit.*;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.util.Vector;
import org.luckyraven.keystone.timer.CountdownTimer;
import org.luckyraven.keystone.timer.RepeatingTimer;
import org.luckyraven.keystone.util.ParticleUtil;
import org.luckyraven.bartizan.api.weapon.dto.EffectHook;
import org.luckyraven.bartizan.api.weapon.dto.ExplosionData;
import org.luckyraven.bartizan.api.weapon.dto.ExplosionData.Detonation;
import org.luckyraven.bartizan.api.weapon.dto.ExplosionData.Trigger;
import org.luckyraven.bartizan.api.weapon.dto.ThrowableData;
import org.luckyraven.bartizan.api.event.WeaponEntityDamageEvent;
import org.luckyraven.bartizan.api.event.WeaponEntityDamageEvent.DamageKind;
import org.luckyraven.bartizan.api.event.WeaponShootEvent;
import org.luckyraven.bartizan.effect.EffectContext;
import org.luckyraven.bartizan.effect.EffectRunner;
import org.luckyraven.bartizan.fire.PluginFireRegistry;
import org.luckyraven.bartizan.listener.projectile.CosmeticTag;
import org.luckyraven.bartizan.raytrace.ExplosionHandler;
import org.luckyraven.bartizan.util.PotionEffectParser;
import org.luckyraven.bartizan.api.weapon.ThrowableType;
import org.luckyraven.bartizan.api.weapon.ThrowableWeapon;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * bartizan.md §1.6(3) (R1): {@code pendingKillerWeapon} and {@code pendingVehicleExplosionDamage} — the two
 * cross-plugin static maps the original Gangland class used to hand weapon-attribution data to
 * {@code WeaponDeathMessageContributor} and gadget's {@code CarDamageListener} — are deleted, along with the 1-tick
 * cleanup task that drained {@code pendingVehicleExplosionDamage}. Both consumers now read
 * {@link WeaponEntityDamageEvent#weaponName()} / {@link WeaponEntityDamageEvent#kind()} instead: the event is fired
 * once per living target (now inside the unified {@code ExplosionHandler}, gate {@code HI-a}) and, by the same
 * reasoning given the event's own javadoc ("hits a non-living entity such as a vehicle"), once per non-living
 * entity in the pre-registration loop below that used to populate {@code pendingVehicleExplosionDamage} —
 * recorded as a deviation in bartizan.md §7 task B13, since the checklist's own line citation only names the
 * living-entity call site and P3's {@code CarDamageListener} rewrite (out of this stream's scope) needs a firing
 * site for vehicles too.
 * <p>
 * Gate {@code HI-a} deletes the vanilla {@code World#createExplosion} blast this class used to fire alongside its
 * own damage loop (and the {@code vanillaBlastImmune} bookkeeping that existed only to undo that blast's own
 * entity damage for a protected victim) — the explosive branch of {@link #detonate} now routes through the same
 * {@code ExplosionHandler} rockets use.
 */
public class ThrowableAction {

	/**
	 * Entity UUIDs currently receiving programmatic explosion damage — used to bypass the event cancel guard. Kept
	 * static so WeaponInteract.onEntityDamage can access it without holding an instance. See {@link MeleeAction}'s
	 * javadoc on its own {@code pendingDamage} field for why this stays static rather than becoming a private
	 * instance field.
	 * <p>
	 * Gate {@code HI-a}: no longer populated. {@code ExplosionHandler#explode} wraps its own {@code target.damage}
	 * call in {@code WeaponRaytracer.setRaytraceDamageInProgress}, which already satisfies
	 * {@code WeaponInteract.onEntityDamage}'s cancel guard — this set's guard-bypass purpose is redundant for the
	 * explosive path. The field itself stays (still referenced by {@code WeaponInteract}, outside this gate's
	 * file list) so nothing that reads it needs to change.
	 */
	public static final Set<UUID> pendingDamage = ConcurrentHashMap.newKeySet();

	private final JavaPlugin         plugin;
	private final ThrowableWeapon    weapon;
	private final PluginFireRegistry fireRegistry;
	private final EffectRunner       effectRunner;

	public ThrowableAction(JavaPlugin plugin, ThrowableWeapon weapon, PluginFireRegistry fireRegistry,
	                       EffectRunner effectRunner) {
		this.plugin       = plugin;
		this.weapon       = weapon;
		this.fireRegistry = fireRegistry;
		this.effectRunner = effectRunner;
	}

	public void activate(Player player) {
		ThrowableData data = weapon.getThrowableData();

		// HK: WeaponShootEvent fired once per trigger pull, before the held stack is decremented below - cancelling
		// costs the caller nothing, matching the "fire before consumption" contract used across the other
		// custom-path actions.
		WeaponShootEvent shootEvent = new WeaponShootEvent(weapon, player);
		Bukkit.getPluginManager().callEvent(shootEvent);
		if (shootEvent.isCancelled()) return;

		// Detonation.Impact_When/Delay_After_Impact (gate HI-a) — everything else about the flight loop below is
		// unchanged; Fuse_Time (the fuseTimer further down) remains the fallback exactly as before when Impact_When
		// is empty (the legacy-parity default ExplosionSectionParser lowers for every throwable that doesn't
		// configure Detonation itself).
		Detonation   detonation = weapon.getExplosionData().getDetonation();
		Set<Trigger> impactWhen = detonation != null ? detonation.impactWhen() : Set.of();
		int          impactDelay = detonation != null ? detonation.delayAfterImpactTicks() : 0;

		if (player.getGameMode() != GameMode.CREATIVE) {
			decrementHeldStack(player);
		}

		World    world  = player.getWorld();
		Location eyeLoc = player.getEyeLocation();

		ItemStack visual = data.getDisplayItem() != null ?
		                   data.getDisplayItem().clone() :
		                   new ItemStack(weapon.getMaterial());
		Item grenade = world.dropItem(eyeLoc, visual);
		grenade.setPickupDelay(Integer.MAX_VALUE);
		CosmeticTag.mark(grenade);

		Vector throwVec = eyeLoc.getDirection().normalize().multiply(1.2).add(new Vector(0, 0.2, 0));
		grenade.setVelocity(throwVec);

		EffectContext shootCtx = EffectContext.shot(weapon, player, eyeLoc.getDirection()).build();
		effectRunner.run(weapon, EffectHook.ON_SHOOT, shootCtx);

		if (weapon.getRecoilData() != null) {
			weapon.getRecoil().applyRecoil(player);
			weapon.applyPush(player);
		}

		boolean[] wasOnGround       = {false};
		boolean[] stuck             = {false};
		int[]     bounceCount       = {0};
		int[]     bounceCooldown    = {0};
		int[]     tickCount         = {0};
		double[]  prevVelocityLenSq = {throwVec.lengthSquared()};
		boolean[] detonated         = {false};

		// Declared (but not started) before physicsTimer so physicsTimer's own body can cancel it on an early
		// impact-triggered detonation.
		CountdownTimer fuseTimer = new CountdownTimer(plugin, 0L, 1L, data.getFuseTime(), null, null, expired -> {
			if (detonated[0]) return;
			detonated[0] = true;
			Location blast = grenade.getLocation();
			grenade.remove();
			detonate(blast, data, player, world);
		});

		RepeatingTimer physicsTimer = new RepeatingTimer(plugin, 1L, time -> {
			if (detonated[0] || grenade.isDead()) {
				time.stop();
				return;
			}

			if (stuck[0]) {
				grenade.setVelocity(new Vector(0, 0, 0));
				ParticleUtil.spawnSmokeTrail(grenade.getLocation());
				return;
			}

			tickCount[0]++;
			if (bounceCooldown[0] > 0) bounceCooldown[0]--;
			boolean onGround   = grenade.isOnGround();
			boolean justLanded = onGround && !wasOnGround[0];

			// Detect wall/ceiling collision: item was moving last tick but velocity
			// is now near-zero without having hit the floor (isOnGround is false).
			// Skip the first tick — the item entity's velocity isn't always reflected by
			// getVelocity() immediately after setVelocity() on the very first tick.
			// Also suppress detection for a few ticks after a bounce so the new bounce
			// velocity has time to register and doesn't trigger a false wall-hit.
			Vector curVel   = grenade.getVelocity();
			double curLenSq = curVel.lengthSquared();
			boolean hitSurface = tickCount[0] > 1 && !onGround && curLenSq < 0.005 && prevVelocityLenSq[0] > 0.01 &&
			                     bounceCooldown[0] == 0;
			// For sticky grenades: also catch angled wall hits where the reflected velocity is
			// reduced but not near-zero. If velocity magnitude squared drops to < 40 % of the
			// previous tick's value, a collision absorbed kinetic energy → stick immediately.
			boolean stickyCollision = data.isSticky() && tickCount[0] > 2 && bounceCooldown[0] == 0 &&
			                          prevVelocityLenSq[0] > 0.05 && curLenSq < prevVelocityLenSq[0] * 0.40;
			prevVelocityLenSq[0] = curLenSq;

			if (justLanded || hitSurface || stickyCollision) {
				if (data.isSticky()) {
					stuck[0] = true;
					grenade.setGravity(false);
					grenade.setVelocity(new Vector(0, 0, 0));
				} else if (data.isBounces() && bounceCount[0] < data.getMaxBounces()) {
					bounceCount[0]++;
					double bounceHeight = Math.max(0.15, throwVec.length() * 0.45 * Math.pow(0.65, bounceCount[0]));
					Vector v            = grenade.getVelocity().clone();
					v.setY(bounceHeight);
					v.setX(v.getX() * 0.85);
					v.setZ(v.getZ() * 0.85);
					grenade.setVelocity(v);
					bounceCooldown[0] = 3;
				}
			}

			// Detonation.Impact_When (gate HI-a): landing OR a wall/ceiling hit (BLOCK — review fix: previously
			// only justLanded counted, so a grenade thrown straight into a wall never detonated on Impact_When:
			// [block]) or entity contact (ENTITY, ignoring the thrower for the first 5 ticks so the grenade
			// doesn't detonate in the thrower's own hitbox at launch — see hasNearbyLivingEntity's javadoc;
			// review fix: this used to also skip the whole ENTITY check for those first 5 ticks via an outer
			// tickCount[0] > 5 guard, making the inner per-tick exclusion dead code and, past tick 5, letting the
			// thrower detonate their own grenade on contact).
			boolean impactTriggered = ((justLanded || hitSurface || stickyCollision) && impactWhen.contains(Trigger.BLOCK)) ||
			                          (impactWhen.contains(Trigger.ENTITY) &&
			                           hasNearbyLivingEntity(grenade, player, tickCount[0]));

			if (impactTriggered) {
				detonated[0] = true;
				time.stop();
				fuseTimer.stop();

				Location blast = grenade.getLocation();
				if (impactDelay > 0) {
					grenade.setVelocity(new Vector(0, 0, 0));
					grenade.setGravity(false);
					new CountdownTimer(plugin, 0L, 1L, impactDelay, null, null, expired -> {
						grenade.remove();
						detonate(blast, data, player, world);
					}).start(false);
				} else {
					grenade.remove();
					detonate(blast, data, player, world);
				}
				return;
			}

			wasOnGround[0] = onGround;
			ParticleUtil.spawnSmokeTrail(grenade.getLocation());
		});

		physicsTimer.start(false);
		fuseTimer.start(false);
	}

	/**
	 * {@code Detonation.Impact_When: [entity]} contact check — a living entity within 0.6 blocks of the grenade,
	 * excluding the thrower for the first 5 ticks of flight.
	 */
	private boolean hasNearbyLivingEntity(Item grenade, Player thrower, int tickCount) {
		for (Entity nearby : grenade.getNearbyEntities(0.6, 0.6, 0.6)) {
			if (!(nearby instanceof LivingEntity)) continue;
			if (tickCount <= 5 && nearby.equals(thrower)) continue;
			return true;
		}
		return false;
	}

	private void decrementHeldStack(Player player) {
		ItemStack held = player.getInventory().getItemInMainHand();

		if (held.getAmount() > 1) {
			held.setAmount(held.getAmount() - 1);
			player.getInventory().setItemInMainHand(held);
			return;
		}

		player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
	}

	private void detonate(Location center, ThrowableData data, Player player, World world) {
		// dispatch on throwable type — only EXPLOSIVE goes through the unified explosion handler
		ThrowableType type = data.getType() != null ? data.getType() : ThrowableType.EXPLOSIVE;
		switch (type) {
			case STUN -> {
				detonateStun(center, data, player, world);
				return;
			}
			case SMOKE -> {
				spawnSmokeCloud(center, data, world);
				return;
			}
			case EXPLOSIVE -> { /* fall through to the unified explosion handler below */ }
		}

		ExplosionHandler handler = ExplosionHandler.get();
		if (handler == null) return;

		// Modifiers.Flat_Damage bonus (pre-dates this gate) folded into a clone so ExplosionHandler itself stays
		// ignorant of any per-weapon damage bonus mechanics — mirrors how the old code added it to totalDmg.
		ExplosionData explosionData = weapon.getExplosionData().clone();
		if (weapon.getModifiersData().hasFlatDamage()) {
			explosionData.setDamage(explosionData.getDamage() + weapon.getModifiersData().getFlatDamage().bonus());
		}

		double radius   = explosionData.getRadius();
		double radiusSq = radius * radius;

		// Fire the canonical WeaponEntityDamageEvent for every non-living entity (vehicles) in range so a listener
		// such as CarDamageListener can react — unchanged from before gate HI-a deletes the vanilla
		// World#createExplosion call itself (that call never covered non-living entities on its own; this
		// pre-emptive notification did, and still does).
		if (explosionData.getDamage() > 0) {
			for (Entity nearby : world.getNearbyEntities(center, radius, radius, radius)) {
				if (nearby instanceof LivingEntity) continue;
				if (nearby.getLocation().distanceSquared(center) > radiusSq) continue;
				Bukkit.getPluginManager().callEvent(new WeaponEntityDamageEvent(
						weapon, nearby, explosionData.getDamage(), player, weapon.getName(), DamageKind.EXPLOSION));
			}
		}

		if (explosionData.getFireTicks() > 0) {
			placeTempFire(center, radius, explosionData.getFireTicks(), world);
		}

		handler.explode(weapon, explosionData, center, player, 0);
	}

	/**
	 * Scatters short-lived {@link Material#FIRE} blocks in a sphere of {@code radius} around {@code center}. Each fire
	 * block is placed only on top of solid blocks (in an air space) and is automatically removed after
	 * {@code fireTicks} ticks so underlying blocks are never consumed.
	 */
	private void placeTempFire(Location center, double radius, int fireTicks, World world) {
		int                          r        = (int) Math.ceil(radius);
		double                       radiusSq = radius * radius;
		List<org.bukkit.block.Block> placed   = new ArrayList<>();

		for (int x = -r; x <= r; x++) {
			for (int y = -r; y <= r; y++) {
				for (int z = -r; z <= r; z++) {
					if (x * x + y * y + z * z > radiusSq) continue;
					org.bukkit.block.Block candidate = world.getBlockAt(
							center.getBlockX() + x,
							center.getBlockY() + y,
							center.getBlockZ() + z);
					if (candidate.getType() != Material.AIR) continue;
					org.bukkit.block.Block below = candidate.getRelative(org.bukkit.block.BlockFace.DOWN);
					if (!below.getType().isSolid()) continue;
					candidate.setType(Material.FIRE);
					fireRegistry.track(candidate);
					placed.add(candidate);
				}
			}
		}

		if (!placed.isEmpty()) {
			plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
				for (org.bukkit.block.Block b : placed) {
					if (b.getType() == Material.FIRE) {
						b.setType(Material.AIR);
					}
					fireRegistry.untrack(b);
				}
			}, fireTicks);
		}
	}

	/**
	 * Stun (flashbang) detonation: no damage, no explosion. Renders a convincing flash-bang burst (bright white dust,
	 * explosion emitter, sparks, smoke) and plays a high-pitched bang sound, then applies the configured potion effects
	 * to every living entity within {@code explosionRadius}, including the thrower.
	 */
	private void detonateStun(Location center, ThrowableData data, Player player, World world) {
		double radius = data.getExplosionRadius();
		ParticleUtil.spawnFlashbangBurst(center, radius);

		EffectContext explodeCtx = EffectContext.builder().weapon(weapon).source(player).impact(center).build();
		effectRunner.run(weapon, EffectHook.ON_EXPLODE, explodeCtx);

		List<PotionEffect> effects = PotionEffectParser.parseList(data.getEffects());
		if (effects.isEmpty()) return;

		double radiusSq = radius * radius;

		for (Entity nearby : world.getNearbyEntities(center, radius, radius, radius)) {
			if (!(nearby instanceof LivingEntity target)) continue;
			if (target.getLocation().distanceSquared(center) > radiusSq) continue;
			for (PotionEffect effect : effects) target.addPotionEffect(effect);
		}
	}

	/**
	 * Smoke cloud detonation: no damage. Spawns a {@link RepeatingTimer} that emits smoke particles in a sphere of
	 * {@code cloudRadius} (or {@code explosionRadius} when zero) and re-applies the configured potion effects to all
	 * living entities inside the cloud every 10 ticks. The timer self-cancels after {@code cloudDuration} ticks.
	 */
	private void spawnSmokeCloud(Location center, ThrowableData data, World world) {
		List<PotionEffect> effects = PotionEffectParser.parseList(data.getEffects());

		double radius   = data.getCloudRadius() > 0 ? data.getCloudRadius() : data.getExplosionRadius();
		double radiusSq = radius * radius;
		int    duration = data.getCloudDuration();

		// initial detonation burst — sudden outward smoke expansion
		ParticleUtil.spawnSmokeCloudBurst(center, radius);

		int[] elapsed = {0};
		RepeatingTimer cloud = new RepeatingTimer(plugin, 1L, time -> {
			if (elapsed[0] >= duration) {
				time.stop();
				return;
			}
			elapsed[0]++;

			// dense smoke cloud that fades naturally toward expiration
			double intensity = 1.0 - ((double) elapsed[0] / duration);
			ParticleUtil.spawnDenseSmokeCloud(center, radius, intensity);

			// refresh effects on entities inside the cloud every 10 ticks
			if (elapsed[0] % 10 != 0 || effects.isEmpty()) return;
			for (Entity nearby : world.getNearbyEntities(center, radius, radius, radius)) {
				if (!(nearby instanceof LivingEntity target)) continue;
				if (target.getLocation().distanceSquared(center) > radiusSq) continue;
				for (PotionEffect effect : effects) target.addPotionEffect(effect);
			}
		});

		cloud.start(false);
	}

}
