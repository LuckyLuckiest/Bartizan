package org.luckyraven.bartizan.api.npc;

import org.bukkit.entity.LivingEntity;

/**
 * Builds an {@link NpcWeaponController} for a spawning NPC. Bartizan owns NPC firing cadence
 * (bartizan.md §1.6(8)); consumers ask for a controller through this factory rather than constructing one
 * themselves.
 */
public interface NpcWeaponFactory {

	NpcWeaponController create(LivingEntity shooter, String weaponName, double fireRateMultiplier,
	                           double aimErrorDegrees);

}
