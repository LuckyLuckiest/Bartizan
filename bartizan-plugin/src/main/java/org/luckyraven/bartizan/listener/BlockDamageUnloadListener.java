package org.luckyraven.bartizan.listener;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldUnloadEvent;
import org.luckyraven.bartizan.api.weapon.modifiers.BlockDamageManager;
import org.luckyraven.keystone.bean.autowire.AutowireTarget;
import org.luckyraven.keystone.bean.listener.ListenerHandler;

/**
 * Writes back every block a weapon broke in {@code RESTORE} mode in a world that is unloading, before the unload
 * saves its chunks with the block still {@code AIR} (BZ-RT-15 — the plugin-disable half is
 * {@code WeaponRaytracerImpl#onShutdown}).
 */
@ListenerHandler
@AutowireTarget({BlockDamageManager.class})
public class BlockDamageUnloadListener implements Listener {

	private final BlockDamageManager blockDamageManager;

	public BlockDamageUnloadListener(BlockDamageManager blockDamageManager) {
		this.blockDamageManager = blockDamageManager;
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onWorldUnload(WorldUnloadEvent event) {
		blockDamageManager.restoreWorld(event.getWorld());
	}

}
