package org.luckyraven.bartizan.weapon.action;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.luckyraven.keystone.timer.RepeatingTimer;
import org.luckyraven.keystone.util.ActionBarManager;
import org.luckyraven.keystone.util.ParticleUtil;
import org.luckyraven.bartizan.api.weapon.dto.BiologicalData;
import org.luckyraven.bartizan.api.weapon.dto.EffectHook;
import org.luckyraven.bartizan.effect.EffectContext;
import org.luckyraven.bartizan.effect.EffectRunner;
import org.luckyraven.bartizan.listener.WeaponInteract;
import org.luckyraven.bartizan.api.raytrace.RaytraceRequest;
import org.luckyraven.bartizan.api.raytrace.WeaponRaytracer;
import org.luckyraven.bartizan.raytrace.WeaponMuzzle;
import org.luckyraven.bartizan.util.EmptyMagSoundGate;
import org.luckyraven.bartizan.util.PotionEffectParser;
import org.luckyraven.bartizan.api.weapon.BiologicalWeapon;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Charged single-shot raytrace weapon. The player holds RMB to charge (each tier multiplies the base damage and picks
 * the matching {@code Effects_Per_Level} entry) and releases RMB to fire a forward raytrace that hits exactly one
 * target.
 *
 * <p>Release-detection is driven by {@link WeaponInteract}: when the watchdog clears
 * the held flag for this weapon's UUID, it invokes the runnable registered via {@link #getReleaseCallback(Player)},
 * which finalises the shot and clears the charge state.
 */
public class BiologicalAction {

	private final JavaPlugin                plugin;
	private final BiologicalWeapon          weapon;
	private final WeaponRaytracer           raytracer;
	private final Map<UUID, RepeatingTimer> activeTasks;
	private final Map<UUID, int[]>          chargeLevels;
	private final EffectRunner              effectRunner;

	public BiologicalAction(JavaPlugin plugin, BiologicalWeapon weapon, WeaponRaytracer raytracer,
	                        Map<UUID, RepeatingTimer> activeTasks, EffectRunner effectRunner) {
		this.plugin        = plugin;
		this.weapon        = weapon;
		this.raytracer     = raytracer;
		this.activeTasks   = activeTasks;
		this.chargeLevels  = new ConcurrentHashMap<>();
		this.effectRunner  = effectRunner;
	}

	/**
	 * Begins charging the weapon. If a charge is already in progress for this weapon UUID nothing happens — RMB-hold
	 * keeps refreshing the held flag in {@link WeaponInteract}, but the charge timer is created exactly once per press
	 * cycle.
	 */
	public boolean start(Player player) {
		UUID weaponUuid = weapon.getUuid();

		if (activeTasks.containsKey(weaponUuid)) return false;
		if (weapon.getAmmunitionData() != null && weapon.isMagazineEmpty()) {
			EmptyMagSoundGate.play(plugin, player, weapon, effectRunner);
			return false;
		}

		BiologicalData data = weapon.getBiologicalData();

		int[] charge = {0};
		chargeLevels.put(weaponUuid, charge);

		RepeatingTimer timer = new RepeatingTimer(plugin, 1L, time -> {
			if (time.getTickCount() % data.getChargeTimePerLevel() == 0 && charge[0] < data.getMaxChargeLevel()) {
				charge[0]++;
				ActionBarManager.send(player, "&6Charging... &e[" + "■".repeat(charge[0]) +
				                              "□".repeat(data.getMaxChargeLevel() - charge[0]) + "]");

				EffectContext levelCtx = EffectContext.builder().weapon(weapon).source(player).level(charge[0]).build();
				effectRunner.run(weapon, EffectHook.ON_CHARGE_LEVEL, levelCtx);

				if (charge[0] >= data.getMaxChargeLevel()) {
					effectRunner.run(weapon, EffectHook.ON_CHARGE_FULL, levelCtx);
				}
			}
			// visual ring that grows with charge level
			ParticleUtil.spawnChargeRing(player.getLocation(), charge[0], data.getMaxChargeLevel());
		});

		timer.start(false);
		activeTasks.put(weaponUuid, timer);
		return true;
	}

	/**
	 * Returns a release runnable that fires the charged shot when invoked. {@link WeaponInteract}'s watchdog calls this
	 * once it detects RMB has been released.
	 */
	public Runnable getReleaseCallback(Player player) {
		return () -> fire(player);
	}

	private void fire(Player player) {
		UUID weaponUuid = weapon.getUuid();

		RepeatingTimer timer = activeTasks.remove(weaponUuid);
		if (timer != null) timer.stop();

		int[] charge = chargeLevels.remove(weaponUuid);
		int   level  = charge != null ? charge[0] : 0;
		if (level <= 0) return;

		if (!weapon.consumeShot()) return;

		BiologicalData data = weapon.getBiologicalData();

		// release feedback and recoil
		EffectContext shootCtx = EffectContext.builder()
		                                      .weapon(weapon)
		                                      .source(player)
		                                      .muzzle(WeaponMuzzle.compute(player, player.getEyeLocation().getDirection()))
		                                      .level(level)
		                                      .ammoLeft(weapon.getAmmunitionData() != null
		                                                ? weapon.getCurrentMagCapacity() : 0)
		                                      .ammoMax(weapon.getAmmunitionData() != null
		                                               ? weapon.getAmmunitionData().getMaxMagCapacity() : 0)
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

		fireRay(player, damage, effects, data);

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
	 */
	private void fireRay(Player player, double damage, List<PotionEffect> effects, BiologicalData data) {
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

		raytracer.fireInstant(request);
	}

}
