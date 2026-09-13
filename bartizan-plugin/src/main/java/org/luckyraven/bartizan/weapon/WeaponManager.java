package org.luckyraven.bartizan.weapon;

import org.luckyraven.keystone.bean.BeanLifecycle;
import org.luckyraven.bartizan.configuration.WeaponAddon;

/**
 * The runtime weapon registry bean. Live {@link org.luckyraven.bartizan.api.weapon.Weapon} instances are keyed by
 * the uuid the item carries in NBT and rebuilt from that NBT on first use
 * ({@link WeaponService#validateAndGetWeapon}). Nothing is persisted — the item is the source of truth — so the only
 * lifecycle duty left is wiping the registry on reload, when the templates it clones from are re-parsed.
 */
public class WeaponManager extends WeaponService implements BeanLifecycle {

	public WeaponManager(WeaponAddon weaponAddon) {
		super(weaponAddon);
	}

	@Override
	public void onClear() {
		clear();
	}

}
