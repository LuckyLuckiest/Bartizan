package org.luckyraven.bartizan.weapon.action;

import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.luckyraven.keystone.util.ParticleUtil;
import org.luckyraven.bartizan.api.weapon.dto.EffectHook;
import org.luckyraven.bartizan.api.weapon.dto.MeleeData;
import org.luckyraven.bartizan.api.event.WeaponRaytraceImpactEvent;
import org.luckyraven.bartizan.api.weapon.modifiers.action.ArmorPiercingModifier;
import org.luckyraven.bartizan.api.raytrace.RaytraceRequest;
import org.luckyraven.bartizan.api.raytrace.WeaponRaytracer;
import org.luckyraven.bartizan.api.weapon.MeleeWeapon;
import org.luckyraven.bartizan.effect.EffectContext;
import org.luckyraven.bartizan.effect.EffectRunner;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class MeleeAction {

	/**
	 * Entity UUIDs currently receiving programmatic melee damage — used to bypass the event cancel guard in
	 * WeaponInteract. Kept static so WeaponInteract.onEntityDamage can access it without holding an instance.
	 *
	 * <p>Deviation from bartizan.md §1.6(3) (recorded bartizan.md §7, task B13): the checklist describes
	 * {@code ThrowableAction}'s own {@code pendingDamage} field as "internal to the weapon module ... keep it as a
	 * private instance field, do not expose it." Read literally that would make it unreadable by
	 * {@code WeaponInteract.onEntityDamage}, which accesses {@code MeleeAction.pendingDamage} /
	 * {@code ThrowableAction.pendingDamage} / {@code IncendiaryAction.pendingDamage} by static class reference today
	 * — there is no per-action instance held anywhere for it to read an instance field from (a fresh action object is
	 * created per swing/throw/burst). Kept exactly as it is today: {@code public static final Set<UUID>}, package
	 * renamed only, so the existing cross-class static-drain pattern in {@code WeaponInteract} keeps working
	 * unchanged.
	 */
	public static final Set<UUID> pendingDamage = ConcurrentHashMap.newKeySet();

	private final MeleeWeapon     weapon;
	private final WeaponRaytracer raytracer;
	private final Map<UUID, Long> cooldowns;
	private final EffectRunner    effectRunner;

	public MeleeAction(MeleeWeapon weapon, WeaponRaytracer raytracer, Map<UUID, Long> cooldowns,
	                   EffectRunner effectRunner) {
		this.weapon       = weapon;
		this.raytracer    = raytracer;
		this.cooldowns    = cooldowns;
		this.effectRunner = effectRunner;
	}

	/**
	 * Activates a melee swing. Spawns a slash arc regardless of hit.
	 *
	 * @return true if at least one entity was hit
	 */
	public boolean activate(Player player) {
		MeleeData data = weapon.getMeleeData();

		// empty-mag guard — only applies to melee weapons with ammo configured
		if (weapon.getReloadData() != null && weapon.isMagazineEmpty()) {
			EffectContext emptyCtx = EffectContext.builder().weapon(weapon).source(player).build();
			effectRunner.run(weapon, EffectHook.ON_EMPTY, emptyCtx);
			return false;
		}

		UUID weaponUuid = weapon.getUuid();
		long now        = System.currentTimeMillis();
		long cooldownMs = data.getCooldown() * 50L;

		Long lastSwing = cooldowns.get(weaponUuid);
		if (lastSwing != null && now - lastSwing < cooldownMs) {
			return false;
		}
		cooldowns.put(weaponUuid, now);

		Vector lookDir = player.getEyeLocation().getDirection().normalize();
		double range   = data.getRange();

		double flatBonus = weapon.getModifiersData().hasFlatDamage()
		                   ? weapon.getModifiersData().getFlatDamage().bonus() : 0.0;
		double baseDmg = data.getDamage() + flatBonus;

		ArmorPiercingModifier ap = weapon.getModifiersData().getArmorPiercing();

		// Multi-ray cone melee swing routed through the unified raytracer. Each ray is short and
		// fat (large hitboxExpansion) so the swing approximates the legacy 60° arc but uses the
		// shared event/modifier pipeline. Hits are deduplicated so a wide entity standing in
		// front of multiple rays only takes one hit per swing.
		Set<UUID> hitInThisSwing = new HashSet<>();

		Vector perp1 = lookDir.clone().crossProduct(new Vector(0, 1, 0));
		if (perp1.lengthSquared() < 0.001) {
			perp1 = lookDir.clone().crossProduct(new Vector(1, 0, 0));
		}
		perp1.normalize();
		Vector perp2 = lookDir.clone().crossProduct(perp1).normalize();

		// 5 rays: centre + 4 around it for a forgiving cone (matches the existing 60° feel)
		Vector[] rayDirs = {
				lookDir,
				lookDir.clone().add(perp1.clone().multiply(0.3)).normalize(),
				lookDir.clone().add(perp1.clone().multiply(-0.3)).normalize(),
				lookDir.clone().add(perp2.clone().multiply(0.3)).normalize(),
				lookDir.clone().add(perp2.clone().multiply(-0.3)).normalize()
		};

		for (Vector rayDir : rayDirs) {
			RaytraceRequest request = RaytraceRequest.builder()
			                                         .shooter(player)
			                                         .weapon(weapon)
			                                         .origin(player.getEyeLocation())
			                                         .direction(rayDir)
			                                         .maxDistance(range)
			                                         .baseDamage(baseDmg)
			                                         .hitboxExpansion(0.5)
			                                         .maxIterations(1)
			                                         .impactHandler(event -> applyMeleeImpact(
														 event, hitInThisSwing, baseDmg, ap, lookDir,
					                                         data.getKnockback()))
			                                         .build();

			raytracer.fireInstant(request);
		}

		boolean hit = !hitInThisSwing.isEmpty();

		// slash effect always plays on swing (not just on hit)
		ParticleUtil.spawnSlashArc(player.getLocation(), lookDir, range * 0.6);

		// swing feedback and recoil always apply on swing
		EffectContext shootCtx = EffectContext.shot(weapon, player, lookDir).build();
		effectRunner.run(weapon, EffectHook.ON_SHOOT, shootCtx);

		if (weapon.getRecoilData() != null) {
			weapon.getRecoil().applyRecoil(player);
			weapon.applyPush(player);
		}

		if (!hit) {
			effectRunner.run(weapon, EffectHook.ON_MISS, shootCtx);
		}

		return hit;
	}

	/**
	 * Applies melee damage to an entity hit by one of the cone rays. Deduplicates per-swing so a wide entity in front
	 * of multiple rays only takes one hit. Mirrors the legacy armor-piercing split (one armored hit + one bypass hit)
	 * for AP modifiers.
	 */
	private void applyMeleeImpact(WeaponRaytraceImpactEvent event, Set<UUID> hitInThisSwing, double baseDmg,
	                              ArmorPiercingModifier ap, Vector lookDir, double knockback) {
		Entity hitEntity = event.getHitEntity();
		if (!(hitEntity instanceof LivingEntity target)) return;
		if (target.isDead()) return;
		if (!hitInThisSwing.add(target.getUniqueId())) return;

		Player player = event.getShooter() instanceof Player p ? p : null;

		if (ap != null && ap.armorBypass() > 0) {
			double armoredDmg = baseDmg * (1.0 - ap.armorBypass());
			double pierceDmg  = baseDmg * ap.armorBypass();
			pendingDamage.add(target.getUniqueId());
			target.damage(armoredDmg, player);
			if (!target.isDead() && pierceDmg > 0) {
				pendingDamage.add(target.getUniqueId());
				target.damage(pierceDmg);
			}
		} else {
			pendingDamage.add(target.getUniqueId());
			target.damage(baseDmg, player);
		}

		if (target.isDead() || knockback <= 0) return;
		Vector kb = lookDir.clone().multiply(knockback);
		target.setVelocity(target.getVelocity().add(kb));
	}

}
