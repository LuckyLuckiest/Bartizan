package org.luckyraven.bartizan.weapon;

import org.bukkit.entity.Player;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.api.weapon.dto.EffectHook;
import org.luckyraven.bartizan.api.weapon.dto.ScopeData;
import org.luckyraven.bartizan.effect.EffectContext;
import org.luckyraven.bartizan.effect.EffectRunner;

/**
 * The scope-in/scope-out side effects shared by every trigger for it (weapons-roadmap.md gate {@code HP}):
 * {@code WeaponInteract}'s click handler and {@code SpyglassScopeTask}'s scope-out poll both delegate here instead
 * of each keeping its own copy of {@link Weapon#cycleScope(Player)} + the skin refresh + the {@code ON_SCOPE_IN}/
 * {@code ON_SCOPE_OUT} effect - one implementation, one behaviour, no matter which event ends the scope.
 */
public final class ScopeToggle {

	private ScopeToggle() {
	}

	/**
	 * @return {@code true} when this call scoped in (or stepped to a further zoom stage), mirroring
	 * 		{@link Weapon#cycleScope(Player)}'s own return value; {@code false} when it unscoped.
	 */
	public static boolean apply(Weapon weapon, Player player, WeaponService weaponService, EffectRunner effectRunner) {
		boolean scopingIn = weapon.cycleScope(player);

		// gate HJ: refresh the item's Scope-state skin, but only for a weapon that actually has one configured -
		// persistHeldWeapon rebuilds+rewrites the held item unconditionally, which is otherwise unnecessary churn
		// on every scope toggle for the vast majority of weapons with no Skins: block at all.
		if (weapon.getSkinsData() != null) weaponService.persistHeldWeapon(weapon, player);

		// A scopeless weapon (scopeData == null) still reaches this method but has no ON_SCOPE_IN/ON_SCOPE_OUT of
		// its own to fire.
		ScopeData scopeData = weapon.getScopeData();
		if (scopeData != null) {
			// Zoom_Stacking stage (1-based) so Pitch_Per_Level sounds can differ per scope-in stage; 0 on
			// scope-out (currentStack is already reset by ScopeData.advanceZoomStack by this point).
			int level = scopingIn ? scopeData.getCurrentStack() + 1 : 0;
			EffectContext ctx = EffectContext.builder().weapon(weapon).source(player).level(level).build();
			effectRunner.run(weapon, scopingIn ? EffectHook.ON_SCOPE_IN : EffectHook.ON_SCOPE_OUT, ctx);
		}

		return scopingIn;
	}

}
