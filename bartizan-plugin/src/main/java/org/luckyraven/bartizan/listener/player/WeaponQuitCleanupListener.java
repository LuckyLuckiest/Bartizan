package org.luckyraven.bartizan.listener.player;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.api.weapon.dto.EffectHook;
import org.luckyraven.bartizan.effect.EffectContext;
import org.luckyraven.bartizan.effect.EffectRunner;
import org.luckyraven.bartizan.hud.HudService;
import org.luckyraven.bartizan.listener.WeaponInteract;
import org.luckyraven.bartizan.weapon.WeaponManager;
import org.luckyraven.bartizan.wearable.WearableEffectsService;
import org.luckyraven.keystone.bean.listener.ListenerHandler;
import org.luckyraven.keystone.bean.listener.ListenerPriority;

/**
 * The weapon half of the core's quit cleanup, moved out of {@code RemoveAccountListener} when the feature flipped
 * to a runtime module: unscopes and stops reloading whatever weapon the player was holding when they quit, removes
 * any HUD boss bar {@code HudService} still has open for them (gate {@code HD}), and drops
 * {@code WearableEffectsService}'s worn-key snapshot for them (gate {@code HL}) so a rejoin diffs against nothing
 * instead of stale gear from the last session.
 *
 * <p>Also unscopes and stops reloading on {@link PlayerDeathEvent} (gate {@code HH}; reload-stop added for bug
 * docket BZ-EV-10): vanilla potion effects vanish on death, but {@code ScopeData.scoped} does not track that on
 * its own - without this, a player who dies while scoped keeps {@code scoped} stuck {@code true} until they
 * manually toggle it again, and a player who dies mid-reload keeps {@code isReloading()} stuck {@code true}.
 */
@ListenerHandler(priority = ListenerPriority.LOW)
public class WeaponQuitCleanupListener implements Listener {

	private final WeaponManager          weaponManager;
	private final EffectRunner           effectRunner;
	private final HudService             hudService;
	private final WearableEffectsService wearableEffectsService;

	public WeaponQuitCleanupListener(WeaponManager weaponManager, EffectRunner effectRunner, HudService hudService,
	                                 WearableEffectsService wearableEffectsService) {
		this.weaponManager          = weaponManager;
		this.effectRunner           = effectRunner;
		this.hudService             = hudService;
		this.wearableEffectsService = wearableEffectsService;
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onPlayerQuit(PlayerQuitEvent event) {
		Player player = event.getPlayer();

		hudService.remove(player.getUniqueId());
		wearableEffectsService.remove(player.getUniqueId());

		// main hand, falling back to the off hand - a weapon stowed off-hand while quitting must still be
		// un-scoped/reload-stopped below.
		Weapon weapon = weaponManager.getHeldWeapon(player);

		if (weapon == null) return;

		stopReloadingIfActive(weapon, player);
		weapon.unScope(player, true);

		// WeaponInteract's per-weapon tracking maps (continuousFire, equipDelayUntil, pressHoldState,
		// releaseCallbacks, autoTasks, activeTasks, lastMeleeSwingMs) are only ever cleared on a hotbar swap -
		// meleeCooldowns is deliberately kept everywhere (it is the Melee.Cooldown gate itself; a stale entry only
		// gates that weapon's next swing for its Cooldown ticks) - a player who disconnects mid-AUTO-fire or
		// mid-throwable-charge would otherwise leave its
		// FullAutoTask/RepeatingTimer running and calling Bukkit Player APIs against an offline Player until its
		// own watchdog times out (bug docket BZ-EV-01). WeaponInteract isn't reachable through the bean graph from
		// here (see WeaponInteract#get()), so this can be null if it somehow never got constructed.
		WeaponInteract interact = WeaponInteract.get();
		if (interact != null) {
			interact.clearWeaponState(player, weapon);
		}
	}

	/**
	 * Evicts the quitter's weapons from the registry (BZ-WM-04). MONITOR, so it runs after {@link #onPlayerQuit} has
	 * stopped the reload and unscoped on the live instance - evicting first would make that lookup mint a fresh one.
	 */
	@EventHandler(priority = EventPriority.MONITOR)
	public void forgetWeaponsOnQuit(PlayerQuitEvent event) {
		weaponManager.forgetWeapons(event.getPlayer());
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onPlayerDeath(PlayerDeathEvent event) {
		Player player = event.getEntity();
		Weapon weapon = weaponManager.getHeldWeapon(player);

		if (weapon == null) return;

		stopReloadingIfActive(weapon, player);
		weapon.unScope(player, true);
	}

	/**
	 * Shared by both handlers (bug docket BZ-EV-10) - without this on {@code onPlayerDeath}, a player who died
	 * mid-reload kept {@code isReloading()} stuck {@code true} (blocking both fire and scope) until the reload's
	 * own per-stage {@code isDead()} check happened to notice, up to a full reload stage after respawn.
	 */
	private void stopReloadingIfActive(Weapon weapon, Player player) {
		if (!weapon.isReloading()) return;

		weapon.stopReloading();

		EffectContext ctx = EffectContext.builder().weapon(weapon).source(player).build();
		effectRunner.run(weapon, EffectHook.ON_RELOAD_CANCEL, ctx);
	}

}
