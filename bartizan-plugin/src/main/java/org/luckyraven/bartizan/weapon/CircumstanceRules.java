package org.luckyraven.bartizan.weapon;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.api.weapon.dto.HandlingData;
import org.luckyraven.bartizan.api.weapon.dto.HandlingData.Circumstance;
import org.luckyraven.bartizan.api.weapon.dto.HandlingData.Rule;
import org.luckyraven.bartizan.api.weapon.dto.ScopeData;

import java.util.Map;

/**
 * Evaluates {@code Shoot.Circumstance} rules (weapons-roadmap.md gate {@code HE}, part a) against a player's
 * current state. Pure function of {@code Player}/{@code Weapon} state — no side effects — so
 * {@code WeaponInteract} can call it once per genuine press and fire {@code ON_DENY} with the returned
 * circumstance's {@link Circumstance#key()} as the deny reason.
 */
public final class CircumstanceRules {

	private CircumstanceRules() {
	}

	/**
	 * @return the first configured circumstance whose rule is violated by {@code player}'s current state, or
	 * 		{@code null} when every configured circumstance is satisfied (or the weapon configures none).
	 */
	@Nullable
	public static Circumstance firstDenied(Player player, Weapon weapon) {
		HandlingData handling = weapon.getHandlingData();
		if (handling == null) return null;

		Map<Circumstance, Rule> circumstances = handling.getCircumstances();
		if (circumstances.isEmpty()) return null;

		for (Map.Entry<Circumstance, Rule> entry : circumstances.entrySet()) {
			Circumstance circumstance = entry.getKey();
			boolean      active      = isActive(player, weapon, circumstance);
			boolean      violated    = entry.getValue() == Rule.DENY ? active : !active;

			if (violated) return circumstance;
		}

		return null;
	}

	private static boolean isActive(Player player, Weapon weapon, Circumstance circumstance) {
		return switch (circumstance) {
			case SNEAKING -> player.isSneaking();
			case SPRINTING -> player.isSprinting();
			case SWIMMING -> player.isSwimming();
			case IN_MIDAIR -> !player.isOnGround();
			case RELOADING -> weapon.isReloading();
			case ZOOMING -> isZoomed(weapon);
			case AMMO_EMPTY -> weapon.isMagazineEmpty();
		};
	}

	private static boolean isZoomed(Weapon weapon) {
		ScopeData scope = weapon.getScopeData();
		return scope != null && scope.isScoped();
	}

}
