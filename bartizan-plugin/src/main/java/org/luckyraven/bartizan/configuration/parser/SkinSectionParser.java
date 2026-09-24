package org.luckyraven.bartizan.configuration.parser;

import org.bukkit.NamespacedKey;
import org.jetbrains.annotations.Nullable;
import org.luckyraven.bartizan.api.weapon.SkinState;
import org.luckyraven.bartizan.api.weapon.dto.SkinsData;
import org.luckyraven.keystone.persistence.config.ConfigNode;
import org.luckyraven.keystone.persistence.config.ConfigReport;
import org.luckyraven.keystone.persistence.config.MappingNode;
import org.luckyraven.keystone.persistence.config.NodeReader;
import org.luckyraven.keystone.persistence.config.Severity;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Parses a weapon's {@code Skins:} section (weapons-roadmap.md gate {@code HJ}) into a {@link SkinsData} —
 * category agnostic, applied from {@code WeaponAddon.registerWeapon} like {@code HudSectionParser}. Only the five
 * {@link SkinState} keys plus {@code Named}/{@code Item_Model} are ever read here, so any other key inside
 * {@code Skins:} or one {@code Named.<name>} entry falls through to {@code ConfigReport}'s generic unknown-key
 * sweep as a {@code config.unknown_key} warning — no separate validation needed for that case.
 */
public final class SkinSectionParser {

	private SkinSectionParser() {
	}

	/**
	 * @param skinsSection the {@code Skins:} mapping reader, or {@code null} when the section is absent.
	 * @param informationCustomModelData the weapon's {@code Information.Custom_Model_Data} (0 when unconfigured)
	 * 		— used only to decide whether the root state table below is missing every fallback (review finding 3).
	 *
	 * @return the parsed data, or {@code null} when the section is absent — {@code Weapon#getSkinsData() == null}
	 * 		means the weapon renders entirely through {@code Information.Custom_Model_Data}/{@code Item_Model}.
	 */
	@Nullable
	public static SkinsData parse(@Nullable NodeReader skinsSection, int informationCustomModelData,
	                              ConfigReport report) {
		if (skinsSection == null) return null;

		Map<SkinState, Integer> states = parseStates(skinsSection, report);

		// A weapon with only e.g. Skins.Reload configured (no Default) and no Information.Custom_Model_Data
		// either has every other state resolve to "no custom model data at all" - worth flagging, since it's
		// easy to configure by accident (weapons-roadmap.md gate HJ review finding 3).
		if (!states.isEmpty() && !states.containsKey(SkinState.DEFAULT) && informationCustomModelData <= 0) {
			String path = skinsSection.mapping().path();
			report.add(Severity.WARNING, skinsSection.mapping().location(), path == null || path.isEmpty() ? "Skins" : path,
			           "Skins configures per-state custom model data with no Default and no "
			           + "Information.Custom_Model_Data - states left unconfigured will render with no custom "
			           + "model data at all",
			           "skins.no_default_fallback");
		}

		Map<String, SkinsData.NamedSkin> named = new LinkedHashMap<>();
		MappingNode namedSection = skinsSection.get("Named").asMapping().orNull();
		if (namedSection != null) {
			NodeReader namedReader = NodeReader.of(namedSection, report);

			for (String skinName : namedReader.keys()) {
				// "default" is the reserved clear-selection keyword baked into the command syntax
				// <name|default> (documentation/bartizan-api.md:153) and into WeaponSkinCommand's contract - a
				// Named skin with this name could never be reached, so reject it here rather than let the command
				// silently treat the literal name as "clear selection" instead of the configured skin.
				if (skinName.equalsIgnoreCase("default")) {
					reportReservedName(namedReader, skinName, report);
					continue;
				}

				MappingNode skinMapping = namedReader.get(skinName).asMapping().orNull();
				if (skinMapping == null) continue;

				NodeReader skinReader = NodeReader.of(skinMapping, report);
				named.put(skinName.toLowerCase(Locale.ROOT),
				          new SkinsData.NamedSkin(parseStates(skinReader, report),
				                                  parseItemModel(skinReader, "Item_Model", report)));
			}
		}

		return new SkinsData(states, named);
	}

	/**
	 * Parses one {@code Item_Model} key — root {@code Information.Item_Model} or a {@code Named.<name>.Item_Model}
	 * override — into a {@link NamespacedKey}. A present-but-malformed value is a {@link Severity#WARNING}; the
	 * key is then ignored (treated as absent) rather than failing the file.
	 */
	@Nullable
	public static NamespacedKey parseItemModel(NodeReader parent, String key, ConfigReport report) {
		NodeReader.NodeAccess access = parent.get(key);
		if (!access.exists()) return null;

		String raw = access.asString().orNull();
		if (raw == null) return null;

		NamespacedKey parsed = NamespacedKey.fromString(raw.trim());
		if (parsed == null) {
			reportMalformed(parent, key, raw, report, "skins.bad_item_model");
		}

		return parsed;
	}

	/**
	 * Reads each {@link SkinState} key as a raw string and parses it manually (rather than {@code
	 * NodeReader.NodeAccess#asInt()}) so a present-but-non-numeric value is a {@link Severity#WARNING} scoped to
	 * skins — not the generic {@code config.int} {@link Severity#ERROR} the shared int accessor would otherwise
	 * report — and is then ignored (treated as absent) rather than silently becoming 0 (review finding 6).
	 */
	private static Map<SkinState, Integer> parseStates(NodeReader reader, ConfigReport report) {
		Map<SkinState, Integer> states = new EnumMap<>(SkinState.class);

		for (SkinState state : SkinState.values()) {
			NodeReader.NodeAccess access = reader.get(state.key());
			if (!access.exists()) continue;

			String raw = access.asString().orNull();
			if (raw == null) continue;

			try {
				states.put(state, Integer.parseInt(raw.trim()));
			} catch (NumberFormatException e) {
				reportMalformed(reader, state.key(), raw, report, "skins.bad_state_value");
			}
		}

		return states;
	}

	private static void reportMalformed(NodeReader parent, String key, String raw, ConfigReport report,
	                                    String issueId) {
		ConfigNode node       = parent.mapping().get(key);
		String     parentPath = parent.mapping().path();
		String     path       = parentPath == null || parentPath.isEmpty() ? key : parentPath + "." + key;

		report.add(Severity.WARNING, node != null ? node.location() : parent.mapping().location(), path,
		           "malformed " + key + " '" + raw + "' - ignored", issueId);
	}

	private static void reportReservedName(NodeReader parent, String key, ConfigReport report) {
		ConfigNode node       = parent.mapping().get(key);
		String     parentPath = parent.mapping().path();
		String     path       = parentPath == null || parentPath.isEmpty() ? key : parentPath + "." + key;

		report.add(Severity.WARNING, node != null ? node.location() : parent.mapping().location(), path,
		           "Named." + key + " uses the reserved keyword 'default' - a weapon skin cannot be named this, "
		           + "since '<name|default>' already treats it as clear-selection - ignored", "skins.reserved_name");
	}

}
