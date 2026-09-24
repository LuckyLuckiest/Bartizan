package org.luckyraven.bartizan.hud;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.luckyraven.bartizan.Bartizan;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.weapon.WeaponService;

import java.util.Locale;

/**
 * PlaceholderAPI expansion for a player's held weapon (weapons-roadmap.md gate {@code HD}), identifier
 * {@code bartizan}: {@code %bartizan_<param>%}, backed by the same {@link WeaponPlaceholders} lookups the HUD
 * ticker uses. Never loaded unless PlaceholderAPI is installed — see {@link PlaceholderApiSupport}.
 *
 * <p>Params: {@code ammo_left}, {@code ammo_max}, {@code reload}, {@code reload_progress}, {@code reload_stage},
 * {@code reload_stage_max} (gate {@code HO}), {@code firearm_state}, {@code selective_fire}, {@code durability},
 * {@code weapon_title} (the display name — {@code %weapon%} in {@link WeaponPlaceholders}, renamed here to avoid
 * clashing with other expansions' generic {@code weapon} param).
 */
class BartizanExpansion extends PlaceholderExpansion {

	private final Bartizan      bartizan;
	private final WeaponService weaponService;

	BartizanExpansion(Bartizan bartizan, WeaponService weaponService) {
		this.bartizan      = bartizan;
		this.weaponService = weaponService;
	}

	@Override
	public @NotNull String getIdentifier() {
		return "bartizan";
	}

	@Override
	public @NotNull String getAuthor() {
		return String.join(", ", bartizan.getDescription().getAuthors());
	}

	@Override
	public @NotNull String getVersion() {
		return bartizan.getDescription().getVersion();
	}

	@Override
	public boolean persist() {
		return true;
	}

	@Override
	@Nullable
	public String onPlaceholderRequest(@Nullable Player player, @NotNull String params) {
		if (player == null) return "";

		ItemStack item   = player.getInventory().getItemInMainHand();
		// PlaceholderAPI may call this off the main thread - read-only lookup only (BZ-HU-03)
		Weapon    weapon = weaponService.peekWeapon(item);
		if (weapon == null) return "";

		return switch (params.toLowerCase(Locale.ROOT)) {
			case "ammo_left" -> WeaponPlaceholders.resolve(weapon, player, "%ammo_left%");
			case "ammo_max" -> WeaponPlaceholders.resolve(weapon, player, "%ammo_max%");
			case "reload" -> WeaponPlaceholders.resolve(weapon, player, "%reload%");
			case "reload_progress" -> WeaponPlaceholders.resolve(weapon, player, "%reload_progress%");
			case "reload_stage" -> WeaponPlaceholders.resolve(weapon, player, "%reload_stage%");
			case "reload_stage_max" -> WeaponPlaceholders.resolve(weapon, player, "%reload_stage_max%");
			case "firearm_state" -> WeaponPlaceholders.resolve(weapon, player, "%firearm_state%");
			case "selective_fire" -> WeaponPlaceholders.resolve(weapon, player, "%selective_fire%");
			case "durability" -> WeaponPlaceholders.resolve(weapon, player, "%durability%");
			case "weapon_title" -> WeaponPlaceholders.resolve(weapon, player, "%weapon%");
			default -> null;
		};
	}

}
