package org.luckyraven.bartizan.weapon;

import org.luckyraven.bartizan.api.weapon.Weapon;
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

	/**
	 * Ends every in-flight reload before {@link #onClear()} discards the instances it runs on (BZ-WM-13): its timer
	 * would keep consuming ammo and writing a stale magazine while the next lookup mints a fresh, non-reloading
	 * instance that can start a second reload in parallel. {@code Reload#stopReloading} unscopes the reloader and
	 * fires the interrupted completion event, which persists the rounds loaded so far.
	 */
	@Override
	public void onPreClear() {
		for (Weapon weapon : getWeapons().values()) {
			if (weapon.isReloading()) weapon.stopReloading();
		}
	}

	@Override
	public void onClear() {
		clear();
	}

}
