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
 * <p>Also unscopes on {@link PlayerDeathEvent} (gate {@code HH}): vanilla potion effects vanish on death, but
 * {@code ScopeData.scoped} does not track that on its own - without this, a player who dies while scoped keeps
 * {@code scoped} stuck {@code true} until they manually toggle it again.
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

		if (weapon.isReloading()) {
			weapon.stopReloading();

			EffectContext ctx = EffectContext.builder().weapon(weapon).source(player).build();
			effectRunner.run(weapon, EffectHook.ON_RELOAD_CANCEL, ctx);
		}

		weapon.unScope(player, true);
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onPlayerDeath(PlayerDeathEvent event) {
		Player player = event.getEntity();
		Weapon weapon = weaponManager.getHeldWeapon(player);

		if (weapon == null) return;

		weapon.unScope(player, true);
	}

}
