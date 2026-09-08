package org.luckyraven.bartizan.item;

import org.bukkit.inventory.ItemStack;
import org.luckyraven.keystone.item.ItemBuilder;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.api.weapon.WeaponTag;
import org.luckyraven.bartizan.api.ammo.Ammunition;

import java.util.function.Predicate;

/**
 * Constant {@link Predicate}s that identify a weapon or ammunition {@link ItemStack} by its NBT. Moved out of the
 * core's {@code ItemPredicates} when the weapon module was split out — the core cannot see {@code Weapon} or
 * {@code Ammunition} any more. {@code BartizanItemPredicates.WEARABLE} lives in {@code bartizan-api} (it depends
 * only on {@code Wearable}, an api type) and this class imports it from there instead of duplicating it here.
 */
public final class WeaponItemPredicates {

	public static final Predicate<ItemStack> WEAPON     = stack -> hasTag(stack,
	                                                                      Weapon.getTagProperName(WeaponTag.WEAPON));
	public static final Predicate<ItemStack> AMMUNITION = stack -> hasTag(stack, Ammunition.NBT_KEY);

	private WeaponItemPredicates() {
	}

	private static boolean hasTag(ItemStack stack, String tag) {
		if (stack == null) {
			return false;
		}
		return new ItemBuilder(stack).hasNBTTag(tag);
	}
}
