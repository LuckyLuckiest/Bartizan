package org.luckyraven.bartizan.stats;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.luckyraven.bartizan.api.event.WeaponEntityDamageEvent;
import org.luckyraven.bartizan.api.event.WeaponKillEntityEvent;
import org.luckyraven.bartizan.api.event.WeaponShootEvent;
import org.luckyraven.keystone.bean.listener.ListenerHandler;
import org.luckyraven.keystone.npc.NpcSupport;

/**
 * Bukkit-facing wiring for {@link StatsService} (weapons-roadmap.md gate {@code HK}) — mirrors
 * {@code StatusListener}/{@code StatusEffectService}'s split so the counting logic itself stays a plain,
 * unit-testable class. Registered like every other {@code @ListenerHandler} class: constructor-injected with the
 * {@link StatsService} bean {@code WiringConfig} already produces.
 */
@ListenerHandler
public class StatsListener implements Listener {

	private final StatsService statsService;

	public StatsListener(StatsService statsService) {
		this.statsService = statsService;
	}

	/**
	 * {@code MONITOR}: reads the event's final {@code isCancelled()} state, after every other plugin's listener
	 * has had a chance to cancel it.
	 */
	@EventHandler(priority = EventPriority.MONITOR)
	public void onShoot(WeaponShootEvent event) {
		if (event.isCancelled()) return;
		if (!(event.getShooter() instanceof Player shooter)) return;

		statsService.recordShot(shooter, event.getWeapon().getName());
	}

	@EventHandler
	public void onDamage(WeaponEntityDamageEvent event) {
		Player shooter = event.getShooter();
		if (shooter == null) return;

		statsService.recordDamage(shooter, event.getEntity(), event.weaponName(), event.getDamage(),
				event.getZone(), event.getDistance());
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onKill(WeaponKillEntityEvent event) {
		if (event.isCancelled()) return;

		Entity killer = event.getKiller();
		statsService.recordKill(event.getWeapon(), killer, event.getKilled());
	}

	/**
	 * BZ-HU-04: unconditional, so every player death counts — falling, drowning, lava, void, starvation, unarmed
	 * PvP, a vanilla/other-plugin mob kill, all the same as a Bartizan weapon kill. {@code MONITOR} runs after
	 * {@code WeaponDeathListener}'s {@code HIGH}-priority handler (and, nested inside it, this class's own
	 * {@code onKill} above whenever a weapon claims the kill), so by the time this runs
	 * {@link StatsService#recordDeath} always observes the final state. A Citizens player-NPC raises
	 * {@code PlayerDeathEvent} too, and is skipped - every dying NPC would otherwise leave a stats file behind.
	 */
	@EventHandler(priority = EventPriority.MONITOR)
	public void onDeath(PlayerDeathEvent event) {
		if (isNpc(event.getEntity())) return;

		statsService.recordDeath(event.getEntity());
	}

	/**
	 * Package-private so a test can stub it: {@code NpcSupport} can't be mocked without Citizens on the classpath.
	 */
	boolean isNpc(Player player) {
		return NpcSupport.isNpc(player);
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		statsService.onQuit(event.getPlayer().getUniqueId());
	}

}
