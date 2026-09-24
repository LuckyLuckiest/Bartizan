package org.luckyraven.bartizan.api.weapon.modifiers.action;

import org.bukkit.Material;
import org.luckyraven.bartizan.api.weapon.modifiers.BreakMode;

import java.util.Set;

/**
 * Represents a block break modifier configuration.
 *
 * @param targetMaterials The materials that can be damaged (includes group variants)
 * @param hitsRequired Number of projectile hits required to reach max damage — clamped to at least 1 (BZ-RT-13:
 *        {@link org.luckyraven.bartizan.api.weapon.modifiers.BlockDamageManager#applyDamage} divides by this value
 *        on every hit; a 0 threw ArithmeticException on the very first hit and left the block-break feature
 *        permanently broken for that weapon)
 * @param mode How the block is treated once the hit threshold is reached (see {@link BreakMode})
 */
public record BlockBreakModifier(Set<Material> targetMaterials, int hitsRequired, BreakMode mode) {

	public BlockBreakModifier {
		hitsRequired = Math.max(1, hitsRequired);
	}

	/**
	 * Checks if this modifier applies to the given material.
	 */
	public boolean appliesTo(Material material) {
		return targetMaterials.contains(material);
	}

}
