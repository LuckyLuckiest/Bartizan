package org.luckyraven.bartizan.listener.player;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.api.weapon.dto.EffectHook;
import org.luckyraven.bartizan.effect.EffectContext;
import org.luckyraven.bartizan.effect.EffectRunner;
import org.luckyraven.bartizan.hud.HudService;
import org.luckyraven.bartizan.weapon.WeaponManager;
import org.luckyraven.keystone.bean.listener.ListenerHandler;
import org.luckyraven.keystone.bean.listener.ListenerPriority;

/**
 * The weapon half of the core's quit cleanup, moved out of {@code RemoveAccountListener} when the feature flipped
 * to a runtime module: unscopes and stops reloading whatever weapon the player was holding when they quit, and
 * (gate {@code HD}) removes any HUD boss bar {@code HudService} still has open for them.
 */
@ListenerHandler(priority = ListenerPriority.LOW)
public class WeaponQuitCleanupListener implements Listener {

	private final WeaponManager weaponManager;
	private final EffectRunner  effectRunner;
	private final HudService    hudService;

	public WeaponQuitCleanupListener(WeaponManager weaponManager, EffectRunner effectRunner, HudService hudService) {
		this.weaponManager = weaponManager;
		this.effectRunner  = effectRunner;
		this.hudService    = hudService;
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onPlayerQuit(PlayerQuitEvent event) {
		Player player = event.getPlayer();

		hudService.remove(player.getUniqueId());

		// search if the player holds a weapon
		// check if it was a weapon
		ItemStack item   = player.getInventory().getItemInMainHand();
		Weapon    weapon = weaponManager.validateAndGetWeapon(player, item);

		if (weapon == null) return;

		if (weapon.isReloading()) {
			weapon.stopReloading();

			EffectContext ctx = EffectContext.builder().weapon(weapon).source(player).build();
			effectRunner.run(weapon, EffectHook.ON_RELOAD_CANCEL, ctx);
		}

		weapon.unScope(player, true);
	}

}
