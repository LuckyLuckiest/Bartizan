package org.luckyraven.bartizan.api.wearable;

import lombok.Builder;
import lombok.Getter;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.jetbrains.annotations.Nullable;
import org.luckyraven.keystone.item.ItemBuilder;
import org.luckyraven.keystone.util.Placeholder;
import org.luckyraven.keystone.util.ChatUtil;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Represents a configurable armor piece within the Bartizan damage pipeline (bartizan.md §1.6(6) rewrite of
 * Gangland's {@code gangland-item} {@code Wearable}).
 *
 * <p>A {@code Wearable} can be:
 * <ul>
 *   <li><b>Registered</b> - defined in {@code wearables.yml} and stamped onto an ItemStack via
 *       {@link #buildItem()}, identifiable by an NBT key.</li>
 *   <li><b>Temporary</b> - created on-the-fly from a vanilla armor ItemStack via
 *       {@link #fromItemStack(ItemStack)}. Temporary wearables are never added to the main
 *       registry.</li>
 * </ul>
 *
 * <p>Traits are keyed by lower-case string (not an enum - Bartizan has no compile-time trait catalogue) and
 * jetpack-specific data lives in {@link #extraTags()} (a generic {@code Map<String,Object>}) rather than dedicated
 * fields - see {@link #traits()} / {@link #traitLevel(String)} / {@link #extraTags()}. Consumers that need to know
 * whether a wearable is a jetpack test {@code extraTags().containsKey("fuel")}.
 *
 * <p>When {@code ProjectileDamage} needs to apply armor damage reduction it calls
 * {@code WearableService.resolveWearable(ItemStack)} for each worn piece. That method first checks
 * the registry; if the item is not registered it falls back to a temporary wearable so vanilla
 * armor always contributes some reduction.
 */
@Builder
@Getter
public class Wearable {

	public static final String NBT_KEY          = "wearable";
	public static final String NBT_TRAIT_PREFIX = "wt_";
	public static final String NBT_BASE_REDUCE  = "wr_base";

	/**
	 * Per-trait {@code {maxLevel, effectPerLevel}} table, ported verbatim from Gangland's {@code WearableTrait}
	 * enum (bartizan.md §1.6(6) - copy the numbers, do not re-derive them).
	 */
	private static final Map<String, double[]> TRAIT_TABLE = Map.ofEntries(
			Map.entry("reinforced", new double[]{4, 0.05}),
			Map.entry("bulletproof", new double[]{3, 0.04}),
			Map.entry("padded", new double[]{2, 0.08}),
			Map.entry("toughened", new double[]{3, 0.10}),
			Map.entry("fire_resistant", new double[]{2, 0.25}),
			Map.entry("reactive", new double[]{3, 0.02}),
			Map.entry("lightweight", new double[]{2, 0.0}),
			Map.entry("fuel_efficient", new double[]{2, 0.10}),
			Map.entry("sealed", new double[]{3, 0})
	);

	private final Material             material;
	private final int                  customModelData;
	private final String               name;
	private final List<String>         lore;
	private final String               wearableKey;
	private final Map<String, Integer> traits;
	private final double               baseDamageReduction;
	@Nullable
	private final Color                leatherColor;
	private final boolean              temporary;

	/**
	 * Arbitrary extra NBT-stampable data (jetpack fuel/thrust/glide scalars, nested sound config, …) parsed
	 * generically from {@code wearables.yml}'s {@code Extra_Tags:} block. Only top-level {@code String} /
	 * {@code Integer} / {@code Double} values are stamped onto the built item's NBT; nested {@code Map} values
	 * (e.g. the jetpack {@code Sounds:} block) are readable via {@link #extraTags()} but never stamped.
	 */
	@Nullable
	private final Map<String, Object> extraTags;

	/**
	 * Placeholder resolver injected by {@code WearableAddon} at load time via the builder so {@link #buildItem(Player)}
	 * can resolve configured PlaceholderAPI placeholders in the display name and lore.
	 */
	@Nullable
	private final Placeholder placeholder;

	/**
	 * Extra generic damage reduction from vanilla {@code PROTECTION} enchantment. Each level contributes 1.5 %.
	 */
	public static double getEnchantmentGenericBonus(ItemStack item) {
		if (!isArmorItem(item)) return 0;
		return item.getEnchantmentLevel(Enchantment.PROTECTION) * 0.015;
	}

	/**
	 * Extra projectile damage reduction from vanilla {@code PROTECTION} and {@code PROJECTILE_PROTECTION} enchantments.
	 * Protection gives 1.5 %, Projectile Protection gives 2 % per level.
	 */
	public static double getEnchantmentProjectileBonus(ItemStack item) {
		if (!isArmorItem(item)) return 0;
		int prot     = item.getEnchantmentLevel(Enchantment.PROTECTION);
		int projProt = item.getEnchantmentLevel(Enchantment.PROJECTILE_PROTECTION);
		return prot * 0.015 + projProt * 0.02;
	}

	/**
	 * Extra fire-damage/tick reduction from vanilla {@code FIRE_PROTECTION} enchantment. Each level contributes 2 %.
	 */
	public static double getEnchantmentFireBonus(ItemStack item) {
		if (!isArmorItem(item)) return 0;
		return item.getEnchantmentLevel(Enchantment.FIRE_PROTECTION) * 0.02;
	}

	/**
	 * Extra explosion damage reduction from vanilla {@code PROTECTION} and {@code BLAST_PROTECTION} enchantments.
	 */
	public static double getEnchantmentBlastBonus(ItemStack item) {
		if (!isArmorItem(item)) return 0;
		int prot  = item.getEnchantmentLevel(Enchantment.PROTECTION);
		int blast = item.getEnchantmentLevel(Enchantment.BLAST_PROTECTION);
		return prot * 0.015 + blast * 0.025;
	}

	/**
	 * Wraps a vanilla (or unregistered) armor ItemStack as a <em>temporary</em> Wearable. The returned instance is
	 * never added to the main registry.
	 *
	 * <p>Base reduction is derived from the material tier. Traits are empty - only vanilla
	 * enchantment bonuses (resolved separately by {@code WearableService}) apply.
	 *
	 * @param item the worn armor piece
	 *
	 * @return a temporary Wearable, or {@code null} if the item is not an armor piece
	 */
	@Nullable
	public static Wearable fromItemStack(ItemStack item) {
		if (!isArmorItem(item)) return null;

		Color    leatherColor = null;
		ItemMeta itemMeta     = item.getItemMeta();

		if (isLeatherArmor(item.getType()) && itemMeta instanceof LeatherArmorMeta leatherMeta) {
			leatherColor = leatherMeta.getColor();
		}

		String displayName = (item.hasItemMeta() && Objects.requireNonNull(itemMeta).hasDisplayName()) ?
		                     itemMeta.getDisplayName() :
		                     ChatUtil.color("&7" + item.getType().name().replace("_", " ").toLowerCase());

		return Wearable.builder()
		               .material(item.getType())
		               .name(displayName)
		               .wearableKey("__vanilla__" + item.getType().name())
		               .baseDamageReduction(vanillaMaterialReduction(item.getType()))
		               .traits(Collections.emptyMap())
		               .temporary(true)
		               .leatherColor(leatherColor)
		               .build();
	}

	/**
	 * Returns whether the given ItemStack carries a registered-wearable NBT stamp.
	 */
	public static boolean isRegisteredWearable(@Nullable ItemStack item) {
		if (item == null || item.getType().isAir()) return false;
		return new ItemBuilder(item).hasNBTTag(NBT_KEY);
	}

	/**
	 * Reads the wearable registry key embedded in an ItemStack's NBT.
	 *
	 * @return the registry key, or {@code null} if the item has no such tag
	 */
	@Nullable
	public static String getWearableKey(@Nullable ItemStack item) {
		if (!isRegisteredWearable(item)) return null;
		return new ItemBuilder(item).getStringTagData(NBT_KEY);
	}

	/**
	 * Returns true if the ItemStack is a non-null, non-air piece of armor.
	 */
	public static boolean isArmorItem(@Nullable ItemStack item) {
		if (item == null || item.getType().isAir()) return false;
		return isArmorMaterial(item.getType());
	}

	/**
	 * Returns true if the given Material is an armor piece.
	 */
	public static boolean isArmorMaterial(Material material) {
		String name = material.name();
		return name.endsWith("_HELMET") || name.endsWith("_CHESTPLATE") || name.endsWith("_LEGGINGS") ||
		       name.endsWith("_BOOTS") || name.equals("TURTLE_HELMET");
	}

	/**
	 * Returns true if the material is a leather armor piece (supports dye colors).
	 */
	public static boolean isLeatherArmor(Material material) {
		return material.name().startsWith("LEATHER_");
	}

	/**
	 * Per-piece base damage reduction by vanilla material tier. These values represent a modest contribution - vanilla
	 * enchantments and custom traits build on top of them.
	 */
	private static double vanillaMaterialReduction(Material material) {
		String name = material.name();
		if (name.startsWith("LEATHER_")) return 0.04;
		if (name.startsWith("CHAINMAIL_")) return 0.06;
		if (name.startsWith("GOLDEN_")) return 0.06;
		if (name.startsWith("IRON_")) return 0.09;
		if (name.startsWith("DIAMOND_")) return 0.13;
		if (name.startsWith("NETHERITE_")) return 0.15;
		return 0.03; // TURTLE_HELMET or unknown
	}

	/**
	 * Returns the permission node for this wearable, derived from its registry key.
	 *
	 * @return {@code "bartizan.wearables.<wearableKey>"}, or {@code null} for temporary wearables
	 */
	public String getPermission() {
		if (temporary || wearableKey == null) return null;
		return "bartizan.wearables." + wearableKey;
	}

	/** The trait keys (lower-case) this wearable carries a non-zero level for. */
	public Set<String> traits() {
		return traits == null ? Collections.emptySet() : traits.keySet();
	}

	/** The level of {@code key} on this wearable, or {@code 0} if it does not carry that trait. */
	public int traitLevel(String key) {
		return traits == null ? 0 : traits.getOrDefault(key, 0);
	}

	/**
	 * The configured max level for {@code key} in {@link #TRAIT_TABLE}, or {@code 0} for an unknown trait — the cap
	 * a trait-summing service (e.g. {@code WearableService#traitLevel}) applies per piece and to the total when
	 * summing a trait across a full set of armor (e.g. {@code sealed}, which several pieces could otherwise stack
	 * past its intended ceiling).
	 */
	public static int traitMaxLevel(String key) {
		double[] definition = TRAIT_TABLE.get(key);
		return definition == null ? 0 : (int) definition[0];
	}

	/** Arbitrary extra data parsed from {@code Extra_Tags:} - see the field javadoc above. Never {@code null}. */
	public Map<String, Object> extraTags() {
		return extraTags == null ? Collections.emptyMap() : extraTags;
	}

	/**
	 * Builds an ItemStack for this registered Wearable. The item is stamped with:
	 * <ul>
	 *   <li>{@link #NBT_KEY} → {@link #wearableKey}</li>
	 *   <li>Per-trait levels under {@code "wt_<traitKey>"}</li>
	 *   <li>Base reduction under {@link #NBT_BASE_REDUCE}</li>
	 *   <li>Top-level scalar {@link #extraTags} entries, stamped as-is</li>
	 * </ul>
	 * Leather armor additionally has its dye color applied when {@link #leatherColor} is set.
	 */
	public ItemStack buildItem() {
		return buildItem(null);
	}

	public ItemStack buildItem(@Nullable Player player) {
		ItemBuilder builder = new ItemBuilder(material);
		builder.setDisplayName(resolvePlaceholder(player, name));

		List<String> resolvedLore = resolvePlaceholder(player, lore);
		if (resolvedLore != null && !resolvedLore.isEmpty()) builder.setLore(resolvedLore);

		if (customModelData > 0) {
			builder.setCustomModelData(customModelData);
		}

		// Stamp registry key and base data into NBT
		builder.addTag(NBT_KEY, wearableKey);
		builder.addTag(NBT_BASE_REDUCE, baseDamageReduction);

		// Embed per-trait levels
		if (traits != null) {
			for (Map.Entry<String, Integer> entry : traits.entrySet()) {
				builder.addTag(NBT_TRAIT_PREFIX + entry.getKey(), entry.getValue());
			}
		}

		// Apply leather dye color
		if (leatherColor != null && isLeatherArmor(material)) {
			if (builder.build().getItemMeta() instanceof LeatherArmorMeta leatherMeta) {
				leatherMeta.setColor(leatherColor);
				builder.setItemMeta(leatherMeta);
			}
		}

		// Stamp top-level scalar extra tags (fuel/thrust/glide scalars, …); nested maps (e.g. Sounds:) are not
		// stamped - readable only via extraTags() at runtime.
		if (extraTags != null) {
			for (Map.Entry<String, Object> entry : extraTags.entrySet()) {
				Object value = entry.getValue();

				if (value instanceof String stringValue) {
					builder.addTag(entry.getKey(), stringValue);
				} else if (value instanceof Integer intValue) {
					builder.addTag(entry.getKey(), (int) intValue);
				} else if (value instanceof Double doubleValue) {
					builder.addTag(entry.getKey(), (double) doubleValue);
				}
			}
		}

		return builder.build();
	}

	/**
	 * Generic damage reduction for this piece, including the {@code reinforced} trait. Capped at 80 % per single
	 * piece.
	 *
	 * @return fraction in [0.0, 0.80]
	 */
	public double getGenericDamageReduction() {
		return Math.min(baseDamageReduction + traitBonus("reinforced"), 0.80);
	}

	/**
	 * Projectile-specific reduction: generic reduction plus the {@code bulletproof} trait. Capped at 90 % per
	 * piece.
	 *
	 * @return fraction in [0.0, 0.90]
	 */
	public double getProjectileDamageReduction() {
		return Math.min(getGenericDamageReduction() + traitBonus("bulletproof"), 0.90);
	}

	/**
	 * Explosion-specific reduction: generic reduction plus the {@code padded} trait. Capped at 90 % per piece.
	 *
	 * @return fraction in [0.0, 0.90]
	 */
	public double getExplosionDamageReduction() {
		return Math.min(getGenericDamageReduction() + traitBonus("padded"), 0.90);
	}

	/**
	 * Fraction of the critical-hit bonus damage reduced by the {@code toughened} trait.
	 *
	 * @return fraction in [0.0, 1.0]
	 */
	public double getCritBonusReduction() {
		return Math.min(traitBonus("toughened"), 1.0);
	}

	/**
	 * Fraction of applied fire ticks reduced by the {@code fire_resistant} trait.
	 *
	 * @return fraction in [0.0, 1.0]
	 */
	public double getFireTickReduction() {
		return Math.min(traitBonus("fire_resistant"), 1.0);
	}

	/**
	 * Rolls whether the {@code reactive} trait triggers for this hit. Each level contributes an independent 2 %
	 * chance; all levels are accumulated into a single probability check.
	 *
	 * @return true if the incoming damage should be nullified
	 */
	public boolean rollReactive() {
		double chance = traitBonus("reactive");
		return chance > 0 && ThreadLocalRandom.current().nextDouble() < chance;
	}

	private String resolvePlaceholder(@Nullable Player player, @Nullable String text) {
		if (text == null || text.isEmpty() || placeholder == null) return text;
		return placeholder.convert(player, text);
	}

	private List<String> resolvePlaceholder(@Nullable Player player, @Nullable List<String> loreLines) {
		if (loreLines == null || loreLines.isEmpty() || placeholder == null) return loreLines;
		List<String> resolved = new ArrayList<>(loreLines.size());
		for (String line : loreLines) {
			resolved.add(line == null ? null : placeholder.convert(player, line));
		}
		return resolved;
	}

	/**
	 * Returns the total contribution of a specific trait, capped at the trait's max level. Unknown trait keys (not
	 * in {@link #TRAIT_TABLE}) contribute nothing.
	 */
	private double traitBonus(String key) {
		if (traits == null || traits.isEmpty()) return 0;
		Integer level = traits.get(key);
		if (level == null || level <= 0) return 0;

		double[] definition = TRAIT_TABLE.get(key);
		if (definition == null) return 0;

		int capped = (int) Math.min(level, definition[0]);
		return capped * definition[1];
	}

}
