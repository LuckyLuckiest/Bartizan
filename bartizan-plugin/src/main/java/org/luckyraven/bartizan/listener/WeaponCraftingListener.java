package org.luckyraven.bartizan.listener;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.ItemStack;
import org.luckyraven.keystone.bean.autowire.AutowireTarget;
import org.luckyraven.keystone.bean.listener.ListenerHandler;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.api.weapon.dto.HandlingData;
import org.luckyraven.bartizan.weapon.WeaponService;

/**
 * {@code Information.Deny_Use_In_Crafting} (weapons-roadmap.md gate {@code HE}, part a, default {@code true}):
 * blocks a crafting recipe result whenever any matrix slot carries a Bartizan weapon whose flag is set. Uses the
 * cheap NBT-name read plus the shared, read-only catalogue template ({@code WeaponService#getWeaponTemplate})
 * rather than {@code validateAndGetWeapon} — nothing here needs a live, registered weapon instance.
 */
@ListenerHandler
@AutowireTarget({WeaponService.class})
public class WeaponCraftingListener implements Listener {

	private final WeaponService weaponService;

	public WeaponCraftingListener(WeaponService weaponService) {
		this.weaponService = weaponService;
	}

	@EventHandler
	public void onPrepareItemCraft(PrepareItemCraftEvent event) {
		for (ItemStack item : event.getInventory().getMatrix()) {
			if (item == null) continue;

			String weaponName = weaponService.getHeldWeaponName(item);
			if (weaponName == null) continue;

			Weapon template = weaponService.getWeaponTemplate(weaponName);
			if (template == null) continue;

			HandlingData handling = template.getHandlingData();
			if (handling != null && !handling.isDenyUseInCrafting()) continue;

			event.getInventory().setResult(null);
			return;
		}
	}

}
