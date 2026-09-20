package org.luckyraven.bartizan.hud;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.luckyraven.bartizan.api.event.WeaponShootEvent;
import org.luckyraven.keystone.bean.autowire.AutowireTarget;
import org.luckyraven.keystone.bean.listener.ListenerHandler;

/**
 * Makes the weapon HUD react to the shot that just happened instead of waiting for {@link HudService}'s 5-tick
 * sweep, which visibly trails the magazine count under sustained fire. Every firing action fires
 * {@link WeaponShootEvent} after decrementing the in-memory magazine but <em>before</em> writing it back to the
 * held item, and the HUD reads the item's NBT, so the refresh is deferred by one tick rather than run inline.
 */
@ListenerHandler
@AutowireTarget({HudService.class})
public class HudShotRefreshListener implements Listener {

	private final JavaPlugin plugin;
	private final HudService hudService;

	public HudShotRefreshListener(JavaPlugin plugin, HudService hudService) {
		this.plugin     = plugin;
		this.hudService = hudService;
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onShoot(WeaponShootEvent event) {
		if (!(event.getShooter() instanceof Player player)) return;

		Bukkit.getScheduler().runTask(plugin, () -> hudService.refresh(player));
	}

}
