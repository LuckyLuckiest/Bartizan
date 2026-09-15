package org.luckyraven.bartizan;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;
import org.luckyraven.bartizan.ammo.AmmunitionManager;
import org.luckyraven.bartizan.api.BartizanApi;
import org.luckyraven.bartizan.api.ammo.AmmunitionCatalog;
import org.luckyraven.bartizan.api.item.WeaponItemApi;
import org.luckyraven.bartizan.api.npc.NpcWeaponFactory;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.api.weapon.WeaponCatalog;
import org.luckyraven.bartizan.api.wearable.WearableCatalog;
import org.luckyraven.bartizan.weapon.WeaponManager;
import org.luckyraven.bartizan.weapon.WeaponService;
import org.luckyraven.bartizan.wearable.WearableAddon;

/**
 * The {@code ServicesManager} registration behind {@link BartizanApi} (bartizan.md §1.6/§C.3, task B17): five thin
 * accessors over the beans that already implement each catalog interface — {@code WeaponManager}
 * ({@code WeaponCatalog}, via {@code WeaponService}), {@code WearableAddon} ({@code WearableCatalog}, via
 * {@code WearableService}), {@code AmmunitionManager} ({@code AmmunitionCatalog}). No new state, no new logic:
 * every catalog method these interfaces expose is already implemented on the manager itself.
 *
 * <p>{@code weapons} is typed as the concrete {@code WeaponManager} (widened from {@code WeaponCatalog} at gate
 * {@code HH}) so {@link #tryReload(Player)} can reach {@code WeaponService.tryReload} directly - the same
 * guarded path {@code WeaponDroppedListener}/{@code Reload.Auto_Reload_When_Empty} already use.
 */
public final class BartizanApiImpl implements BartizanApi {

	private final WeaponManager      weapons;
	private final WearableCatalog    wearables;
	private final AmmunitionCatalog  ammunition;
	private final NpcWeaponFactory   npcWeapons;
	private final WeaponItemApi      items;
	private final JavaPlugin         plugin;

	public BartizanApiImpl(JavaPlugin plugin, WeaponManager weaponManager, WearableAddon wearableAddon,
	                       AmmunitionManager ammunitionManager, NpcWeaponFactory npcWeaponFactory,
	                       WeaponItemApi weaponItemApi) {
		this.plugin     = plugin;
		this.weapons    = weaponManager;
		this.wearables  = wearableAddon;
		this.ammunition = ammunitionManager;
		this.npcWeapons = npcWeaponFactory;
		this.items      = weaponItemApi;
	}

	@Override
	public WeaponCatalog weapons() {
		return weapons;
	}

	@Override
	public WearableCatalog wearables() {
		return wearables;
	}

	@Override
	public AmmunitionCatalog ammunition() {
		return ammunition;
	}

	@Override
	public NpcWeaponFactory npcWeapons() {
		return npcWeapons;
	}

	@Override
	public WeaponItemApi items() {
		return items;
	}

	@Override
	@Nullable
	public Weapon getHeldWeapon(Player player) {
		return weapons.getHeldWeapon(player);
	}

	@Override
	public boolean isScoping(Player player) {
		Weapon weapon = getHeldWeapon(player);
		return weapon != null && weapon.getScopeData() != null && weapon.getScopeData().isScoped();
	}

	@Override
	public boolean isReloading(Player player) {
		Weapon weapon = getHeldWeapon(player);
		return weapon != null && weapon.isReloading();
	}

	@Override
	public boolean tryReload(Player player) {
		Weapon weapon = getHeldWeapon(player);
		if (weapon == null) return false;

		return weapons.tryReload(plugin, player, weapon);
	}

	@Override
	public boolean setSkin(Player player, @Nullable String name) {
		Weapon weapon = getHeldWeapon(player);
		if (weapon == null || !weapon.setSelectedSkin(name)) return false;

		weapons.persistHeldWeapon(weapon, player);

		return true;
	}

	@Override
	@Nullable
	public String getSkin(Player player) {
		Weapon weapon = getHeldWeapon(player);
		return weapon != null ? weapon.getSelectedSkin() : null;
	}

}
