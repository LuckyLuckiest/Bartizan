package org.luckyraven.bartizan.command;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.api.weapon.WeaponType;
import org.luckyraven.bartizan.weapon.WeaponManager;

import java.util.Map;

/**
 * The stack-building/overflow-drop body shared by {@code /bartizan weapon give} and {@code /bartizan weapon get}
 * (weapons-roadmap.md gate {@code HD}) — identical to what {@code WeaponGiveCommand} did on its own before
 * {@code get} split off and {@code give} grew a target-player argument, pulled out so neither command copies it.
 */
final class WeaponGiveHelper {

	// ponytail: 36 inventory slots * 64 (largest vanilla stack size) - a console-reachable amount with no upper
	// bound lets `give <p> rifle 2000000000` allocate an ItemStack[] large enough to OOM the server.
	private static final int MAX_AMOUNT = 2304;

	private WeaponGiveHelper() {
	}

	/**
	 * @param receiver the player whose inventory receives the weapon (the {@code /give} target, or the sole player
	 *                 for {@code /get}).
	 * @param name weapon file name (already lower-cased by the caller).
	 * @param amount clamped to {@code [1, MAX_AMOUNT]} before use.
	 *
	 * @return how many were actually handed over (the clamped amount) - what the success message must report
	 * 		(BZ-CM-01); {@code 0} when {@code name} is not a configured weapon, and the receiver's inventory is
	 * 		untouched.
	 */
	static int give(WeaponManager weaponManager, Player receiver, String name, int amount) {
		// never registered here - validateAndGetWeapon registers each item under its own uuid on first use
		Weapon weapon = weaponManager.createTransientWeapon(name);
		if (weapon == null) return 0;

		amount = Math.max(1, Math.min(amount, MAX_AMOUNT));

		ItemStack       sampleItem   = weapon.buildItem(receiver);
		// one item per stack so every non-throwable is its own weapon with its own uuid (BZ-CM-05); throwables share
		// one uuid per type on purpose (WeaponService#mintUuid) and keep stacking
		int             maxStackSize = weapon.getCategory() == WeaponType.THROWABLE ? sampleItem.getMaxStackSize() : 1;
		int             slots        = (int) Math.ceil(amount / (double) maxStackSize);
		int             amountLeft   = amount;
		PlayerInventory inventory    = receiver.getInventory();
		ItemStack[]     items        = new ItemStack[slots];

		for (int i = 0; i < slots; i++) {
			int amountGive = Math.min(amountLeft, maxStackSize);

			if (amountGive <= 0) break;

			ItemStack item = weaponManager.createTransientWeapon(name).buildItem(receiver);

			item.setAmount(amountGive);

			items[i] = item;

			amountLeft -= amountGive;
		}

		Map<Integer, ItemStack> left = inventory.addItem(items);

		// make the receiver drop from their inventory the rest of items
		for (ItemStack item : left.values()) {
			receiver.getWorld().dropItemNaturally(receiver.getLocation(), item);
		}

		return amount;
	}

}
