package org.luckyraven.bartizan.item;

import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.luckyraven.keystone.item.ItemBuilder;
import org.luckyraven.keystone.util.ChatUtil;
import org.luckyraven.bartizan.api.item.WeaponItemApi;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.api.weapon.WeaponTag;
import org.luckyraven.bartizan.weapon.WeaponService;

/**
 * bartizan.md §1.6(9): the four cross-plugin build/compare/display-name helpers, sourced verbatim from
 * {@code WeaponService} and the deleted {@code WeaponShopDisplayNameProvider}. {@link #buildItem(String)} reuses
 * {@code WeaponService.createTransientWeapon}, which already reproduces the throwable-UUID determinism rule
 * ({@code UUID.nameUUIDFromBytes("throwable:" + name)}) so throwables keep stacking.
 */
public class WeaponItemApiImpl implements WeaponItemApi {

	private final WeaponService weaponService;

	public WeaponItemApiImpl(WeaponService weaponService) {
		this.weaponService = weaponService;
	}

	@Override
	@Nullable
	public ItemStack buildItem(String weaponName) {
		Weapon weapon = weaponService.createTransientWeapon(weaponName);
		return weapon != null ? weapon.buildItem() : null;
	}

	@Override
	public boolean isValidWeaponName(String name) {
		return weaponService.getWeaponTemplate(name) != null;
	}

	@Override
	public boolean isSameWeapon(ItemStack a, ItemStack b) {
		// Player-less resolution — WeaponService.validateAndGetWeapon tolerates a null player (see
		// WeaponItemSpawnListener's own precedent) since it only needs the item's own NBT to rebuild the Weapon.
		Weapon w1 = weaponService.validateAndGetWeapon(null, a);
		Weapon w2 = weaponService.validateAndGetWeapon(null, b);
		if (w1 == null || w2 == null) return false;

		return weaponService.compare(w1, w2) == 0;
	}

	@Override
	@Nullable
	public String cleanDisplayName(ItemStack item) {
		if (item == null) return null;

		String weaponName = new ItemBuilder(item).getStringTagData(Weapon.getTagProperName(WeaponTag.WEAPON));
		if (weaponName == null || weaponName.isEmpty()) return null;

		Weapon weapon = weaponService.getWeaponTemplate(weaponName);
		if (weapon == null || weapon.getDisplayName() == null || weapon.getDisplayName().isBlank()) return null;

		// Weapon#getDisplayName returns the raw YAML string with '&' codes — translate before returning
		// so callers can drop the result straight into item display names / chat messages.
		return ChatUtil.color(weapon.getDisplayName());
	}

}
