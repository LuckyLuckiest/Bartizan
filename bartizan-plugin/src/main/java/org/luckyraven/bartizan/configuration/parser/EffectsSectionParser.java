package org.luckyraven.bartizan.configuration.parser;

import org.jetbrains.annotations.Nullable;
import org.luckyraven.keystone.persistence.config.ConfigNode;
import org.luckyraven.keystone.persistence.config.ConfigReport;
import org.luckyraven.keystone.persistence.config.MappingNode;
import org.luckyraven.keystone.persistence.config.NodeReader;
import org.luckyraven.keystone.persistence.config.ScalarNode;
import org.luckyraven.keystone.persistence.config.SequenceNode;
import org.luckyraven.keystone.persistence.config.Severity;
import org.luckyraven.keystone.sound.SoundEffect;
import org.luckyraven.bartizan.api.weapon.dto.EffectHook;
import org.luckyraven.bartizan.api.weapon.dto.EffectSpec;
import org.luckyraven.bartizan.api.weapon.dto.EffectsData;
import org.luckyraven.bartizan.api.weapon.dto.SoundData;
import org.luckyraven.bartizan.effect.EffectRunner;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Parses a weapon's (or {@code settings.yml}'s {@code Default_Effects:}) {@code Effects:} section into an
 * {@link EffectsData} (weapons-roadmap.md gate {@code HA}, §1). Each child key is a hook name; an unknown hook, a
 * missing/unknown entry {@code Type}, is a {@link Severity#WARNING} — never an error — so an old or slightly
 * misspelled file keeps loading with that one entry skipped.
 */
public final class EffectsSectionParser {

	private EffectsSectionParser() {
	}

	/**
	 * @param effectsSection the {@code Effects:} mapping reader, or {@code null} when the section is absent.
	 */
	public static EffectsData parse(@Nullable NodeReader effectsSection, ConfigReport report) {
		EffectsData data = EffectsData.empty();
		if (effectsSection == null) return data;

		for (String key : effectsSection.keys()) {
			Optional<EffectHook>  hookOptional = EffectHook.fromKey(key);
			NodeReader.NodeAccess access       = effectsSection.get(key);

			if (hookOptional.isEmpty()) {
				ConfigNode node = access.node();
				report.add(Severity.WARNING, node != null ? node.location() : effectsSection.mapping().location(),
				           childPath(effectsSection, key), "unknown effect hook '" + key + "'",
				           "effects.unknown_hook");
				continue;
			}

			List<MappingNode> entries = access.asList().ofMappings().orEmpty();
			List<EffectSpec>  specs   = new ArrayList<>(entries.size());

			for (MappingNode entryMapping : entries) {
				EffectSpec spec = parseEntry(entryMapping, report);
				if (spec != null) specs.add(spec);
			}

			if (!specs.isEmpty()) data.put(hookOptional.get(), specs);
		}

		return data;
	}

	/**
	 * Lowers the legacy {@code Shoot.Sound.*} / {@code Reload.Sound.*} slots into their {@link EffectHook}
	 * equivalents, but only for a hook the weapon declared no {@code Effects:} list for — a weapon's own list always
	 * wins, no merging.
	 */
	public static void lowerLegacySounds(@Nullable SoundData sounds, EffectsData effects) {
		if (sounds == null) return;

		lowerPair(effects, EffectHook.ON_SHOOT, sounds.getShotDefault(), sounds.getShotCustom(), "source", "source");
		lowerPair(effects, EffectHook.ON_EMPTY, sounds.getEmptyMagDefault(), sounds.getEmptyMagCustom(), "source",
		         "source");
		lowerPair(effects, EffectHook.ON_HIT, sounds.getImpactDefault(), sounds.getImpactCustom(), "source",
		         "impact");
		lowerPair(effects, EffectHook.ON_SCOPE_IN, sounds.getScopeDefault(), sounds.getScopeCustom(), "source",
		         "source");
		lowerPair(effects, EffectHook.ON_RELOAD_START, sounds.getReloadDefaultBefore(), sounds.getReloadCustomStart(),
		         "source", "source");
		lowerPair(effects, EffectHook.ON_RELOAD_END, sounds.getReloadDefaultAfter(), sounds.getReloadCustomEnd(),
		         "source", "source");
	}

	private static void lowerPair(EffectsData effects, EffectHook hook, @Nullable SoundEffect vanilla,
	                              @Nullable SoundEffect custom, String target, String at) {
		if (effects.has(hook)) return;
		if (vanilla == null && custom == null) return;

		List<EffectSpec> specs = new ArrayList<>(2);
		if (vanilla != null) specs.add(soundSpec("sound", vanilla, target, at));
		if (custom != null) specs.add(soundSpec("custom_sound", custom, target, at));

		effects.put(hook, specs);
	}

	private static EffectSpec soundSpec(String type, SoundEffect sound, String target, String at) {
		Map<String, String> args = new LinkedHashMap<>();
		args.put("Sound", sound.sound());
		args.put("Volume", String.valueOf(sound.volume()));
		args.put("Pitch", String.valueOf(sound.pitch()));
		args.put("Target", target);
		args.put("At", at);
		return new EffectSpec(type, args);
	}

	@Nullable
	private static EffectSpec parseEntry(MappingNode entryMapping, ConfigReport report) {
		ConfigNode typeNode  = entryMapping.get("Type");
		String     typeValue = typeNode instanceof ScalarNode scalar ? scalar.value() : null;

		if (typeValue == null || typeValue.isBlank()) {
			report.add(Severity.WARNING, entryMapping.location(), entryMapping.path(),
			           "effect entry missing 'Type'", "effects.missing_type");
			return null;
		}

		String normalizedType = typeValue.trim().toLowerCase(Locale.ROOT);
		if (!EffectRunner.TYPES.contains(normalizedType)) {
			report.add(Severity.WARNING, entryMapping.location(), entryMapping.path(),
			           "unknown effect type '" + typeValue + "'", "effects.unknown_type");
			return null;
		}

		Map<String, String> args = new LinkedHashMap<>();
		for (Map.Entry<String, ConfigNode> entry : entryMapping.entries().entrySet()) {
			if (entry.getKey().equalsIgnoreCase("Type")) continue;

			String value = nodeToArgString(entry.getValue());
			if (value != null) args.put(entry.getKey(), value);
		}

		return new EffectSpec(normalizedType, args);
	}

	/**
	 * Flattens a scalar node to its raw text; a sequence node to its elements joined with single spaces. Any other
	 * node shape (a nested mapping) is skipped.
	 */
	@Nullable
	private static String nodeToArgString(ConfigNode node) {
		if (node instanceof ScalarNode scalar) return scalar.value();
		if (node instanceof SequenceNode seq) {
			return seq.items().stream()
			          .map(EffectsSectionParser::nodeToArgString)
			          .filter(Objects::nonNull)
			          .collect(Collectors.joining(" "));
		}
		return null;
	}

	private static String childPath(NodeReader parent, String key) {
		String parentPath = parent.mapping().path();
		return parentPath == null || parentPath.isEmpty() ? key : parentPath + "." + key;
	}

}
