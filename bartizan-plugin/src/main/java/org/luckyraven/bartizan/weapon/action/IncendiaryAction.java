package org.luckyraven.bartizan.weapon.action;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;
import org.luckyraven.keystone.item.ItemBuilder;
import org.luckyraven.keystone.timer.RepeatingTimer;
import org.luckyraven.keystone.util.ParticleUtil;
import org.luckyraven.bartizan.weapon.WeaponService;
import org.luckyraven.bartizan.api.event.WeaponEntityDamageEvent;
import org.luckyraven.bartizan.api.event.WeaponEntityDamageEvent.DamageKind;
import org.luckyraven.bartizan.api.event.WeaponShootEvent;
import org.luckyraven.bartizan.api.weapon.dto.EffectHook;
import org.luckyraven.bartizan.api.weapon.dto.IncendiaryData;
import org.luckyraven.bartizan.api.event.WeaponRaytraceImpactEvent;
import org.luckyraven.bartizan.effect.EffectContext;
import org.luckyraven.bartizan.effect.EffectRunner;
import org.luckyraven.bartizan.fire.PluginFireRegistry;
import org.luckyraven.bartizan.listener.WeaponInteract;
import org.luckyraven.bartizan.api.raytrace.RaytraceRequest;
import org.luckyraven.bartizan.raytrace.WeaponMuzzle;
import org.luckyraven.bartizan.api.raytrace.WeaponRaytracer;
import org.luckyraven.bartizan.util.EmptyMagSoundGate;
import org.luckyraven.bartizan.api.weapon.IncendiaryWeapon;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public class IncendiaryAction {

	/**
	 * Entity UUIDs receiving attributed fire damage — bypasses WeaponInteract's cancel guard so the combat tracker
	 * stays updated (enabling death attribution and flat-damage application).
	 */
	public static final Set<UUID> pendingDamage = ConcurrentHashMap.newKeySet();

	private final JavaPlugin         plugin;
	private final WeaponService      weaponService;
	private final IncendiaryWeapon   weapon;
	private final WeaponRaytracer    raytracer;
	private final PluginFireRegistry fireRegistry;
	private final EffectRunner       effectRunner;

	public IncendiaryAction(JavaPlugin plugin, WeaponService weaponService, IncendiaryWeapon weapon,
	                        WeaponRaytracer raytracer, PluginFireRegistry fireRegistry, EffectRunner effectRunner) {
		this.plugin        = plugin;
		this.weaponService = weaponService;
		this.weapon        = weapon;
		this.raytracer     = raytracer;
		this.fireRegistry  = fireRegistry;
		this.effectRunner  = effectRunner;
	}

	/**
	 * Fires one cone burst forward — used by both SINGLE (one press → one cone) and AUTO (driven by a
	 * {@link RepeatingTimer} in {@link WeaponInteract}). Returns {@code true} if the burst was actually fired,
	 * {@code false} if blocked by ammo/durability state — callers in AUTO mode use this to break the loop when the
	 * magazine empties.
	 */
	public boolean fireOnce(Player player) {
		if (weapon.isBroken()) {
			EmptyMagSoundGate.play(plugin, player, weapon, effectRunner);
			return false;
		}

		IncendiaryData data       = weapon.getIncendiaryData();
		boolean        tracksAmmo = weapon.getAmmunitionData() != null;

		if (tracksAmmo && weapon.isMagazineEmpty()) {
			EmptyMagSoundGate.play(plugin, player, weapon, effectRunner);
			return false;
		}

		// HK: WeaponShootEvent fired once per trigger pull, before fuel is consumed below (sprayFire), so
		// cancelling costs the caller nothing — matches the "fire before consumption" contract used across the
		// other custom-path actions.
		WeaponShootEvent shootEvent = new WeaponShootEvent(weapon, player);
		Bukkit.getPluginManager().callEvent(shootEvent);
		if (shootEvent.isCancelled()) return false;

		// shoot feedback
		EffectContext shootCtx = EffectContext.shot(weapon, player, player.getEyeLocation().getDirection()).build();
		effectRunner.run(weapon, EffectHook.ON_SHOOT, shootCtx);

		sprayFire(player, data, tracksAmmo);
		return true;
	}

	// --- Per-tick spray ---

	private void sprayFire(Player player, IncendiaryData data, boolean tracksAmmo) {
		ItemBuilder heldWeapon = weaponService.getHeldWeaponItem(player);
		if (heldWeapon == null) {
			return;
		}

		int slot = player.getInventory().getHeldItemSlot();

		// consume fuel
		if (tracksAmmo) weapon.consumeShot();

		// update ammo counter in display name
		weapon.updateWeaponData(heldWeapon);

		// durability on shot
		short onShot = weapon.getDurabilityData().getOnShot();
		if (onShot > 0) weapon.decreaseDurability(heldWeapon, onShot);

		// push updated item to inventory
		weapon.updateWeapon(player, heldWeapon, slot);

		// recoil and push per tick
		if (weapon.getRecoilData() != null) {
			weapon.getRecoil().applyRecoil(player);
			weapon.applyPush(player);
		}

		// fire spray
		Location eye    = player.getEyeLocation();
		Vector   dir    = eye.getDirection().normalize();
		Location muzzle = WeaponMuzzle.compute(player, dir, weapon);

		double flatBonus = weapon.getModifiersData().hasFlatDamage() ?
		                   weapon.getModifiersData().getFlatDamage().bonus() :
		                   0.0;

		ParticleUtil.spawnFlameCone(muzzle, dir, data.getRange(), data.getConeAngle());

		fireCone(player, dir, data, flatBonus);
	}

	/**
	 * Sprays a cone of rays through the unified raytracer. Each ray reports its impact via a custom
	 * {@link RaytraceRequest#getImpactHandler()} that applies fire ticks to LivingEntity hits and fires
	 * {@link WeaponRaytraceImpactEvent} as the canonical hook for vehicle / NPC reactions.
	 *
	 * <p>{@code sprayed} is shared across every ray of this one spray tick (mirrors {@code MeleeAction}'s
	 * {@code hitInThisSwing}) so a target standing inside the cone, hit by several of the {@code rays} rays, is
	 * only damaged/credited once per tick instead of once per ray.
	 */
	private void fireCone(Player player, Vector dir, IncendiaryData data, double flatBonus) {
		double halfAngle = Math.toRadians(data.getConeAngle() / 2.0);
		int    rays      = Math.max(4, (int) (data.getConeAngle() / 8));

		Vector perp1 = dir.clone().crossProduct(new Vector(0, 1, 0));
		if (perp1.lengthSquared() < 0.001) {
			perp1 = dir.clone().crossProduct(new Vector(1, 0, 0));
		}
		perp1.normalize();
		Vector perp2 = dir.clone().crossProduct(perp1).normalize();

		ThreadLocalRandom rng     = ThreadLocalRandom.current();
		Set<UUID>         sprayed = new HashSet<>();

		for (int r = 0; r < rays; r++) {
			double theta = rng.nextDouble() * halfAngle;
			double phi   = rng.nextDouble() * 2 * Math.PI;
			Vector rayDir = dir.clone()
			                   .add(perp1.clone().multiply(Math.sin(theta) * Math.cos(phi)))
			                   .add(perp2.clone().multiply(Math.sin(theta) * Math.sin(phi)))
			                   .normalize();

			RaytraceRequest request = RaytraceRequest.builder()
			                                         .shooter(player)
			                                         .weapon(weapon)
			                                         .origin(player.getEyeLocation())
			                                         .direction(rayDir)
			                                         .maxDistance(data.getRange())
			                                         .baseDamage(flatBonus > 0 ? flatBonus : 0.001)
			                                         .hitboxExpansion(0.3)
			                                         .maxIterations(1)
			                                         .impactHandler(
														 event -> applyIncendiaryImpact(event, data, flatBonus, sprayed))
			                                         .build();

			raytracer.fireInstant(request);
		}
	}

	private void applyIncendiaryImpact(WeaponRaytraceImpactEvent event, IncendiaryData data, double flatBonus,
	                                   Set<UUID> sprayed) {
		Entity hit = event.getHitEntity();
		if (hit == null) {
			Block     hitBlock = event.getHitBlock();
			BlockFace face     = event.getHitBlockFace();
			if (hitBlock != null && face != null) {
				Block fireBlock = hitBlock.getRelative(face);
				if (fireBlock.getType() == Material.AIR) {
					fireBlock.setType(Material.FIRE);
					fireRegistry.track(fireBlock);
					plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
						if (fireBlock.getType() == Material.FIRE) {
							fireBlock.setType(Material.AIR);
						}
						fireRegistry.untrack(fireBlock);
					}, data.getFireDuration());
				}
			}
			return;
		}

		if (hit instanceof LivingEntity target) {
			// One hit per target per spray tick, regardless of how many of the cone's rays land on it.
			if (!sprayed.add(target.getUniqueId())) return;

			// Always attribute damage to the shooter so getKiller() is set and the death message
			// is correctly assigned. When there is no configured flat bonus a sub-tick amount
			// (0.001) is used so the combat tracker is updated without meaningfully changing
			// health.
			target.setFireTicks(data.getFireDuration());
			double attributed = flatBonus > 0 ? flatBonus : 0.001;
			target.setNoDamageTicks(0);
			double healthBefore = target.getHealth();
			pendingDamage.add(target.getUniqueId());
			target.damage(attributed, event.getShooter());

			// If health didn't decrease, a protection plugin blocked the damage (same "damageBlocked" shape as
			// WeaponRaytracerImpl.handleEntityImpact) — skip the event below.
			boolean damageBlocked = target.isValid() && !target.isDead() && target.getHealth() >= healthBefore;

			// HK: canonical WeaponEntityDamageEvent (FIRE) after damage is applied, player shooters only — the
			// cone spray has no single travel direction to run HitZone.of against, so zone stays null.
			if (!damageBlocked && event.getShooter() instanceof Player player) {
				Bukkit.getPluginManager().callEvent(
						new WeaponEntityDamageEvent(weapon, target, attributed, player, weapon.getName(),
						                            DamageKind.FIRE, null,
						                            player.getEyeLocation().distance(event.getImpactPoint())));
			}
		}

		// Non-living entity (vehicle, etc.). The unified WeaponRaytraceImpactEvent has already
		// fired with damage = flatBonus (set in the request), so CarDamageListener picks it up via
		// its WeaponRaytraceImpactEvent handler. Nothing to do here.
	}

}
