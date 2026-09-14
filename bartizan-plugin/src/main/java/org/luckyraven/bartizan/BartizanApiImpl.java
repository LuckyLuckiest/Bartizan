package org.luckyraven.bartizan;

import org.bukkit.entity.Player;
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
import org.luckyraven.bartizan.wearable.WearableAddon;

/**
 * The {@code ServicesManager} registration behind {@link BartizanApi} (bartizan.md §1.6/§C.3, task B17): five thin
 * accessors over the beans that already implement each catalog interface — {@code WeaponManager}
 * ({@code WeaponCatalog}, via {@code WeaponService}), {@code WearableAddon} ({@code WearableCatalog}, via
 * {@code WearableService}), {@code AmmunitionManager} ({@code AmmunitionCatalog}). No new state, no new logic:
 * every catalog method these interfaces expose is already implemented on the manager itself.
 *
 * <p>Constructor matches {@code WiringConfig.bartizanApi(...)}'s existing
 * {@code new BartizanApiImpl(weaponManager, wearableAddon, ammunitionManager, npcWeaponFactory, weaponItemApi)}
 * call exactly (bartizan.md B10 row) — no config-class change needed.
 */
public final class BartizanApiImpl implements BartizanApi {

	private final WeaponCatalog      weapons;
	private final WearableCatalog    wearables;
	private final AmmunitionCatalog  ammunition;
	private final NpcWeaponFactory   npcWeapons;
	private final WeaponItemApi      items;

	public BartizanApiImpl(WeaponManager weaponManager, WearableAddon wearableAddon,
	                       AmmunitionManager ammunitionManager, NpcWeaponFactory npcWeaponFactory,
	                       WeaponItemApi weaponItemApi) {
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
		Weapon mainHand = weapons.validateAndGetWeapon(player, player.getInventory().getItemInMainHand());
		if (mainHand != null) return mainHand;

		return weapons.validateAndGetWeapon(player, player.getInventory().getItemInOffHand());
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

}
