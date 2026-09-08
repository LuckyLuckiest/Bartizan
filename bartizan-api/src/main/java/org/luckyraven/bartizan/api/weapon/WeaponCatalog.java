package org.luckyraven.bartizan.api.weapon;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Collection;

/**
 * The lookup surface {@code WeaponManager}/{@code WeaponService} expose across the api/plugin split (bartizan.md
 * §2 B6) - exactly the five {@code WeaponService} methods E1 §5.2 marks as cross-module.
 */
public interface WeaponCatalog {

	Weapon getWeaponTemplate(String name);

	Collection<Weapon> getWeaponTemplates();

	Weapon createTransientWeapon(String name);

	Weapon validateAndGetWeapon(Player player, ItemStack item);

	boolean isWeapon(ItemStack item);

}
