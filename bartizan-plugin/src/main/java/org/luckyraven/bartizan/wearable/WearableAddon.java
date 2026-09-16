package org.luckyraven.bartizan.wearable;

import com.cryptomorin.xseries.XAttribute;
import com.cryptomorin.xseries.XMaterial;
import lombok.CustomLog;
import org.bukkit.Color;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.Material;
import org.jetbrains.annotations.Nullable;
import org.luckyraven.keystone.util.Placeholder;
import org.luckyraven.keystone.exception.PluginException;
import org.luckyraven.bartizan.api.weapon.dto.EffectsData;
import org.luckyraven.bartizan.api.weapon.dto.HandlingData;
import org.luckyraven.bartizan.api.wearable.Wearable;
import org.luckyraven.bartizan.configuration.parser.EffectsSectionParser;
import org.luckyraven.bartizan.wearable.WearableService.SetTier;
import org.luckyraven.keystone.persistence.FileHandler;
import org.luckyraven.keystone.persistence.FileInitializer;
import org.luckyraven.keystone.persistence.FileManager;
import org.luckyraven.keystone.persistence.config.ConfigNode;
import org.luckyraven.keystone.persistence.config.ConfigReport;
import org.luckyraven.keystone.persistence.config.FileHandlerReader;
import org.luckyraven.keystone.persistence.config.MappingNode;
import org.luckyraven.keystone.persistence.config.NodeReader;
import org.luckyraven.keystone.persistence.config.ScalarNode;
import org.luckyraven.keystone.persistence.config.SequenceNode;
import org.luckyraven.keystone.persistence.config.Severity;

import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for {@code items/wearables.yml}, rewritten onto the {@code FileHandlerReader}/{@code NodeReader}/
 * {@code ConfigReport} pipeline {@code WeaponAddon.registerWeapon} already uses (weapons-roadmap.md gate
 * {@code HL}, §1) — a bad wearable entry is reported exactly like a bad weapon instead of a bare {@code log.warn}.
 * Traits are read as a {@code Map<String,Integer>} keyed by lower-cased trait name (the YAML {@code Traits:} keys
 * stay upper-case unchanged — lower-casing happens here on read) instead of the deleted {@code WearableTrait}
 * enum. {@code Extra_Tags:} is read generically into {@link Wearable#extraTags()}.
 *
 * <p>Only a missing/invalid {@code Material} skips the whole entry (with a {@link Severity#WARNING}); every other
 * bad value (an unrecognised trait/attribute key, a malformed {@code Sets} tier key, an out-of-range number) is
 * itself reported by {@code NodeReader}'s typed accessors or a targeted warning here, and defaults/skips just that
 * one value.
 */
@CustomLog
public class WearableAddon extends WearableService implements FileInitializer {

	/**
	 * {@code Sets.<name>} tier keys — {@code Pieces_2}, {@code Pieces_4}, … — {@code N} is the worn-piece count
	 * the tier activates at.
	 */
	private static final Pattern PIECES_KEY = Pattern.compile("^Pieces_(\\d+)$", Pattern.CASE_INSENSITIVE);

	/** Top-level keys that are never a wearable entry, skipped by {@link #loadWearables}. */
	private static final Set<String> RESERVED_ROOT_KEYS = Set.of("sets", "config_version");

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
	 * Converts a positional {@link MappingNode} into a plain {@code Map<String,Object>} — the {@code Extra_Tags:}
	 * read path used for the {@code Extra_Tags:} block. Nested mappings
	 * become nested maps, sequences become lists, scalars are best-effort typed (int, then double, then boolean,
	 * else the raw string) so downstream {@code ConfigurationSection}/{@code Map} readers see the type they expect.
	 */
	private static Map<String, Object> nodeToMap(MappingNode mapping) {
		Map<String, Object> map = new LinkedHashMap<>();
		for (Map.Entry<String, ConfigNode> entry : mapping.entries().entrySet()) {
			map.put(entry.getKey(), nodeToValue(entry.getValue()));
		}
		return map;
	}

	@Nullable
	private static Object nodeToValue(ConfigNode node) {
		if (node instanceof ScalarNode scalar) return scalarValue(scalar.value());
		if (node instanceof MappingNode mapping) return nodeToMap(mapping);
		if (node instanceof SequenceNode seq) {
			List<Object> list = new ArrayList<>(seq.items().size());
			for (ConfigNode item : seq.items()) list.add(nodeToValue(item));
			return list;
		}
		return null;
	}

	private static Object scalarValue(String raw) {
		try {
			return Integer.parseInt(raw);
		} catch (NumberFormatException ignored) {
			// not an int - fall through
		}
		try {
			return Double.parseDouble(raw);
		} catch (NumberFormatException ignored) {
			// not a number either - fall through
		}
		if (raw.equalsIgnoreCase("true")) return Boolean.TRUE;
		if (raw.equalsIgnoreCase("false")) return Boolean.FALSE;
		return raw;
	}

	@Override
	public FileHandler getFileHandler() {
		return fileHandler;
	}

	@Override
	public void initialize() {
		ConfigReport report = load();
		if (!report.isEmpty()) report.log(log);
	}

	/**
	 * Package-visible so {@code WearableAddonTest} can inspect the resulting {@link ConfigReport} directly, the
	 * same way {@code WeaponAddonTest} inspects {@code WeaponAddon.registerWeapon}'s return value.
	 */
	ConfigReport load() {
		ConfigReport report = new ConfigReport();
		NodeReader   root   = FileHandlerReader.read(fileHandler, report);

		loadSets(root, report);
		loadWearables(root, report);

		return report;
	}

	/**
	 * Parses the top-level {@code Sets:} section (weapons-roadmap.md gate {@code HL}, §4) into
	 * {@link WearableService#registerSet}. An unrecognised tier key (not matching {@code Pieces_N}) is a
	 * {@link Severity#WARNING} and that one tier is skipped; the rest of the set keeps loading.
	 */
	private void loadSets(NodeReader root, ConfigReport report) {
		MappingNode setsSection = root.get("Sets").asMapping().orNull();
		if (setsSection == null) return;

		NodeReader setsReader = NodeReader.of(setsSection, report);

		for (String setName : setsReader.keys()) {
			MappingNode setMapping = setsReader.get(setName).asMapping().orNull();
			if (setMapping == null) continue;

			NodeReader                     tiersReader = NodeReader.of(setMapping, report);
			NavigableMap<Integer, SetTier> tiers       = new TreeMap<>();

			for (String tierKey : tiersReader.keys()) {
				Matcher matcher = PIECES_KEY.matcher(tierKey);
				if (!matcher.matches()) {
					report.add(Severity.WARNING, setMapping.location(),
					           joinPath(setMapping.path(), tierKey),
					           "set '" + setName + "' has an unrecognised tier key '" + tierKey +
					           "' (expected Pieces_N) - skipped", "wearable.unknown_set_tier");
					continue;
				}

				MappingNode tierMapping = tiersReader.get(tierKey).asMapping().orNull();
				if (tierMapping == null) continue;

				NodeReader   tier             = NodeReader.of(tierMapping, report);
				Map<String, Integer> traits   = readTraits(tier, report, "set '" + setName + "'");
				List<String> effectsWhileWorn = tier.get("Effects_While_Worn").asList().ofStrings().orEmpty();

				tiers.put(Integer.parseInt(matcher.group(1)), new SetTier(traits, effectsWhileWorn));
			}

			if (!tiers.isEmpty()) registerSet(setName, tiers);
		}
	}

	private void loadWearables(NodeReader root, ConfigReport report) {
		List<String> loaded = new ArrayList<>();

		for (String key : root.keys()) {
			if (RESERVED_ROOT_KEYS.contains(key.toLowerCase(Locale.ROOT))) continue;

			MappingNode section = root.get(key).asMapping().orNull();
			if (section == null) continue;

			NodeReader wearable = NodeReader.of(section, report);

			String   materialString = wearable.get("Material").asString().orNull();
			Material material       = materialString != null
			                         ? XMaterial.matchXMaterial(materialString).map(XMaterial::get).orElse(null)
			                         : null;

			if (material == null || !Wearable.isArmorMaterial(material)) {
				report.add(Severity.WARNING, section.location(), joinPath(section.path(), "Material"),
				           "wearable '" + key + "' has a missing/invalid Material '" + materialString +
				           "' - skipped", "wearable.invalid_material");
				// gate HL review, §6: mark every other key in this abandoned entry touched too, so the report's
				// end-of-load unknown-key sweep doesn't bury the one real warning above under a spurious
				// config.unknown_key for every field this entry never got around to reading (Name, Traits, ...).
				wearable.markAllKeysTouched();
				continue;
			}

			String       name                = wearable.get("Name").asString().orDefault(key);
			int          customModelData     = wearable.get("Custom_Model_Data").asInt().min(0).orDefault(0);
			double       baseDamageReduction = wearable.get("Base_Damage_Reduction").asDouble().min(0).max(1)
			                                          .orDefault(0.0);
			List<String> lore                = wearable.get("Lore").asList().ofStrings().orEmpty();

			Color  leatherColor      = null;
			String leatherColorValue = wearable.get("Leather_Color").asString().orNull();
			if (leatherColorValue != null && !leatherColorValue.isEmpty() && Wearable.isLeatherArmor(material)) {
				leatherColor = parseHexColor(leatherColorValue);
			}

			Map<String, Integer> traits = readTraits(wearable, report, "wearable '" + key + "'");

			List<HandlingData.AttributeEntry> attributes = readAttributes(wearable, key, report);
			appendSwiftAttribute(attributes, traits);

			String set = wearable.get("Set").asString().orNull();
			List<String> effectsWhileWorn = wearable.get("Effects_While_Worn").asList().ofStrings().orEmpty();

			MappingNode effectsSection = wearable.get("Effects").asMapping().orNull();
			NodeReader  effectsReader  = effectsSection != null ? NodeReader.of(effectsSection, report) : null;
			EffectsData effects        = EffectsSectionParser.parse(effectsReader, report);

			Map<String, Object> extraTags = readExtraTags(wearable, key);

			Wearable built = Wearable.builder()
			                         .material(material)
			                         .customModelData(customModelData)
			                         .name(name)
			                         .lore(lore.isEmpty() ? null : lore)
			                         .wearableKey(key)
			                         .baseDamageReduction(baseDamageReduction)
			                         .traits(traits)
			                         .leatherColor(leatherColor)
			                         .temporary(false)
			                         .extraTags(extraTags)
			                         .attributes(attributes)
			                         .set(set != null && !set.isBlank() ? set.toLowerCase(Locale.ROOT) : null)
			                         .effectsWhileWorn(effectsWhileWorn)
			                         .effects(effects)
			                         .placeholder(placeholder)
			                         .build();

			register(key, built);
			permissionRegistrar.accept(built.getPermission());
			loaded.add(key);
		}

		log.debug("Loaded the following wearables:");
		log.debug(loaded);
	}

	/**
	 * Reads a {@code Traits:} section (shared by a wearable's own block and a {@code Sets.<name>.Pieces_N} tier) —
	 * string-keyed (lower-cased on read), per §1.6(6): the YAML keys stay upper-case unchanged. A level below 1 is
	 * clamped to 1 by {@code NodeReader}'s own {@code min(1)}, matching the pre-{@code HL} loader's
	 * {@code Math.max(1, ...)} behaviour, now with the clamp itself reported.
	 */
	private Map<String, Integer> readTraits(NodeReader parent, ConfigReport report, String ownerDescription) {
		Map<String, Integer> traits        = new HashMap<>();
		MappingNode           traitsSection = parent.get("Traits").asMapping().orNull();
		if (traitsSection == null) return traits;

		NodeReader traitsReader = NodeReader.of(traitsSection, report);
		for (String traitKey : traitsReader.keys()) {
			int level = traitsReader.get(traitKey).asInt().min(1).orDefault(1);
			traits.put(traitKey.toLowerCase(Locale.ROOT), level);
		}
		return traits;
	}

	/**
	 * Parses {@code Attributes:} as a map ({@code Armor: 4.0}, …) rather than the weapon {@code Information.
	 * Attributes} positional-string DSL (weapons-roadmap.md gate {@code HL}, §2) — every entry becomes an
	 * {@code ADD_NUMBER} modifier; the piece's own armour slot group is resolved later, at
	 * {@link Wearable#buildItem}, from its material. An unrecognised attribute key is a
	 * {@link Severity#WARNING} — that one entry is skipped.
	 */
	private List<HandlingData.AttributeEntry> readAttributes(NodeReader wearable, String key, ConfigReport report) {
		MappingNode attributesSection = wearable.get("Attributes").asMapping().orNull();
		if (attributesSection == null) return new ArrayList<>();

		NodeReader                        attributesReader = NodeReader.of(attributesSection, report);
		List<HandlingData.AttributeEntry> entries          = new ArrayList<>();

		for (String attrKey : attributesReader.keys()) {
			double             amount    = attributesReader.get(attrKey).asDouble().orDefault(0.0);
			Optional<Attribute> attribute = resolveAttribute(attrKey);

			if (attribute.isEmpty()) {
				report.add(Severity.WARNING, attributesSection.location(), joinPath(attributesSection.path(), attrKey),
				           "wearable '" + key + "' has an unrecognised Attributes key '" + attrKey + "' - skipped",
				           "wearable.unknown_attribute");
				continue;
			}

			entries.add(new HandlingData.AttributeEntry(attribute.get(), AttributeModifier.Operation.ADD_NUMBER,
			                                            amount));
		}
		return entries;
	}

	/**
	 * {@code swift} (weapons-roadmap.md gate {@code HL}, §3): a {@code MOVEMENT_SPEED ADD_SCALAR} modifier equal to
	 * {@code 0.05 * level}, stamped through the very same {@code AttributeModifiers.apply} call every other
	 * {@code Attributes:} entry goes through — appended here rather than read from YAML.
	 */
	private void appendSwiftAttribute(List<HandlingData.AttributeEntry> attributes, Map<String, Integer> traits) {
		int level = traits.getOrDefault("swift", 0);
		if (level <= 0) return;

		resolveAttribute("MOVEMENT_SPEED").ifPresent(attribute -> attributes.add(new HandlingData.AttributeEntry(
				attribute, AttributeModifier.Operation.ADD_SCALAR, Wearable.traitBonusForLevel("swift", level))));
	}

	private Optional<Attribute> resolveAttribute(String key) {
		return XAttribute.of(key.toUpperCase(Locale.ROOT)).map(XAttribute::get);
	}

	/**
	 * {@code Extra_Tags:} (generic). {@code null} when absent.
	 */
	@Nullable
	private Map<String, Object> readExtraTags(NodeReader wearable, String key) {
		MappingNode extraSection = wearable.get("Extra_Tags").asMapping().orNull();
		if (extraSection != null) return nodeToMap(extraSection);

		return null;
	}

	private static String joinPath(@Nullable String parent, String key) {
		return parent == null || parent.isEmpty() ? key : parent + "." + key;
	}

}
