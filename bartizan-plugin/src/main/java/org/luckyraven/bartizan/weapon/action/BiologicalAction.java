package org.luckyraven.bartizan.weapon.action;

import org.bukkit.Bukkit;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.luckyraven.keystone.util.ActionBarManager;
import org.luckyraven.bartizan.api.event.WeaponEntityDamageEvent;
import org.luckyraven.bartizan.api.event.WeaponEntityDamageEvent.DamageKind;
import org.luckyraven.bartizan.api.event.WeaponShootEvent;
import org.luckyraven.bartizan.api.weapon.dto.BiologicalData;
import org.luckyraven.bartizan.api.weapon.dto.EffectHook;
import org.luckyraven.bartizan.effect.EffectContext;
import org.luckyraven.bartizan.effect.EffectRunner;
import org.luckyraven.bartizan.api.raytrace.RaytraceRequest;
import org.luckyraven.bartizan.api.raytrace.WeaponRaytracer;
import org.luckyraven.bartizan.status.StatusEffectService;
import org.luckyraven.bartizan.util.PotionEffectParser;
import org.luckyraven.bartizan.api.weapon.BiologicalWeapon;
import org.luckyraven.bartizan.weapon.WeaponService;
import org.luckyraven.bartizan.wearable.WearableService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Charged single-shot raytrace weapon. Charging (the timer, the level, the action-bar meter, the release trigger)
 * is owned by {@link ChargeController}; this class only finalises the shot once a charge level is released — see
 * {@link #fire(Player, int)}.
 */
public class BiologicalAction {

	private final BiologicalWeapon    weapon;
	private final WeaponRaytracer     raytracer;
	private final EffectRunner        effectRunner;
	private final StatusEffectService statusService;
	private final WeaponService       weaponService;

	public BiologicalAction(BiologicalWeapon weapon, WeaponRaytracer raytracer, EffectRunner effectRunner,
	                        StatusEffectService statusService, WeaponService weaponService) {
		this.weapon        = weapon;
		this.raytracer     = raytracer;
		this.effectRunner  = effectRunner;
		this.statusService = statusService;
		this.weaponService = weaponService;
	}

	/**
	 * Finalises a charged shot at the given level — {@link ChargeController}'s {@code onFire} callback for this
	 * weapon. A level of 0 (released before reaching {@code Min_Level_To_Fire}) never reaches here.
	 */
	public void fire(Player player, int level) {
		if (level <= 0) return;

		// HK: WeaponShootEvent fired once per trigger pull, before ammo is consumed below - cancelling costs
		// the caller nothing, matching the "fire before consumption" contract used across the other custom-path
		// actions.
		WeaponShootEvent shootEvent = new WeaponShootEvent(weapon, player);
		Bukkit.getPluginManager().callEvent(shootEvent);
		if (shootEvent.isCancelled()) return;

		if (!weapon.consumeShot()) return;
		weaponService.persistHeldWeapon(weapon, player);

		BiologicalData data = weapon.getBiologicalData();

		// release feedback and recoil
		EffectContext shootCtx = EffectContext.shot(weapon, player, player.getEyeLocation().getDirection())
		                                      .level(level)
		                                      .build();
		effectRunner.run(weapon, EffectHook.ON_SHOOT, shootCtx);

		if (weapon.getRecoilData() != null) {
			weapon.getRecoil().applyRecoil(player);
			weapon.applyPush(player);
		}

		List<PotionEffect> effects = data.isCumulativeLevels() ? cumulativeEffectsForLevel(data, level)
		                                                      : effectsForLevel(data, level);

		double flatBonus = weapon.getModifiersData().hasFlatDamage() ?
		                   weapon.getModifiersData().getFlatDamage().bonus() :
		                   0.0;
		double damage = data.getBaseDamage() * level + flatBonus;

		if (!fireRay(player, damage, effects, level)) {
			effectRunner.run(weapon, EffectHook.ON_MISS, shootCtx);
		}

		ActionBarManager.send(player, "&aReleased at charge level " + level);
	}

	/**
	 * Resolves the potion effects for a charge level from {@code Effects_Per_Level}. A level beyond the list's size
	 * clamps to the last configured entry (rather than throwing); a null/empty list or a level below 1 yields no
	 * effects.
	 */
	static List<PotionEffect> effectsForLevel(BiologicalData data, int level) {
		List<String> perLevel = data.getEffectsPerLevel();
		if (perLevel == null || perLevel.isEmpty() || level < 1) {
			return List.of();
		}

		int index = Math.min(level, perLevel.size()) - 1;
		return PotionEffectParser.parseList(List.of(perLevel.get(index)));
	}

	/**
	 * {@code Shoot.Cumulative_Levels: true} variant of {@link #effectsForLevel}: merges every entry from level 1
	 * through {@code level} (list indices {@code [0..level-1]}, clamped to the list's size) instead of only the
	 * current level's — so charging to level 3 doesn't silently drop levels 1-2's effects. Per potion type, the
	 * merged effect keeps the strongest amplifier and the longest duration across the merged entries.
	 */
	static List<PotionEffect> cumulativeEffectsForLevel(BiologicalData data, int level) {
		List<String> perLevel = data.getEffectsPerLevel();
		if (perLevel == null || perLevel.isEmpty() || level < 1) return List.of();

		Map<PotionEffectType, PotionEffect> merged = new LinkedHashMap<>();
		int upTo = Math.min(level, perLevel.size());

		for (int i = 0; i < upTo; i++) {
			for (PotionEffect effect : PotionEffectParser.parseList(List.of(perLevel.get(i)))) {
				merged.merge(effect.getType(), effect, BiologicalAction::strongest);
			}
		}

		return List.copyOf(merged.values());
	}

	/**
	 * Merge combiner for {@link #cumulativeEffectsForLevel}: keeps the longer duration and the higher amplifier
	 * (independently) of two same-type potion effects.
	 */
	static PotionEffect strongest(PotionEffect a, PotionEffect b) {
		return new PotionEffect(a.getType(), Math.max(a.getDuration(), b.getDuration()),
		                        Math.max(a.getAmplifier(), b.getAmplifier()));
	}

	/**
	 * Fires one ray through the unified raytracer. The {@link RaytraceRequest#getImpactHandler() impact handler}
	 * applies the tracked status (feedback, stacking, kill-credit window) and then the parsed potion effects to a
	 * single living target — non-living hits are ignored beyond the standard {@code WeaponRaytraceImpactEvent} that
	 * the raytracer fires automatically.
	 *
	 * @return {@code true} if a living entity took the hit — see {@code WeaponRaytracer#fireInstant}.
	 */
	private boolean fireRay(Player player, double damage, List<PotionEffect> effects, int level) {
		RaytraceRequest request = RaytraceRequest.builder()
		                                         .shooter(player)
		                                         .weapon(weapon)
		                                         .origin(player.getEyeLocation())
		                                         .direction(player.getEyeLocation().getDirection().normalize())
		                                         .maxDistance(weapon.getBiologicalData().getRange())
		                                         .baseDamage(damage)
		                                         .hitboxExpansion(0.3)
		                                         .maxIterations(1)
		                                         .impactHandler(event -> {
													 if (event.getHitEntity() instanceof LivingEntity target) {
														 boolean applied = statusService.apply(target, player, weapon, level);
														 if (applied) {
															 for (PotionEffect effect : effects) {
																 target.addPotionEffect(effect);
															 }

															 // HK: canonical WeaponEntityDamageEvent (BIOLOGICAL) once the status has
															 // actually landed - no per-zone data (a single-ray charge shot has no head
															 // bonus of its own), player shooters only (this action's own signature).
															 // damage = 0: no target.damage() call happens on this path - the status ticks
															 // its own damage later via StatusEffectService, so reporting `damage` (the raw
															 // base-damage number) here would misreport an immediate hit that never landed.
															 Bukkit.getPluginManager().callEvent(
																	 new WeaponEntityDamageEvent(weapon, target, 0, player,
																	                             weapon.getName(), DamageKind.BIOLOGICAL, null,
																	                             player.getEyeLocation()
																	                                   .distance(event.getImpactPoint())));

															 // On_Hit_Taken (gate HL review, §1): this custom impact
															 // handler short-circuits WeaponRaytracerImpl's default
															 // pipeline before it ever fires the hook.
															 WearableService wearableService = WearableService.resolveLazily();
															 if (wearableService != null) {
																 wearableService.onHitTaken(target, player, damage, effectRunner);
															 }
														 }
													 }
												 })
		                                         .build();

		return raytracer.fireInstant(request);
	}

}
