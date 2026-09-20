package org.luckyraven.bartizan.hud;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.api.weapon.dto.AmmunitionData;
import org.luckyraven.bartizan.api.weapon.dto.ReloadActionBarData;
import org.luckyraven.bartizan.util.BartizanChatUtil;

/**
 * The small set of weapon-state placeholders the HUD (gate {@code HD}, {@code HUD:} YAML section) and {@code
 * BartizanExpansion} (the PlaceholderAPI expansion) both read off a held weapon — one implementation shared by
 * both so they never drift.
 *
 * <table>
 *   <caption>Placeholders</caption>
 *   <tr><td>{@code %weapon%}</td><td>display name, without the ammo suffix {@code buildDisplayName()} appends</td></tr>
 *   <tr><td>{@code %ammo_left%}</td><td>current magazine, empty when the weapon has no magazine</td></tr>
 *   <tr><td>{@code %ammo_max%}</td><td>magazine capacity, {@code ∞} when the weapon has no magazine</td></tr>
 *   <tr><td>{@code %selective_fire%}</td><td>current fire mode name, empty when the weapon has none configured</td></tr>
 *   <tr><td>{@code %durability%}</td><td>{@code current/max}, empty when unbreakable ({@code Durability.Base <= 0})</td></tr>
 *   <tr><td>{@code %reload%}</td><td>{@code Reload.Action_Bar.Reloading} while reloading, else empty</td></tr>
 *   <tr><td>{@code %firearm_state%}</td><td>{@code reloading | scoping | empty | ready}</td></tr>
 *   <tr><td>{@code %reload_progress%}</td><td>0-100 integer while reloading, else empty</td></tr>
 *   <tr><td>{@code %reload_stage%}</td><td>1-based current reload stage while reloading, else empty (gate {@code HO})</td></tr>
 *   <tr><td>{@code %reload_stage_max%}</td><td>total stage count while reloading, else empty (gate {@code HO})</td></tr>
 * </table>
 */
public final class WeaponPlaceholders {

	private WeaponPlaceholders() {
	}

	/**
	 * Substitutes every placeholder above in {@code template} and colorizes the result via
	 * {@link BartizanChatUtil#color}. {@code player} is accepted for parity with other placeholder resolvers, even
	 * though nothing here currently reads it.
	 */
	public static String resolve(Weapon weapon, @Nullable Player player, String template) {
		String result = template
				.replace("%weapon%", weapon.getDisplayName())
				.replace("%ammo_left%", ammoLeft(weapon))
				.replace("%ammo_max%", ammoMax(weapon))
				.replace("%selective_fire%", selectiveFire(weapon))
				.replace("%durability%", durability(weapon))
				.replace("%reload%", reloadText(weapon))
				.replace("%firearm_state%", firearmState(weapon))
				.replace("%reload_progress%", reloadProgress(weapon))
				.replace("%reload_stage%", reloadStage(weapon))
				.replace("%reload_stage_max%", reloadStageMax(weapon));

		return BartizanChatUtil.color(result);
	}

	private static String ammoLeft(Weapon weapon) {
		return weapon.getAmmunitionData() != null ? String.valueOf(weapon.getCurrentMagCapacity()) : "";
	}

	private static String ammoMax(Weapon weapon) {
		AmmunitionData data = weapon.getAmmunitionData();
		return data != null ? String.valueOf(data.getMaxMagCapacity()) : "∞";
	}

	private static String selectiveFire(Weapon weapon) {
		return weapon.getCurrentSelectiveFire() != null ? weapon.getCurrentSelectiveFire().name() : "";
	}

	private static String durability(Weapon weapon) {
		if (weapon.getDurability() <= 0) return "";
		return weapon.getCurrentDurability() + "/" + weapon.getDurability();
	}

	private static String reloadText(Weapon weapon) {
		ReloadActionBarData actionBar = weapon.getReloadActionBarData();
		if (!weapon.isReloading() || actionBar == null || actionBar.getReloading() == null) return "";
		return actionBar.getReloading();
	}

	private static String firearmState(Weapon weapon) {
		if (weapon.isReloading()) return "reloading";
		if (weapon.getScopeData() != null && weapon.getScopeData().isScoped()) return "scoping";
		if (weapon.isMagazineEmpty()) return "empty";
		return "ready";
	}

	private static String reloadProgress(Weapon weapon) {
		if (!weapon.isReloading()) return "";
		return String.valueOf((int) Math.round(weapon.reloadProgress() * 100));
	}

	private static String reloadStage(Weapon weapon) {
		if (!weapon.isReloading()) return "";
		int index = weapon.reloadStageIndex();
		return index >= 0 ? String.valueOf(index + 1) : "";
	}

	private static String reloadStageMax(Weapon weapon) {
		if (!weapon.isReloading()) return "";
		int count = weapon.reloadStageCount();
		return count > 0 ? String.valueOf(count) : "";
	}

}
