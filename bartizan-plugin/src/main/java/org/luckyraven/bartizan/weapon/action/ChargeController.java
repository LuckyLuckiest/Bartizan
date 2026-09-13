package org.luckyraven.bartizan.weapon.action;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;
import org.luckyraven.keystone.timer.RepeatingTimer;
import org.luckyraven.keystone.util.ActionBarManager;
import org.luckyraven.keystone.util.ParticleUtil;
import org.luckyraven.bartizan.api.event.WeaponChargeLevelEvent;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.api.weapon.dto.ChargeData;
import org.luckyraven.bartizan.api.weapon.dto.EffectHook;
import org.luckyraven.bartizan.effect.EffectContext;
import org.luckyraven.bartizan.effect.EffectRunner;

import java.util.Map;
import java.util.UUID;
import java.util.function.IntConsumer;

/**
 * Owns the per-weapon charge-then-release cycle shared by biological weapons (gate {@code HB}) and beam weapons
 * (gate {@code HC}): the tick timer, the level counter, the action-bar meter, the charge-ring particle, the
 * {@code On_Charge_Level}/{@code On_Charge_Full} effect hooks and {@link WeaponChargeLevelEvent}, {@code
 * Min_Level_To_Fire}, and {@code Auto_Fire_At_Max}.
 *
 * <p>One instance is dedicated to a single charge attempt for one weapon — {@link #release(Player)} is idempotent
 * so an {@code Auto_Fire_At_Max} release from inside {@link #tick} and a later manual release (the player finally
 * lets go of RMB) don't both fire.
 */
public class ChargeController {

	private final JavaPlugin                plugin;
	private final Weapon                    weapon;
	private final ChargeData                data;
	private final EffectRunner              effectRunner;
	private final Map<UUID, RepeatingTimer> activeTasks;
	private final IntConsumer               onFire;
	private final @Nullable TickListener    tickListener;

	private int     level;
	private boolean released;

	public ChargeController(JavaPlugin plugin, Weapon weapon, ChargeData data, EffectRunner effectRunner,
	                        Map<UUID, RepeatingTimer> activeTasks, IntConsumer onFire) {
		this(plugin, weapon, data, effectRunner, activeTasks, onFire, null);
	}

	/**
	 * @param tickListener invoked every charge tick (after the level bookkeeping, while still charging) — lets a
	 *                     category-specific preview (beam) piggyback on this controller's own timer instead of
	 *                     running a second one that would outlive {@link #release}.
	 */
	public ChargeController(JavaPlugin plugin, Weapon weapon, ChargeData data, EffectRunner effectRunner,
	                        Map<UUID, RepeatingTimer> activeTasks, IntConsumer onFire,
	                        @Nullable TickListener tickListener) {
		this.plugin       = plugin;
		this.weapon       = weapon;
		this.data         = data;
		this.effectRunner = effectRunner;
		this.activeTasks  = activeTasks;
		this.onFire       = onFire;
		this.tickListener = tickListener;
	}

	/**
	 * Starts the charge timer. Returns {@code false} without doing anything if a charge is already in progress for
	 * this weapon UUID — RMB-hold keeps refreshing the caller's held flag, but the timer is created exactly once
	 * per press cycle.
	 */
	public boolean start(Player player) {
		UUID weaponUuid = weapon.getUuid();
		if (activeTasks.containsKey(weaponUuid)) return false;

		RepeatingTimer timer = new RepeatingTimer(plugin, 1L, time -> tick(player, time.getTickCount()));
		timer.start(false);
		activeTasks.put(weaponUuid, timer);
		return true;
	}

	/**
	 * Stops the timer and, if the reached level is at least {@code Min_Level_To_Fire}, invokes {@code onFire} with
	 * it. Releasing below the minimum cancels silently — nothing was consumed yet, so there is nothing to refund.
	 * Idempotent: a second call (e.g. the manual release after {@code Auto_Fire_At_Max} already fired) is a no-op.
	 */
	public void release(Player player) {
		if (released) return;
		released = true;

		RepeatingTimer timer = activeTasks.remove(weapon.getUuid());
		if (timer != null) timer.stop();

		if (level >= data.getMinLevelToFire()) {
			onFire.accept(level);
		}
	}

	/**
	 * One charge tick, delegated to by the {@link RepeatingTimer} started in {@link #start}. Package-private so it
	 * can be driven directly in a unit test without a scheduler.
	 */
	void tick(Player player, long tickCount) {
		if (tickCount % data.getTimePerLevel() == 0 && level < data.getMaxLevel()) {
			level++;
			ActionBarManager.send(player, "&6Charging... &e[" + "■".repeat(level) +
			                              "□".repeat(data.getMaxLevel() - level) + "]");

			Bukkit.getPluginManager().callEvent(new WeaponChargeLevelEvent(weapon, player, level, data.getMaxLevel()));

			EffectContext levelCtx = EffectContext.builder().weapon(weapon).source(player).level(level).build();
			effectRunner.run(weapon, EffectHook.ON_CHARGE_LEVEL, levelCtx);

			if (level >= data.getMaxLevel()) {
				effectRunner.run(weapon, EffectHook.ON_CHARGE_FULL, levelCtx);
				if (data.isAutoFireAtMax()) release(player);
			}
		}

		// Skipped once released (including an Auto_Fire_At_Max release earlier this same tick) — the listener must
		// not keep drawing/acting past the point the charge actually ended.
		if (tickListener != null && !released) tickListener.onTick(player, level, tickCount);

		ParticleUtil.spawnChargeRing(player.getLocation(), level, data.getMaxLevel());
	}

	/**
	 * Per-tick callback for a category-specific charge effect (currently: the beam charge preview). Invoked once
	 * per {@link #tick} while charging, never after {@link #release}.
	 */
	@FunctionalInterface
	public interface TickListener {

		void onTick(Player player, int level, long tickCount);

	}

}
