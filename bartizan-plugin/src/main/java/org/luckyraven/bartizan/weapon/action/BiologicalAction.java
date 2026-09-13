package org.luckyraven.bartizan.weapon.action;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.luckyraven.keystone.util.ActionBarManager;
import org.luckyraven.bartizan.api.weapon.dto.BiologicalData;
import org.luckyraven.bartizan.api.weapon.dto.EffectHook;
import org.luckyraven.bartizan.effect.EffectContext;
import org.luckyraven.bartizan.effect.EffectRunner;
import org.luckyraven.bartizan.api.raytrace.RaytraceRequest;
import org.luckyraven.bartizan.api.raytrace.WeaponRaytracer;
import org.luckyraven.bartizan.util.PotionEffectParser;
import org.luckyraven.bartizan.api.weapon.BiologicalWeapon;

import java.util.List;

/**
 * Charged single-shot raytrace weapon. Charging (the timer, the level, the action-bar meter, the release trigger)
 * is owned by {@link ChargeController}; this class only finalises the shot once a charge level is released — see
 * {@link #fire(Player, int)}.
 */
public class BiologicalAction {

	private final BiologicalWeapon weapon;
	private final WeaponRaytracer  raytracer;
	private final EffectRunner     effectRunner;

	public BiologicalAction(BiologicalWeapon weapon, WeaponRaytracer raytracer, EffectRunner effectRunner) {
		this.weapon       = weapon;
		this.raytracer    = raytracer;
		this.effectRunner = effectRunner;
	}

	/**
	 * Finalises a charged shot at the given level — {@link ChargeController}'s {@code onFire} callback for this
	 * weapon. A level of 0 (released before reaching {@code Min_Level_To_Fire}) never reaches here.
	 */
	public void fire(Player player, int level) {
		if (level <= 0) return;
		if (!weapon.consumeShot()) return;

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

		List<PotionEffect> effects = effectsForLevel(data, level);

		double flatBonus = weapon.getModifiersData().hasFlatDamage() ?
		                   weapon.getModifiersData().getFlatDamage().bonus() :
		                   0.0;
		double damage = data.getBaseDamage() * level + flatBonus;

		if (!fireRay(player, damage, effects, data)) {
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
	 * Fires one ray through the unified raytracer. The {@link RaytraceRequest#getImpactHandler() impact handler}
	 * applies the parsed potion effects to a single living target — non-living hits are ignored beyond the standard
	 * {@code WeaponRaytraceImpactEvent} that the raytracer fires automatically.
	 *
	 * @return {@code true} if a living entity took the hit — see {@code WeaponRaytracer#fireInstant}.
	 */
	private boolean fireRay(Player player, double damage, List<PotionEffect> effects, BiologicalData data) {
		RaytraceRequest request = RaytraceRequest.builder()
		                                         .shooter(player)
		                                         .weapon(weapon)
		                                         .origin(player.getEyeLocation())
		                                         .direction(player.getEyeLocation().getDirection().normalize())
		                                         .maxDistance(data.getRange())
		                                         .baseDamage(damage)
		                                         .hitboxExpansion(0.3)
		                                         .maxIterations(1)
		                                         .impactHandler(event -> {
													 if (event.getHitEntity() instanceof LivingEntity target) {
														 for (PotionEffect effect : effects) {
															 target.addPotionEffect(effect);
														 }
													 }
												 })
		                                         .build();

		return raytracer.fireInstant(request);
	}

}
