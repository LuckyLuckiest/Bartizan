package org.luckyraven.bartizan.api.weapon.dto;

import org.luckyraven.bartizan.api.weapon.Weapon;

/**
 * {@code Shoot.Muzzle_Offset}: three "right up forward" triples (blocks) the plugin's {@code WeaponMuzzle.compute}
 * picks between based on scope/main-hand state — {@code scope} when the weapon is currently scoped, else
 * {@code leftHand}/{@code rightHand} by the shooter's main hand. A weapon with no {@code Muzzle_Offset:} section at
 * all leaves this {@code null} on {@link Weapon#getMuzzleOffsetData()}, and {@code WeaponMuzzle} falls back to its
 * historical hardcoded offset.
 * <p>
 * Fully immutable — a {@link Weapon} template copy shares the same instance rather than cloning it.
 */
public record MuzzleOffsetData(Offset rightHand, Offset leftHand, Offset scope) {

	public record Offset(double right, double up, double forward) {
	}

}
