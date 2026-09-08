package org.luckyraven.bartizan.item;

import lombok.RequiredArgsConstructor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.luckyraven.keystone.item.ItemBuilder;
import org.luckyraven.keystone.item.ItemRefresher;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.weapon.WeaponService;
import org.luckyraven.bartizan.api.weapon.WeaponTag;

/**
 * Rebuilds a Bartizan weapon ItemStack into a factory-fresh copy with full ammo, clean NBT, and the default selective-
 * fire mode. Mirrors {@link WeaponConverter}'s build path so the refreshed item matches what the converter would
 * produce.
 */
@RequiredArgsConstructor
public class WeaponRefresher implements ItemRefresher {

	private final WeaponService weaponService;

	@Override
	public boolean canRefresh(ItemStack source) {
		if (source == null) return false;
		String name = new ItemBuilder(source).getStringTagData(Weapon.getTagProperName(WeaponTag.WEAPON));
		return name != null && !name.isEmpty();
	}

	@Override
	@Nullable
	public ItemStack refresh(ItemStack source, @Nullable Player context) {
		String name = new ItemBuilder(source).getStringTagData(Weapon.getTagProperName(WeaponTag.WEAPON));
		if (name == null || name.isEmpty()) return null;

		// Transient on purpose — refresh runs on every shop/trader render, so minting a registered weapon here grew
		// the registry (and the weapon table) without bound.
		Weapon clone = weaponService.createTransientWeapon(name);
		if (clone == null) return null;

		ItemStack built = context != null ? clone.buildItem(context) : clone.buildItem();
		if (built == null) return null;

		built.setAmount(source.getAmount());
		return built;
	}

}
