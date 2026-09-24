package org.luckyraven.bartizan.weapon;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.keystone.bean.BeanLifecycle;
import org.luckyraven.bartizan.configuration.WeaponAddon;

/**
 * The runtime weapon registry bean. Live {@link org.luckyraven.bartizan.api.weapon.Weapon} instances are keyed by
 * the uuid the item carries in NBT and rebuilt from that NBT on first use
 * ({@link WeaponService#validateAndGetWeapon}). Nothing is persisted — the item is the source of truth — so the
 * lifecycle duties are releasing live reload/scope state on reload and shutdown, and wiping the registry on reload,
 * when the templates it clones from are re-parsed.
 */
public class WeaponManager extends WeaponService implements BeanLifecycle {

	public WeaponManager(WeaponAddon weaponAddon) {
		super(weaponAddon);
	}

	/**
	 * Releases every live weapon before {@link #onClear()} discards the instances (BZ-WM-13): a reload's timer would
	 * keep consuming ammo and writing a stale magazine while the next lookup mints a fresh, non-reloading instance
	 * that can start a second reload in parallel - and a scoped player would keep SLOWNESS the fresh instance does
	 * not know about.
	 */
	@Override
	public void onPreClear() {
		releaseWeapons();
	}

	/**
	 * A server stop disables plugins before it kicks players, so {@code WeaponQuitCleanupListener} never runs and the
	 * Integer.MAX_VALUE-tick scope/reload SLOWNESS and NIGHT_VISION would be saved into player data (BZ-WM-12).
	 */
	@Override
	public void onShutdown() {
		releaseWeapons();
	}

	@Override
	public void onClear() {
		clear();
	}

	/**
	 * Stops every in-flight reload ({@code Reload#stopReloading} unscopes the reloader and fires the interrupted
	 * completion event - on a reload, not on shutdown, where Bukkit no longer delivers events to this disabled
	 * plugin), then unscopes each online player's held live weapon. {@code unScope(player, false)} only acts on a weapon this plugin scoped, never on a potion effect from
	 * elsewhere.
	 */
	private void releaseWeapons() {
		for (Weapon weapon : getWeapons().values()) {
			if (weapon.isReloading()) weapon.stopReloading();
		}

		for (Player player : Bukkit.getOnlinePlayers()) {
			unScopeLive(player, player.getInventory().getItemInMainHand());
			unScopeLive(player, player.getInventory().getItemInOffHand());
		}
	}

	private void unScopeLive(Player player, ItemStack item) {
		Weapon weapon = peekWeapon(item);
		if (weapon != null) weapon.unScope(player, false);
	}

}
