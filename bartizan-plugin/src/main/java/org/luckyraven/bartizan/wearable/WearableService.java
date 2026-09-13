package org.luckyraven.bartizan.wearable;

import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.luckyraven.bartizan.api.wearable.Wearable;
import org.luckyraven.bartizan.api.wearable.WearableCatalog;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Central registry and calculation service for {@link Wearable} armor pieces (bartizan.md §1.1 PLG table: drops
 * Gangland's {@code WearableEquipService} indirection — the {@code contract.WearableEquipService} interface dies
 * with the split — and implements {@link WearableCatalog} instead so {@code BartizanApiImpl} can hand it out).
 *
 * <h3>Resolution order for a worn ItemStack:</h3>
 * <ol>
 *   <li>If the item has a {@code "wearable"} NBT key that matches a registered entry → use the
 *       registry's live values (config changes apply to existing items).</li>
 *   <li>If the item has a {@code "wearable"} NBT key but is no longer in the registry (e.g.
 *       removed from config) → create a temporary Wearable from the item's material so the piece
 *       still grants basic protection.</li>
 *   <li>If the item is any vanilla armor piece → create a temporary Wearable from the
 *       material.</li>
 *   <li>Otherwise → null (no reduction applied).</li>
 * </ol>
 */
public class WearableService implements WearableCatalog {

	private static final EquipmentSlot[] ARMOR_SLOTS = {
			EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
	};

	private final Map<String, Wearable> wearables = new HashMap<>();

	public void register(String key, Wearable wearable) {
		wearables.put(key.toLowerCase(), wearable);
	}

	@Override
	@Nullable
	public Wearable getWearable(String key) {
		return wearables.get(key.toLowerCase());
	}

	@Override
	public Map<String, Wearable> getWearables() {
		return Collections.unmodifiableMap(wearables);
	}

	public void clear() {
		wearables.clear();
	}

	/**
	 * Resolves a {@link Wearable} from an ItemStack according to the resolution order described in the class javadoc.
	 *
	 * @param item the armor ItemStack
	 *
	 * @return a Wearable (possibly temporary), or {@code null} if the item is not armor
	 */
	@Override
	@Nullable
	public Wearable resolveWearable(@Nullable ItemStack item) {
		if (item == null || item.getType().isAir()) return null;

		String key = Wearable.getWearableKey(item);
		if (key != null) {
			Wearable registered = getWearable(key);
			if (registered != null) return registered;
			// Key present but not in registry - fall through to vanilla fallback
		}

		return Wearable.fromItemStack(item);
	}

	/**
	 * Applies all worn-wearable damage reductions for a living entity to the given damage value. Reductions are applied
	 * multiplicatively per armor slot so that wearing four pieces of armor cannot completely negate damage through
	 * stacking alone.
	 *
	 * <p>Each slot's total reduction = wearable reduction + enchantment bonus, capped per piece.
	 *
	 * <p>The {@code reactive} trait is checked first; if any piece procs it, the full damage is nullified and
	 * processing stops immediately.
	 *
	 * @param damage incoming damage before wearable reduction
	 * @param target the entity wearing the armor
	 * @param isProjectile {@code true} when the damage source is a projectile (enables {@code bulletproof} trait and
	 *        {@code PROJECTILE_PROTECTION} enchantment bonuses)
	 *
	 * @return the final damage value (≥ 0)
	 */
	@Override
	public double applyWearableReduction(double damage, LivingEntity target, boolean isProjectile) {
		EntityEquipment equipment = target.getEquipment();
		if (equipment == null) return damage;

		for (EquipmentSlot slot : ARMOR_SLOTS) {
			ItemStack item = equipment.getItem(slot);
			if (item.getType().isAir()) continue;

			Wearable wearable = resolveWearable(item);
			if (wearable == null) continue;

			// reactive: chance to nullify the entire hit
			if (wearable.rollReactive()) return 0;

			double slotReduction = isProjectile
			                       ? wearable.getProjectileDamageReduction()
			                       : wearable.getGenericDamageReduction();

			double enchBonus = isProjectile
			                   ? Wearable.getEnchantmentProjectileBonus(item)
			                   : Wearable.getEnchantmentGenericBonus(item);

			// Hard-cap the combined reduction for this single slot
			double totalSlotReduction = Math.min(slotReduction + enchBonus, 0.90);

			// Multiplicative stacking
			damage *= (1.0 - totalSlotReduction);
		}

		return Math.max(damage, 0);
	}

	/**
	 * Reduces the bonus damage added by a critical hit, based on the {@code toughened} trait across all worn pieces.
	 *
	 * @param critBonus the raw critical-hit bonus damage
	 * @param target the entity wearing the armor
	 *
	 * @return the reduced critical-hit bonus (≥ 0)
	 */
	@Override
	public double reduceCritBonus(double critBonus, LivingEntity target) {
		EntityEquipment equipment = target.getEquipment();
		if (equipment == null) return critBonus;

		for (EquipmentSlot slot : ARMOR_SLOTS) {
			ItemStack item = equipment.getItem(slot);
			if (item.getType().isAir()) continue;

			Wearable wearable = resolveWearable(item);
			if (wearable == null) continue;

			critBonus *= (1.0 - wearable.getCritBonusReduction());
		}

		return Math.max(critBonus, 0);
	}

	/**
	 * Sums the {@code trait} level across every worn armor piece (mirrors {@link #reduceFireTicks}'s iteration),
	 * capping each piece's contribution and the total at the trait's {@link Wearable#traitMaxLevel(String)} — used
	 * by {@code StatusEffectService} to read a resistance trait (e.g. {@code sealed}) at status-apply time, which
	 * reduces the incoming level of a biological status rather than a damage/duration percentage
	 * (weapons-roadmap.md gate {@code HB} §2.2). Without the cap, several pieces each carrying the trait (or one
	 * piece configured past the trait's intended max) could sum to an effectively unlimited reduction.
	 *
	 * @param target the entity wearing the armor
	 * @param trait lower-case trait key
	 *
	 * @return the summed trait level across worn pieces, capped at the trait's max level (0 for an unknown trait)
	 */
	public int traitLevel(LivingEntity target, String trait) {
		EntityEquipment equipment = target.getEquipment();
		if (equipment == null) return 0;

		int max = Wearable.traitMaxLevel(trait);
		if (max <= 0) return 0;

		int total = 0;
		for (EquipmentSlot slot : ARMOR_SLOTS) {
			ItemStack item = equipment.getItem(slot);
			if (item.getType().isAir()) continue;

			Wearable wearable = resolveWearable(item);
			if (wearable != null) total += Math.min(wearable.traitLevel(trait), max);
		}

		return Math.min(total, max);
	}

	/**
	 * Reduces the number of fire ticks to be applied to the target, based on the {@code fire_resistant} trait and the
	 * vanilla {@code FIRE_PROTECTION} enchantment across all worn armor pieces.
	 *
	 * @param fireTicks the raw fire-tick count from the weapon
	 * @param target the entity wearing the armor
	 *
	 * @return the reduced fire-tick count (≥ 0)
	 */
	@Override
	public int reduceFireTicks(int fireTicks, LivingEntity target) {
		if (fireTicks <= 0) return 0;

		EntityEquipment equipment = target.getEquipment();
		if (equipment == null) return fireTicks;

		double reduction = 0;

		for (EquipmentSlot slot : ARMOR_SLOTS) {
			ItemStack item = equipment.getItem(slot);
			if (item.getType().isAir()) continue;

			Wearable wearable = resolveWearable(item);
			if (wearable != null) {
				reduction += wearable.getFireTickReduction();
			}

			// Vanilla FIRE_PROTECTION enchantment contributes additively
			reduction += Wearable.getEnchantmentFireBonus(item);
		}

		// Cap total fire reduction at 90 %
		reduction = Math.min(reduction, 0.90);

		return (int) Math.max(fireTicks * (1.0 - reduction), 0);
	}

}
