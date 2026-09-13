package org.luckyraven.bartizan.wearable;

import com.cryptomorin.xseries.XMaterial;
import lombok.CustomLog;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.jetbrains.annotations.Nullable;
import org.luckyraven.keystone.util.Placeholder;
import org.luckyraven.keystone.exception.PluginException;
import org.luckyraven.bartizan.api.wearable.Wearable;
import org.luckyraven.keystone.persistence.FileHandler;
import org.luckyraven.keystone.persistence.FileInitializer;
import org.luckyraven.keystone.persistence.FileManager;

import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;

/**
 * Parser for {@code items/wearables.yml}, rewritten per bartizan.md §1.6(6): traits are read as a
 * {@code Map<String,Integer>} keyed by lower-cased trait name (the YAML {@code Traits:} keys stay upper-case
 * unchanged — lower-casing happens here on read) instead of the deleted {@code WearableTrait} enum, and the old
 * {@code Jetpack:} block is replaced by a generic {@code Extra_Tags:} map read into {@link Wearable#extraTags()}.
 */
@CustomLog
public class WearableAddon extends WearableService implements FileInitializer {

	private final Consumer<String> permissionRegistrar;
	private final FileHandler      fileHandler;

	/**
	 * Placeholder resolver injected by the plugin bootstrap; threaded into every built {@link Wearable} so its
	 * display name and lore resolve configured PlaceholderAPI tokens at item-build time.
	 */
	@Nullable
	private final Placeholder placeholder;

	public WearableAddon(Consumer<String> permissionRegistrar, FileManager fileManager,
	                     @Nullable Placeholder placeholder) {
		this.permissionRegistrar = permissionRegistrar;
		this.placeholder         = placeholder;

		try {
			String fileName = "wearables";

			fileManager.checkFileLoaded(fileName);

			this.fileHandler = Objects.requireNonNull(fileManager.getFile(fileName));
		} catch (IOException exception) {
			throw new PluginException(exception);
		}
	}

	/**
	 * Parses a CSS-style hex color string ({@code "#RRGGBB"} or {@code "RRGGBB"}) into a Bukkit {@link Color}. Returns
	 * {@code null} if parsing fails.
	 */
	private static Color parseHexColor(String hex) {
		try {
			String clean = hex.startsWith("#") ? hex.substring(1) : hex;
			int    rgb   = Integer.parseUnsignedInt(clean, 16);
			return Color.fromRGB((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/**
	 * Translates the pre-split {@code Jetpack:} block (Gangland 0.8.4 {@code wearables.yml}) into the
	 * {@code Extra_Tags:} shape the gadget module reads: the fuel tags Gangland's {@code FuelKey} expects plus the
	 * lower-cased, {@code jetpack_}-prefixed physics scalars. Defaults mirror the 0.8.4 loader.
	 * {@code Glide_Descent_Rate} is dropped (dead since 152eba4); {@code Sound:} is carried over as {@code Sounds:}.
	 */
	static Map<String, Object> legacyJetpackToExtraTags(ConfigurationSection jetpack) {
		Map<String, Object> tags    = new LinkedHashMap<>();
		int                 maxFuel = jetpack.getInt("Max_Fuel", 3600);

		tags.put("fuel", jetpack.getString("Fuel_Key", ""));
		tags.put("fuel_current", maxFuel);
		tags.put("fuel_max", maxFuel);
		tags.put("jetpack_fuel_consumption_rate", jetpack.getInt("Fuel_Consumption_Rate", 2));
		tags.put("jetpack_ascend_power", jetpack.getDouble("Ascend_Power", 0.35));
		tags.put("jetpack_max_speed_y", jetpack.getDouble("Max_Speed_Y", 0.8));

		ConfigurationSection sound = jetpack.getConfigurationSection("Sound");
		if (sound != null) tags.put("Sounds", sectionToMap(sound));

		return tags;
	}

	/**
	 * Recursively converts a {@link ConfigurationSection} into a plain {@code Map<String,Object>} so nested blocks
	 * (e.g. {@code Extra_Tags.Sounds}) come out as real {@code Map} instances rather than {@code MemorySection}
	 * objects — the shape {@code extraTags()} consumers (gadget's jetpack code, per bartizan.md §1.6(6)) expect.
	 */
	private static Map<String, Object> sectionToMap(ConfigurationSection section) {
		Map<String, Object> map = new LinkedHashMap<>();
		for (String key : section.getKeys(false)) {
			Object value = section.get(key);
			if (value instanceof ConfigurationSection nested) {
				value = sectionToMap(nested);
			}
			map.put(key, value);
		}
		return map;
	}

	@Override
	public FileHandler getFileHandler() {
		return fileHandler;
	}

	@Override
	public void initialize() {
		loadWearables(fileHandler.getFileConfiguration());
	}

	private void loadWearables(FileConfiguration config) {
		List<String> loaded = new ArrayList<>();

		for (String key : config.getKeys(false)) {
			ConfigurationSection section = config.getConfigurationSection(key);
			if (section == null) continue;

			String materialString = section.getString("Material");
			String name           = section.getString("Name");

			if (materialString == null || name == null) {
				log.warn("Wearable '{}' is missing Material or Name - skipped.", key);
				continue;
			}

			Material material = XMaterial.matchXMaterial(materialString).orElse(XMaterial.BARRIER).get();

			if (material == null || !Wearable.isArmorMaterial(material)) {
				log.warn("Wearable '{}' has invalid/non-armor Material '{}' - skipped.", key, materialString);
				continue;
			}

			int          customModelData     = section.getInt("Custom_Model_Data", 0);
			double       baseDamageReduction = section.getDouble("Base_Damage_Reduction", 0.0);
			List<String> lore                = section.getStringList("Lore");

			// Leather color (optional - only meaningful for leather armor)
			Color  leatherColor      = null;
			String leatherColorValue = section.getString("Leather_Color");
			if (leatherColorValue != null && !leatherColorValue.isEmpty() && Wearable.isLeatherArmor(material)) {
				leatherColor = parseHexColor(leatherColorValue);
			}

			// Traits - string-keyed (lower-cased on read), per §1.6(6): the YAML keys stay upper-case unchanged.
			Map<String, Integer> traits        = new HashMap<>();
			ConfigurationSection traitsSection = section.getConfigurationSection("Traits");
			if (traitsSection != null) {
				for (String traitKey : traitsSection.getKeys(false)) {
					int level = Math.max(1, traitsSection.getInt(traitKey, 1));
					traits.put(traitKey.toLowerCase(Locale.ROOT), level);
				}
			}

			// Extra_Tags (optional) - generic replacement for the old Jetpack: block. Nested sections (e.g.
			// Sounds:) come out as real Maps via sectionToMap(); only top-level scalars are ever NBT-stamped by
			// Wearable.buildItem().
			Map<String, Object> extraTags     = null;
			ConfigurationSection extraSection = section.getConfigurationSection("Extra_Tags");
			if (extraSection != null) {
				extraTags = sectionToMap(extraSection);
			} else if (section.getConfigurationSection("Jetpack") != null) {
				// A wearables.yml carried over from Gangland 0.8.4 still has the pre-split Jetpack: block. Left
				// untranslated, extraTags() stays empty, the built item never gets its fuel tags and the gadget
				// module's isJetpack() check fails silently - the jetpack never activates.
				extraTags = legacyJetpackToExtraTags(section.getConfigurationSection("Jetpack"));
				log.warn("Wearable '{}' uses the legacy Jetpack: block - translated to Extra_Tags for this load; " +
				         "rename it in wearables.yml (see documentation/migration.md).", key);
			}

			Wearable wearable = Wearable.builder()
			                            .material(material)
			                            .customModelData(customModelData)
			                            .name(name)
			                            .lore(lore.isEmpty() ? null : lore)
			                            .wearableKey(key)
			                            .baseDamageReduction(Math.max(0.0, Math.min(baseDamageReduction, 1.0)))
			                            .traits(traits)
			                            .leatherColor(leatherColor)
			                            .temporary(false)
			                            .extraTags(extraTags)
			                            .placeholder(placeholder)
			                            .build();

			register(key, wearable);
			permissionRegistrar.accept(wearable.getPermission());
			loaded.add(key);
		}

		log.debug("Loaded the following wearables:");
		log.debug(loaded);
	}

}
