package org.luckyraven.bartizan.api;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;
import org.luckyraven.bartizan.api.ammo.AmmunitionCatalog;
import org.luckyraven.bartizan.api.item.WeaponItemApi;
import org.luckyraven.bartizan.api.npc.NpcWeaponFactory;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.api.weapon.WeaponCatalog;
import org.luckyraven.bartizan.api.wearable.WearableCatalog;

/**
 * The single {@code ServicesManager} discovery point for Bartizan (bartizan.md §0/§C.3). Registered by
 * {@code WiringConfig.bartizanApi(...)}; consumers resolve it lazily -
 * {@code Bukkit.getServicesManager().getRegistration(BartizanApi.class)} - and never cache the result at
 * construction (documentation/bartizan-api.md).
 */
public interface BartizanApi {

	WeaponCatalog weapons();

	WearableCatalog wearables();

	AmmunitionCatalog ammunition();

	NpcWeaponFactory npcWeapons();

	WeaponItemApi items();

	/**
	 * @return the weapon {@code player} is holding (main hand, then off hand), or {@code null} when neither hand
	 * 		holds a valid weapon item (weapons-roadmap.md gate {@code HD}).
	 */
	@Nullable
	Weapon getHeldWeapon(Player player);

	/**
	 * @return {@code true} when {@code player}'s held weapon (see {@link #getHeldWeapon(Player)}) is currently
	 * 		scoped in.
	 */
	boolean isScoping(Player player);

	/**
	 * @return {@code true} when {@code player}'s held weapon (see {@link #getHeldWeapon(Player)}) is currently
	 * 		reloading.
	 */
	boolean isReloading(Player player);

	/**
	 * Starts a reload for {@code player}'s held weapon (see {@link #getHeldWeapon(Player)}) - the same guarded
	 * path {@code WeaponDroppedListener} and {@code Reload.Auto_Reload_When_Empty} already go through
	 * ({@code WeaponService.tryReload}: no-op while already reloading, magazine full, or the player lacks the
	 * ammo (and isn't creative)). weapons-roadmap.md gate {@code HH}.
	 *
	 * @return {@code true} if a reload was started; {@code false} when the player holds no weapon or the reload
	 * 		was refused.
	 */
	boolean tryReload(Player player);

}
