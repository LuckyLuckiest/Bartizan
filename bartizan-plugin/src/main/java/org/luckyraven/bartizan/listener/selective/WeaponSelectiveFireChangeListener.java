package org.luckyraven.bartizan.listener.selective;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.luckyraven.keystone.item.ItemBuilder;
import org.luckyraven.keystone.bean.autowire.AutowireTarget;
import org.luckyraven.keystone.bean.listener.ListenerHandler;
import org.luckyraven.keystone.util.ActionBarManager;
import org.luckyraven.keystone.util.ChatUtil;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.api.weapon.dto.HandlingData;
import org.luckyraven.bartizan.weapon.WeaponService;
import org.luckyraven.bartizan.api.event.WeaponChangeSelectiveFireEvent;

@ListenerHandler
@AutowireTarget({WeaponService.class})
public class WeaponSelectiveFireChangeListener implements Listener {

	private final WeaponService weaponService;

	public WeaponSelectiveFireChangeListener(WeaponService weaponService) {
		this.weaponService = weaponService;
	}

	@EventHandler
	public void onSwapHand(PlayerSwapHandItemsEvent event) {
		Player    player = event.getPlayer();
		ItemStack item   = player.getInventory().getItemInMainHand();
		Weapon    weapon = weaponService.validateAndGetWeapon(player, item);

		// Information.Cancel.Swap_Hands (default false): block F outright while holding this weapon, regardless
		// of sneaking or whether it has Selective_Fire configured at all.
		if (weapon != null && cancelsSwapHands(weapon)) {
			event.setCancelled(true);
			return;
		}

		// check if the player is shifting
		if (!player.isSneaking()) return;

		// check if the player is holding a weapon with selective fire configured
		if (weapon == null) return;
		if (weapon.getCurrentSelectiveFire() == null) return;

		var newEvent = new WeaponChangeSelectiveFireEvent(weapon);
		Bukkit.getPluginManager().callEvent(newEvent);

		if (newEvent.isCancelled()) return;

		// change the selective fire of the weapon and cancel opening the inventory
		event.setCancelled(true);

		weapon.setCurrentSelectiveFire(
				weapon.getCurrentSelectiveFire().getNextState(weapon.getAllowedSelectiveFires()));

		// update the weapon data
		ItemBuilder itemBuilder = weaponService.getHeldWeaponItem(player);

		if (itemBuilder == null) return;

		weapon.updateWeaponData(itemBuilder, player);
		weapon.updateWeapon(player, itemBuilder, player.getInventory().getHeldItemSlot());

		ActionBarManager.send(player, "&6Selective Fire > &e" +
		                              ChatUtil.capitalize(weapon.getCurrentSelectiveFire().name().toLowerCase()));
	}

	private boolean cancelsSwapHands(Weapon weapon) {
		HandlingData handling = weapon.getHandlingData();
		return handling != null && handling.getCancel().swapHands();
	}

}
