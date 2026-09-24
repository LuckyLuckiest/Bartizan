package org.luckyraven.bartizan.listener.reload;

import org.bukkit.Bukkit;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.luckyraven.keystone.bean.autowire.AutowireTarget;
import org.luckyraven.keystone.bean.listener.ListenerHandler;
import org.luckyraven.keystone.util.ActionBarManager;
import org.luckyraven.keystone.util.ChatUtil;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.api.weapon.dto.HandlingData;
import org.luckyraven.bartizan.weapon.WeaponService;
import org.luckyraven.bartizan.api.event.WeaponReloadEvent;

@ListenerHandler
@AutowireTarget({WeaponService.class})
public class WeaponDroppedListener implements Listener {

	private final JavaPlugin    plugin;
	private final WeaponService weaponService;

	public WeaponDroppedListener(JavaPlugin plugin, WeaponService weaponService) {
		this.plugin        = plugin;
		this.weaponService = weaponService;
	}

	@EventHandler
	public void onPlayerDrop(PlayerDropItemEvent event) {
		Player    player    = event.getPlayer();
		Item      item      = event.getItemDrop();
		ItemStack itemStack = item.getItemStack();
		Weapon    weapon    = weaponService.validateAndGetWeapon(player, itemStack);

		if (weapon == null) return;

		var newEvent = new WeaponReloadEvent(weapon);
		Bukkit.getPluginManager().callEvent(newEvent);

		if (newEvent.isCancelled()) return;

		// no interruption while the weapon is reloading
		if (weapon.isReloading()) {
			event.setCancelled(true);
			return;
		}

		// Information.Cancel.Drop_Item (default false): cancels the drop unconditionally — sneaking must never be
		// a bypass. Set eagerly here so every "falls through without starting a reload" path below (no Reload:
		// configured, empty inventory, etc.) stays cancelled too; only a tryReload that actually starts keeps the
		// event cancelled for the same reason (the reload replacing the drop), which this pre-cancel doesn't
		// interfere with. A normal (non-sneak) drop returns immediately, before even showing the drop hologram,
		// since the item never actually leaves the hand.
		boolean cancelDrop = cancelsDropItem(weapon);
		if (cancelDrop) event.setCancelled(true);

		if (!player.isSneaking() && cancelDrop) return;

		// show the hologram when the weapon is dropped
		if (weapon.isDropHologram()) {
			item.setCustomName(ChatUtil.color(weapon.getDisplayName()));
			item.setCustomNameVisible(true);
		}

		// weapons without ammo (melee, throwable, etc.) skip reload
		if (weapon.getReloadData() == null) return;

		// drop the weapon normally
		if (!player.isSneaking()) return;

		// check if the magazine is already full
		if (weapon.isMagazineFull()) {
			ActionBarManager.send(player, "&cMagazine is full!");
			event.setCancelled(true);
			return;
		}

		// check if the item is available (or it was creative) and start the reload
		if (!weaponService.tryReload(plugin, player, weapon)) return;

		// don't drop the weapon — a reload just started
		event.setCancelled(true);
	}

	/**
	 * Unscopes whatever weapon is about to leave the player's hand on every path through {@link #onPlayerDrop}
	 * that lets the drop proceed uncancelled - a plain Q drop leaks the same way the off-hand swap does (bug
	 * docket BZ-EV-09): without this, a scoped weapon dropped normally (not sneaking, no {@code Cancel.Drop_Item})
	 * left the player permanently slowed with no cleanup path. Registered at {@link EventPriority#MONITOR} and
	 * keyed off {@code event.isCancelled()} directly, mirroring
	 * {@code WeaponSelectiveFireChangeListener#onSwapHandScopeCleanup} - one guard for every current and future
	 * uncancelled exit instead of one patched into each. {@code Weapon#unScope} is a no-op unless the weapon is
	 * actually scoped.
	 */
	@EventHandler(priority = EventPriority.MONITOR)
	public void onPlayerDropScopeCleanup(PlayerDropItemEvent event) {
		if (event.isCancelled()) return;

		Player player = event.getPlayer();
		Weapon weapon = weaponService.validateAndGetWeapon(player, event.getItemDrop().getItemStack());

		if (weapon != null) {
			weapon.unScope(player, false);
		}
	}

	private boolean cancelsDropItem(Weapon weapon) {
		HandlingData handling = weapon.getHandlingData();
		return handling != null && handling.getCancel().dropItem();
	}

}
