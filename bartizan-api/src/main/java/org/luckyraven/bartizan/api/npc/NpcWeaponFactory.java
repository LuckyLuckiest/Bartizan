package org.luckyraven.bartizan.api.npc;

import org.bukkit.entity.LivingEntity;

import java.util.function.Supplier;

/**
 * Builds an {@link NpcWeaponController} for a spawning NPC. Bartizan owns NPC firing cadence
 * (bartizan.md §1.6(8)); consumers ask for a controller through this factory rather than constructing one
 * themselves.
 */
public interface NpcWeaponFactory {

	/**
	 * Binds the controller to this one entity. Prefer {@link #create(Supplier, String, double, double)} for a
	 * Citizens NPC: Citizens can replace the entity after spawn, and a controller bound here keeps firing from the
	 * removed one.
	 */
	NpcWeaponController create(LivingEntity shooter, String weaponName, double fireRateMultiplier,
	                           double aimErrorDegrees);

	/**
	 * Reads the shooter from {@code shooter} on every shot, so the controller follows an NPC whose entity was
	 * replaced (e.g. {@code () -> npc.getEntity()}). While it yields {@code null} or a dead/removed entity the
	 * controller skips firing instead of throwing. Since 0.6.0; the default exists only so an older implementation
	 * still compiles - Bartizan's own factory overrides it.
	 *
	 * @throws UnsupportedOperationException from an implementation that predates 0.6.0
	 */
	default NpcWeaponController create(Supplier<? extends LivingEntity> shooter, String weaponName,
	                                   double fireRateMultiplier, double aimErrorDegrees) {
		throw new UnsupportedOperationException("live-shooter NPC weapons need Bartizan 0.6.0+");
	}

}
