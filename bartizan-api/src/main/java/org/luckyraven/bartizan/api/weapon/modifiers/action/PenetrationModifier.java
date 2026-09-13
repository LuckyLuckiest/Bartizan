package org.luckyraven.bartizan.api.weapon.modifiers.action;

/**
 * Allows projectiles to penetrate through blocks or entities.
 *
 * @param penetrateBlocks Number of blocks the projectile can pass through
 * @param penetrateEntities Number of entities the projectile can damage before stopping
 * @param damageReduction Percentage of damage lost per penetration (0.0 - 1.0)
 */
public record PenetrationModifier(int penetrateBlocks, int penetrateEntities, double damageReduction) {
}
