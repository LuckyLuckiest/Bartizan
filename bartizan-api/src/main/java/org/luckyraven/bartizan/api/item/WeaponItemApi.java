package org.luckyraven.bartizan.api.item;

import org.bukkit.inventory.ItemStack;

/**
 * Cross-plugin item helpers for weapon ItemStacks (bartizan.md §1.6(9)) - implemented by
 * {@code WeaponItemApiImpl}, the source for each method noted there:
 * <ul>
 *   <li>{@link #buildItem(String)} - {@code WeaponService.createTransientWeapon} + {@code buildItem}</li>
 *   <li>{@link #isValidWeaponName(String)} - {@code WeaponService.getWeaponTemplate != null}</li>
 *   <li>{@link #isSameWeapon(ItemStack, ItemStack)} - {@code WeaponService.compare(w1, w2) == 0}</li>
 *   <li>{@link #cleanDisplayName(ItemStack)} - {@code WeaponShopDisplayNameProvider}</li>
 * </ul>
 *
 * <p>{@link #buildItem(String)} must reproduce the throwable-UUID determinism rule
 * ({@code UUID.nameUUIDFromBytes("throwable:" + name)}) or throwables stop stacking.
 */
public interface WeaponItemApi {

	ItemStack buildItem(String weaponName);

	boolean isValidWeaponName(String name);

	boolean isSameWeapon(ItemStack a, ItemStack b);

	String cleanDisplayName(ItemStack item);

}
