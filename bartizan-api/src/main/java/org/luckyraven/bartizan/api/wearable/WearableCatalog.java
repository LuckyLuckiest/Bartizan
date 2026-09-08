package org.luckyraven.bartizan.api.wearable;

import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * The lookup + damage-reduction surface {@code WearableService} exposes across the api/plugin split (bartizan.md
 * §2 B6).
 */
public interface WearableCatalog {

	/** @return the configured wearable, or {@code null} when no wearable has that key. */
	@Nullable
	Wearable getWearable(@Nullable String key);

	Map<String, Wearable> getWearables();

	@Nullable
	Wearable resolveWearable(@Nullable ItemStack item);

	double applyWearableReduction(double damage, LivingEntity entity, boolean projectile);

	double reduceCritBonus(double bonus, LivingEntity entity);

	int reduceFireTicks(int ticks, LivingEntity entity);

}
