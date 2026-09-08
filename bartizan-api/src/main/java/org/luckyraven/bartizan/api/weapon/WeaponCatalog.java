package org.luckyraven.bartizan.api.weapon;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;

/**
 * The lookup surface {@code WeaponManager}/{@code WeaponService} expose across the api/plugin split (bartizan.md
 * §2 B6) - exactly the five {@code WeaponService} methods E1 §5.2 marks as cross-module.
 */
public interface WeaponCatalog {

	/** @return the configured template, or {@code null} when no weapon has that name. */
	@Nullable
	Weapon getWeaponTemplate(@Nullable String name);

	Collection<Weapon> getWeaponTemplates();

	/** @return a fresh, unowned copy of the template, or {@code null} when no weapon has that name. */
	@Nullable
	Weapon createTransientWeapon(@Nullable String name);

	/** @return the weapon the item represents, or {@code null} when the item is not a valid weapon for that player. */
	@Nullable
	Weapon validateAndGetWeapon(Player player, @Nullable ItemStack item);

	boolean isWeapon(ItemStack item);

}
