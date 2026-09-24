package org.luckyraven.bartizan.listener.projectile;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataType;

/**
 * Persistent marker for a purely cosmetic entity that is not tracked by {@code WeaponVisualSpawner} (a thrown
 * grenade's display item, an effect-only firework burst). Stored on the entity's own PDC, so it also covers an
 * entity left behind by a reload or disable. {@link ProjectileDamageListener} keeps tagged entities out of vanilla
 * damage and hopper pickup.
 */
public final class CosmeticTag {

	// "bartizan" is the plugin's own namespace, so this equals new NamespacedKey(plugin, "cosmetic").
	static final NamespacedKey KEY = NamespacedKey.fromString("bartizan:cosmetic");

	private CosmeticTag() {
	}

	public static void mark(Entity entity) {
		entity.getPersistentDataContainer().set(KEY, PersistentDataType.BYTE, (byte) 1);
	}

	public static boolean isMarked(Entity entity) {
		return entity.getPersistentDataContainer().has(KEY, PersistentDataType.BYTE);
	}

}
