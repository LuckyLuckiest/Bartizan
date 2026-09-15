package org.luckyraven.bartizan.api.weapon.dto;

import org.bukkit.Material;

import java.util.Map;

/**
 * {@code Shoot.Projectile.Bouncy}: lets a ROCKET/FLARE survive a block hit instead of terminating on first
 * contact (weapons-roadmap.md gate {@code HI} part b). {@code defaultMultiplier} applies to any block not listed
 * in {@code perMaterial} (each entry resolved from a single material or a {@code BlockGroupResolver} group, e.g.
 * {@code GLASS}); {@code 0.0} means "no bounce", matching the historical behaviour of terminating on the first
 * block a slow projectile touches.
 *
 * @param defaultMultiplier velocity multiplier applied on reflection for any block not in {@code perMaterial}.
 * @param perMaterial per-material velocity multiplier overrides.
 */
public record BouncyData(double defaultMultiplier, Map<Material, Double> perMaterial) {

	/**
	 * The bounce multiplier for the given block material — {@link #perMaterial()}'s entry when listed, else
	 * {@link #defaultMultiplier()}.
	 */
	public double multiplierFor(Material material) {
		return perMaterial.getOrDefault(material, defaultMultiplier);
	}

}
