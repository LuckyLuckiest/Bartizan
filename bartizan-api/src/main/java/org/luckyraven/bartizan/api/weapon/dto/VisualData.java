package org.luckyraven.bartizan.api.weapon.dto;

import org.bukkit.Material;
import org.jetbrains.annotations.Nullable;

/**
 * {@code Shoot.Projectile.Visual}: which cosmetic entity {@code SteppedProjectileTask} (weapons-roadmap.md gate
 * {@code HI} part b) drives for a ROCKET/FLARE weapon's slow-projectile flight. The task now teleports this entity
 * itself every tick — Bukkit's own physics no longer drives its position — so {@code item}/{@code block} only need
 * to look plausible, not actually collide.
 *
 * @param type the entity kind to spawn — see {@link VisualType}.
 * @param item material for the {@code DROPPED_ITEM} entity or the {@code ARMOR_STAND} head; {@code null} for
 * 		every other type.
 * @param customModelData resource-pack model id applied to {@code item}'s {@link org.bukkit.inventory.ItemStack}
 * 		(0 = no override).
 * @param block material for the {@code FALLING_BLOCK} entity's block state; {@code null} for every other type.
 */
public record VisualData(VisualType type, @Nullable Material item, int customModelData, @Nullable Material block) {

	/**
	 * Bad/unrecognised {@code Visual.Type} strings fall back to the type-based default ({@code ROCKET} ->
	 * {@code FIREBALL}, {@code FLARE} -> {@code FIREWORK}) with a {@code Severity.WARNING} — see
	 * {@code GunWeaponParser}.
	 */
	public enum VisualType {
		FIREBALL,
		FIREWORK,
		DROPPED_ITEM,
		FALLING_BLOCK,
		ARMOR_STAND,
		PRIMED_TNT
	}

}
