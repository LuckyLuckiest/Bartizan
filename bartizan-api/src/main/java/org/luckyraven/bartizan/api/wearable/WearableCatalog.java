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

	/**
	 * Registers a {@link Wearable} under {@code key} so a worn item carrying the matching {@code "wearable"} NBT
	 * tag ({@link Wearable#NBT_KEY}) resolves to it for damage-reduction/effects purposes (WS7-D4: the
	 * external-registration hook a soft-dependent plugin uses to hand Bartizan an armour identity + traits without
	 * Bartizan gaining any caller-specific code).
	 *
	 * <p>{@code wearable} must be built with {@code .external(true)} and at minimum {@code wearableKey}/
	 * {@code baseDamageReduction}/{@code traits} (an optional {@code effects} block is honoured too). Bartizan
	 * applies its armour behaviour to any worn item carrying the matching tag, but an external entry is
	 * damage-only: it is never built, converted, given, listed or serialised by Bartizan — {@link Wearable#getPermission()}
	 * returns {@code null} for it, {@code /bartizan wearable give/info/list} all skip it, and Bartizan's own
	 * {@code wearable:} item-vocabulary converter/serializer/refresher never claim it. The owning plugin is
	 * responsible for building the ItemStack, stamping the {@code "wearable"} tag itself, and for its own
	 * give/converter/refresher/list surface — Bartizan only ever reads the registered definition to compute a
	 * reduction or run an effect.
	 */
	void register(String key, Wearable wearable);

	@Nullable
	Wearable resolveWearable(@Nullable ItemStack item);

	double applyWearableReduction(double damage, LivingEntity entity, boolean projectile);

	double reduceCritBonus(double bonus, LivingEntity entity);

	int reduceFireTicks(int ticks, LivingEntity entity);

}
