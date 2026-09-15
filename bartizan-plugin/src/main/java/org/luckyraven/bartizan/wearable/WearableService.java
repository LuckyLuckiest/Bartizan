package org.luckyraven.bartizan.wearable;

import com.cryptomorin.xseries.XSound;
import org.bukkit.Bukkit;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.Nullable;
import org.luckyraven.bartizan.api.BartizanApi;
import org.luckyraven.bartizan.api.weapon.dto.EffectHook;
import org.luckyraven.bartizan.api.weapon.dto.EffectsData;
import org.luckyraven.bartizan.api.wearable.Wearable;
import org.luckyraven.bartizan.api.wearable.WearableCatalog;
import org.luckyraven.bartizan.effect.EffectContext;
import org.luckyraven.bartizan.effect.EffectRunner;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;

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

	/**
	 * {@code Sets.<name>} registry (gate {@code HL}, §4): set name (lower-case) → the worn-piece-count → bonus
	 * table, keyed by {@code Pieces_N}'s {@code N}.
	 */
	private final Map<String, NavigableMap<Integer, SetTier>> sets = new HashMap<>();

	/**
	 * One {@code Sets.<name>.Pieces_N} tier — the trait levels and worn-effect tokens a set grants once that many
	 * pieces of the set are worn.
	 */
	public record SetTier(Map<String, Integer> traits, List<String> effectsWhileWorn) {
	}

	public void register(String key, Wearable wearable) {
		wearables.put(key.toLowerCase(), wearable);
	}

	/**
	 * Registers one {@code Sets.<name>} entry — called by {@code WearableAddon} while loading {@code wearables.yml}.
	 */
	public void registerSet(String name, NavigableMap<Integer, SetTier> tiers) {
		sets.put(name.toLowerCase(Locale.ROOT), tiers);
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
		sets.clear();
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
	 * Sums every worn piece's own trait levels, plus the {@code Traits} of whatever {@link SetTier} each worn
	 * set's piece count currently reaches, capping each trait's total at {@link Wearable#traitMaxLevel(String)}.
	 * The one seam {@link #traitLevel}, {@link #reduceCritBonus}, {@link #reduceFireTicks} and
	 * {@link #applyInsulatedReduction} all read through, so a set bonus reaches those calculations from a single
	 * place (weapons-roadmap.md gate {@code HL}, §4). {@link #applyWearableReduction} — the raw damage-reduction
	 * path — deliberately does NOT read through here (gate {@code HL} review, §2): it needed reverting to the
	 * pre-{@code HL} per-piece formula, folding a set's {@code reinforced}/{@code bulletproof} bonus in separately
	 * via {@link #activeSetTiers} instead — see that method's own javadoc.
	 *
	 * @return an unmodifiable map of trait key → capped total level (never containing an unknown trait key)
	 */
	public Map<String, Integer> resolveTraitLevels(LivingEntity target) {
		EntityEquipment equipment = target.getEquipment();
		if (equipment == null) return Map.of();

		Map<String, Integer> totals = new HashMap<>();

		for (EquipmentSlot slot : ARMOR_SLOTS) {
			ItemStack item = equipment.getItem(slot);
			if (item.getType().isAir()) continue;

			Wearable wearable = resolveWearable(item);
			if (wearable == null) continue;

			for (String trait : wearable.traits()) {
				totals.merge(trait, wearable.traitLevel(trait), Integer::sum);
			}
		}

		for (SetTier tier : activeSetTiers(target).values()) {
			for (Map.Entry<String, Integer> traitEntry : tier.traits().entrySet()) {
				totals.merge(traitEntry.getKey(), traitEntry.getValue(), Integer::sum);
			}
		}

		Map<String, Integer> capped = new HashMap<>();
		for (Map.Entry<String, Integer> entry : totals.entrySet()) {
			int max = Wearable.traitMaxLevel(entry.getKey());
			if (max <= 0) continue; // unknown trait key
			capped.put(entry.getKey(), Math.min(entry.getValue(), max));
		}
		return capped;
	}

	/**
	 * Every currently active {@link SetTier} for {@code target}: for each {@code Set:} name at least one worn
	 * piece names, the highest {@code Pieces_N} tier its worn-piece count reaches (a set with no registered
	 * {@code Sets.<name>} entry, or whose worn count doesn't reach even {@code Pieces_N}'s lowest key, is omitted).
	 */
	public Map<String, SetTier> activeSetTiers(LivingEntity target) {
		EntityEquipment equipment = target.getEquipment();
		if (equipment == null) return Map.of();

		Map<String, Integer> pieceCountBySet = new HashMap<>();
		for (EquipmentSlot slot : ARMOR_SLOTS) {
			ItemStack item = equipment.getItem(slot);
			if (item.getType().isAir()) continue;

			Wearable wearable = resolveWearable(item);
			if (wearable == null || wearable.getSet() == null || wearable.getSet().isEmpty()) continue;

			pieceCountBySet.merge(wearable.getSet().toLowerCase(Locale.ROOT), 1, Integer::sum);
		}

		Map<String, SetTier> active = new HashMap<>();
		for (Map.Entry<String, Integer> entry : pieceCountBySet.entrySet()) {
			NavigableMap<Integer, SetTier> tiers = sets.get(entry.getKey());
			if (tiers == null) continue;

			Map.Entry<Integer, SetTier> floor = tiers.floorEntry(entry.getValue());
			if (floor != null) active.put(entry.getKey(), floor.getValue());
		}
		return active;
	}

	/**
	 * Applies all worn-wearable damage reductions for a living entity to the given damage value. This is the exact
	 * pre-{@code HL} per-slot formula (gate {@code HL} review — the {@code HL} rewrite had switched this to a
	 * body-wide trait sum, which silently changed every existing loadout's numbers, e.g. +1.4% damage taken for a
	 * police vest+helmet and +31.7% for a full tactical set; restored here bit-for-bit): each piece's own
	 * {@code Base_Damage_Reduction} plus its OWN {@code reinforced}/{@code bulletproof} trait level (via
	 * {@link Wearable#getGenericDamageReduction()}/{@link Wearable#getProjectileDamageReduction()}) plus its vanilla
	 * enchantment bonus are combined and capped at 90% for that one slot, then every slot stacks multiplicatively
	 * (so four pieces can't fully negate damage through stacking alone). A worn {@link SetTier}'s own
	 * {@code reinforced}/{@code bulletproof} bonus (see {@link #activeSetTiers}) is NOT merged into any piece's own
	 * level — it folds in afterwards as one further, separate multiplicative discount, as if it were one extra
	 * "virtual" slot, computed purely from the tier's own traits through the same per-level table. A set only adds;
	 * every existing (set-less) loadout's numbers are unchanged from before {@code HL}.
	 *
	 * <p>The {@code reactive} trait is rolled per piece, in slot order; the first piece that procs nullifies the
	 * entire hit immediately (matches the pre-{@code HL} behaviour — not a body-wide roll).
	 *
	 * <p>Every other trait ({@code toughened}, {@code fire_resistant}, {@code sealed}, {@code insulated}) is instead
	 * summed body-wide via {@link #resolveTraitLevels} by their own readers ({@link #reduceCritBonus},
	 * {@link #reduceFireTicks}, {@link #traitLevel}, {@link #applyInsulatedReduction}) — only this damage-reduction
	 * path needed reverting.
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

			// reactive: chance to nullify the entire hit, rolled per piece (pre-HL behaviour)
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

		// An active worn Set's own reinforced/bulletproof bonus folds in as one further, separate discount per
		// active set — a "virtual" extra slot computed from the tier's traits alone, never merged into a worn
		// piece's own level above.
		for (SetTier tier : activeSetTiers(target).values()) {
			double setReduction = Wearable.traitBonusForLevel("reinforced", tier.traits().getOrDefault("reinforced", 0));
			if (isProjectile) {
				setReduction += Wearable.traitBonusForLevel("bulletproof", tier.traits().getOrDefault("bulletproof", 0));
			}
			if (setReduction <= 0) continue;
			damage *= (1.0 - Math.min(setReduction, 0.90));
		}

		return Math.max(damage, 0);
	}

	/**
	 * Lazily resolves the registered {@code WearableService} off Bukkit's {@code ServicesManager} via
	 * {@code BartizanApi} (gate {@code HL} review, §1): weapon actions that fire a custom raytrace impact handler
	 * (melee, biological, beam, incendiary) short-circuit {@code WeaponRaytracerImpl}'s default damage pipeline
	 * before it ever calls {@link #onHitTaken}, and none of those actions were constructed with a
	 * {@code WearableService} of their own — they're built by a sibling-owned {@code WeaponInteract}, out of scope
	 * to touch for constructor injection. Mirrors the "resolved lazily, at the point of use" rule CLAUDE.md already
	 * mandates for cross-plugin lookups, applied here to a same-plugin seam for the same reason.
	 *
	 * @return the registered service, or {@code null} if Bartizan's own {@code BartizanApi} isn't registered yet.
	 */
	@Nullable
	public static WearableService resolveLazily() {
		BartizanApi api = Bukkit.getServicesManager().load(BartizanApi.class);
		return api != null && api.wearables() instanceof WearableService wearableService ? wearableService : null;
	}

	/**
	 * Reduces the bonus damage added by a critical hit, based on the body-wide {@code toughened} trait total
	 * (including a set bonus, via {@link #resolveTraitLevels}).
	 *
	 * @param critBonus the raw critical-hit bonus damage
	 * @param target the entity wearing the armor
	 *
	 * @return the reduced critical-hit bonus (≥ 0)
	 */
	@Override
	public double reduceCritBonus(double critBonus, LivingEntity target) {
		int    toughened = resolveTraitLevels(target).getOrDefault("toughened", 0);
		double reduction = Wearable.traitBonusForLevel("toughened", toughened);
		return Math.max(critBonus * (1.0 - Math.min(reduction, 1.0)), 0);
	}

	/**
	 * The body-wide {@code trait} level (including a set bonus) — see {@link #resolveTraitLevels}. Used by
	 * {@code StatusEffectService} to read a resistance trait (e.g. {@code sealed}) at status-apply time, which
	 * reduces the incoming level of a biological status rather than a damage/duration percentage
	 * (weapons-roadmap.md gate {@code HB} §2.2).
	 *
	 * @param target the entity wearing the armor
	 * @param trait lower-case trait key
	 *
	 * @return the resolved trait level, capped at the trait's max level (0 for an unknown trait)
	 */
	public int traitLevel(LivingEntity target, String trait) {
		return resolveTraitLevels(target).getOrDefault(trait, 0);
	}

	/**
	 * Beam/energy damage reduction from the body-wide {@code insulated} trait total (weapons-roadmap.md gate
	 * {@code HL}, §3) — called from {@code BeamAction} where beam damage is applied to a living entity.
	 *
	 * @return the reduced damage (≥ 0)
	 */
	public double applyInsulatedReduction(double damage, LivingEntity target) {
		int    insulated = resolveTraitLevels(target).getOrDefault("insulated", 0);
		double reduction = Wearable.traitBonusForLevel("insulated", insulated);
		return Math.max(damage * (1.0 - Math.min(reduction, 0.90)), 0);
	}

	/**
	 * Reduces the number of fire ticks to be applied to the target, based on the body-wide {@code fire_resistant}
	 * trait total (including a set bonus) and the vanilla {@code FIRE_PROTECTION} enchantment across all worn
	 * armor pieces.
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

		int    fireResistant = resolveTraitLevels(target).getOrDefault("fire_resistant", 0);
		double reduction     = Wearable.traitBonusForLevel("fire_resistant", fireResistant);

		for (EquipmentSlot slot : ARMOR_SLOTS) {
			ItemStack item = equipment.getItem(slot);
			if (item.getType().isAir()) continue;

			// Vanilla FIRE_PROTECTION enchantment contributes additively, still per-piece
			reduction += Wearable.getEnchantmentFireBonus(item);
		}

		// Cap total fire reduction at 90 %
		reduction = Math.min(reduction, 0.90);

		return (int) Math.max(fireTicks * (1.0 - reduction), 0);
	}

	/**
	 * Fires {@code ON_HIT_TAKEN} for every worn wearable that declares it (weapons-roadmap.md gate {@code HL},
	 * §5) — called from {@code WeaponRaytracerImpl#handleEntityImpact}, right after {@link #damageArmor}.
	 * {@code effectRunner} is passed in rather than injected: {@code WearableService}/{@code WearableAddon} is a
	 * {@code FILE}-phase bean, constructed before {@code EffectRunner} exists in the bean graph, so it cannot hold
	 * one as a field — every caller that needs this already has an {@code EffectRunner} of its own.
	 */
	public void onHitTaken(LivingEntity victim, @Nullable LivingEntity source, double damage,
	                       EffectRunner effectRunner) {
		EntityEquipment equipment = victim.getEquipment();
		if (equipment == null) return;

		EffectContext ctx = EffectContext.builder().source(source).victim(victim).damage(damage).build();

		for (EquipmentSlot slot : ARMOR_SLOTS) {
			ItemStack item = equipment.getItem(slot);
			if (item.getType().isAir()) continue;

			Wearable wearable = resolveWearable(item);
			if (wearable == null) continue;

			EffectsData effects = wearable.getEffects();
			if (!effects.has(EffectHook.ON_HIT_TAKEN)) continue;

			effectRunner.run(effects, wearable.getName(), EffectHook.ON_HIT_TAKEN, ctx);
		}
	}

	/**
	 * Damages every worn armour piece's durability by {@code amount} — {@code Damage.Armor_Damage}, guns only
	 * (weapons-roadmap.md gate {@code HF}, §3). Skips unbreakable pieces and any piece with no durability bar.
	 * A piece whose damage reaches or exceeds its max durability is removed and the vanilla item-break sound plays.
	 *
	 * @param target the entity wearing the armor
	 * @param amount durability damage to apply per worn piece; a no-op when {@code <= 0}
	 */
	public void damageArmor(LivingEntity target, int amount) {
		if (amount <= 0) return;

		EntityEquipment equipment = target.getEquipment();
		if (equipment == null) return;

		for (EquipmentSlot slot : ARMOR_SLOTS) {
			ItemStack item = equipment.getItem(slot);
			if (item.getType().isAir()) continue;

			ItemMeta meta = item.getItemMeta();
			if (!(meta instanceof Damageable damageable) || meta.isUnbreakable()) continue;

			int maxDurability = item.getType().getMaxDurability();
			if (maxDurability <= 0) continue;

			int newDamage = damageable.getDamage() + amount;
			if (newDamage >= maxDurability) {
				equipment.setItem(slot, null);
				XSound.ENTITY_ITEM_BREAK.record().soundPlayer().atLocation(target.getLocation()).play();
			} else {
				damageable.setDamage(newDamage);
				item.setItemMeta(meta);
				equipment.setItem(slot, item);
			}
		}
	}

}
