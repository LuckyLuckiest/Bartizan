package org.luckyraven.bartizan.hud;

import lombok.CustomLog;
import org.bukkit.Bukkit;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.api.weapon.dto.AmmunitionData;
import org.luckyraven.bartizan.api.weapon.dto.HudData;
import org.luckyraven.bartizan.weapon.WeaponService;
import org.luckyraven.keystone.bean.BeanLifecycle;
import org.luckyraven.keystone.timer.RepeatingTimer;
import org.luckyraven.keystone.util.ActionBarManager;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Continuous per-player weapon HUD (weapons-roadmap.md gate {@code HD}): a {@link RepeatingTimer} ticker over
 * online players that, for whoever holds a weapon configured with a {@code HUD:} block, refreshes the action bar
 * and/or boss bar every tick. Mirrors {@code StatusEffectService}'s shape — {@link #tick()} is package-visible so
 * a test can drive it directly, and every live boss bar is torn down on {@link #onShutdown()}.
 */
@CustomLog
public class HudService implements BeanLifecycle {

	// ponytail: fixed interval, no per-weapon override — add one if a weapon ever needs a faster/slower HUD.
	private static final long TICK_INTERVAL = 5L;

	private final JavaPlugin    plugin;
	private final WeaponService weaponService;

	private final Map<UUID, BossBar> bossBars = new HashMap<>();

	@Nullable
	private RepeatingTimer timer;

	public HudService(JavaPlugin plugin, WeaponService weaponService) {
		this.plugin        = plugin;
		this.weaponService = weaponService;
	}

	/**
	 * Starts the {@code tick()} timer. Called once, from {@code WiringConfig}'s bean construction. Idempotent.
	 */
	public void start() {
		if (timer != null) return;

		timer = new RepeatingTimer(plugin, TICK_INTERVAL, ignored -> tick());
		timer.start(false);
	}

	/**
	 * Removes {@code playerId}'s boss bar, if any — called on quit (mirrors {@code StatusEffectService}'s cleanup,
	 * wired from {@code WeaponQuitCleanupListener}).
	 */
	public void remove(UUID playerId) {
		removeBossBar(playerId);
	}

	/**
	 * One HUD tick over every online player. Package-visible so a test can drive it directly without a real
	 * scheduler.
	 */
	void tick() {
		for (Player player : Bukkit.getOnlinePlayers()) {
			// A misconfigured HUD template (or a placeholder blowing up on this one player's weapon state) must
			// not take the whole tick down with it - same guard StatusEffectService#tick uses.
			try {
				tickPlayer(player);
			} catch (Exception exception) {
				log.warn("HUD tick failed for " + player.getUniqueId() + ": " + exception.getMessage());
			}
		}
	}

	private void tickPlayer(Player player) {
		ItemStack item   = player.getInventory().getItemInMainHand();
		Weapon    weapon = weaponService.validateAndGetWeapon(player, item);

		HudData hud = weapon != null ? weapon.getHudData() : null;
		if (hud == null) {
			removeBossBar(player.getUniqueId());
			return;
		}

		if (hud.getActionBar() != null) {
			ActionBarManager.send(player, WeaponPlaceholders.resolve(weapon, player, hud.getActionBar()));
		}

		if (hud.getBossBar() != null) updateBossBar(player, weapon, hud.getBossBar());
		else removeBossBar(player.getUniqueId());
	}

	@Override
	public void onShutdown() {
		for (BossBar bar : bossBars.values()) bar.removeAll();
		bossBars.clear();

		if (timer != null) {
			timer.stop();
			timer = null;
		}
	}

	private void updateBossBar(Player player, Weapon weapon, HudData.BossBarData data) {
		String title    = WeaponPlaceholders.resolve(weapon, player, data.title());
		double progress = progress(weapon);

		BossBar bar = bossBars.get(player.getUniqueId());
		if (bar == null) {
			bar = Bukkit.createBossBar(title, data.color(), data.style());
			bar.addPlayer(player);
			bossBars.put(player.getUniqueId(), bar);
		} else {
			bar.setTitle(title);
		}

		bar.setProgress(progress);
	}

	/**
	 * Ammo fraction normally, or reload progress while reloading; {@code 1.0} for a weapon with no magazine.
	 */
	private double progress(Weapon weapon) {
		if (weapon.isReloading()) return weapon.reloadProgress();

		AmmunitionData ammunitionData = weapon.getAmmunitionData();
		if (ammunitionData == null || ammunitionData.getMaxMagCapacity() <= 0) return 1.0;

		return clamp01(weapon.getCurrentMagCapacity() / (double) ammunitionData.getMaxMagCapacity());
	}

	private void removeBossBar(UUID playerId) {
		BossBar bar = bossBars.remove(playerId);
		if (bar != null) bar.removeAll();
	}

	private static double clamp01(double value) {
		return Math.max(0, Math.min(1, value));
	}

}
