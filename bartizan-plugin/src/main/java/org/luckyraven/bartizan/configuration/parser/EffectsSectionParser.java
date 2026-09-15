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

	/**
	 * The default {@code On_Shoot} muzzle-flash particle, shared by {@link #builtInDefaults()} (the
	 * {@code Default_Effects} fallback) and {@link #lowerLegacySounds} (appended alongside the lowered
	 * {@code Shoot.Sound.*} spec for every shipped gun) so the flash actually plays regardless of which of those
	 * two paths ends up populating a weapon's {@code On_Shoot} hook.
	 */
	private static final EffectSpec MUZZLE_FLASH = buildMuzzleFlash();

	private EffectsSectionParser() {
	}

	private static EffectSpec buildMuzzleFlash() {
		Map<String, String> muzzleFlash = new LinkedHashMap<>();
		muzzleFlash.put("Particle", "SMOKE_NORMAL");
		muzzleFlash.put("Count", "6");
		muzzleFlash.put("Offset", "0.05 0.05 0.05");
		muzzleFlash.put("Speed", "0.02");
		muzzleFlash.put("At", "muzzle");
		return new EffectSpec("particle", muzzleFlash);
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
	 * The four feedback entries {@code settings.yml} ships under {@code Default_Effects:} (weapons-roadmap.md gate
	 * {@code HA}, follow-up review item C; the {@code On_Shoot} muzzle flash added at gate {@code HE} part b),
	 * mirrored here in code for {@code BartizanSettings} to fall back to when the root {@code Default_Effects:}
	 * key is entirely absent from the loaded file — Keystone never merges a missing section into an upgraded
	 * pre-HA server's file, so crit/deny/explosion/muzzle-flash feedback would otherwise go silent rather than
	 * falling back to these.
	 * <p>
	 * The {@link #MUZZLE_FLASH} spec here is the same instance {@link #lowerLegacySounds} appends alongside the
	 * lowered shot-sound spec for every shipped gun — so this fallback (used only when a weapon configures
	 * neither a shot sound nor its own {@code Effects.On_Shoot} list) and the sound-lowering path (used by every
	 * shipped gun) both actually produce the flash, instead of only one of them (gate {@code HE} part b review:
	 * previously only this fallback carried it, so it never fired for any shipped gun).
	 */
	public static EffectsData builtInDefaults() {
		EffectsData data = EffectsData.empty();

		data.put(EffectHook.ON_SHOOT, List.of(MUZZLE_FLASH));

		Map<String, String> critical = new LinkedHashMap<>();
		critical.put("Sound", "ITEM_SHIELD_BREAK");
		critical.put("Volume", "1.0");
		critical.put("Pitch", "1.0");
		critical.put("Target", "source");
		data.put(EffectHook.ON_CRITICAL, List.of(new EffectSpec("sound", critical)));

		Map<String, String> deny = new LinkedHashMap<>();
		deny.put("Text", "&c%deny_reason%");
		deny.put("Target", "source");
		data.put(EffectHook.ON_DENY, List.of(new EffectSpec("action_bar", deny)));

		Map<String, String> explode = new LinkedHashMap<>();
		explode.put("Sound", "ENTITY_GENERIC_EXPLODE");
		explode.put("Volume", "2.0");
		explode.put("Pitch", "1.0");
		explode.put("At", "impact");
		data.put(EffectHook.ON_EXPLODE, List.of(new EffectSpec("sound", explode)));

		return data;
	}

	/**
	 * Lowers the legacy {@code Shoot.Sound.*} / {@code Reload.Sound.*} slots into their {@link EffectHook}
	 * equivalents, but only for a hook the weapon declared no {@code Effects:} list for — a weapon's own list always
	 * wins, no merging.
	 */
	public static void lowerLegacySounds(@Nullable SoundData sounds, EffectsData effects) {
		if (sounds == null) return;

		// Keystone's SoundEffect.playSounds(player, custom, vanilla) played exactly ONE sound: custom when
		// present, else vanilla — never both. At/Target below reproduce which shots broadcast vs play privately
		// pre-HA: shot keeps a broadcast At (the gun path already was); impact broadcasts at the hit; empty-mag,
		// scope and reload start/end had no location and played privately to the shooter only (no At).
		//
		// ON_SHOOT also appends MUZZLE_FLASH: every shipped gun has a Shoot.Sound.Default_Sound, so this lowering
		// — not builtInDefaults()'s Default_Effects fallback — is what actually populates ON_SHOOT for those guns;
		// without appending it here the flash never fires for any of them (gate HE part b review).
		lowerPair(effects, EffectHook.ON_SHOOT, sounds.getShotDefault(), sounds.getShotCustom(), "source", "source",
		         true);
		lowerPair(effects, EffectHook.ON_EMPTY, sounds.getEmptyMagDefault(), sounds.getEmptyMagCustom(), "source",
		         null, false);
		lowerPair(effects, EffectHook.ON_HIT, sounds.getImpactDefault(), sounds.getImpactCustom(), "source",
		         "impact", false);
		lowerPair(effects, EffectHook.ON_SCOPE_IN, sounds.getScopeDefault(), sounds.getScopeCustom(), "source",
		         null, false);
		lowerPair(effects, EffectHook.ON_RELOAD_START, sounds.getReloadDefaultBefore(), sounds.getReloadCustomStart(),
		         "source", null, false);
		lowerPair(effects, EffectHook.ON_RELOAD_END, sounds.getReloadDefaultAfter(), sounds.getReloadCustomEnd(),
		         "source", null, false);
	}

	private static void lowerPair(EffectsData effects, EffectHook hook, @Nullable SoundEffect vanilla,
	                              @Nullable SoundEffect custom, String target, @Nullable String at,
	                              boolean includeMuzzleFlash) {
		if (effects.has(hook)) return;

		SoundEffect chosen = custom != null ? custom : vanilla;
		if (chosen == null) return;

		EffectSpec soundSpec = soundSpec(custom != null ? "custom_sound" : "sound", chosen, target, at);
		effects.put(hook, includeMuzzleFlash ? List.of(soundSpec, MUZZLE_FLASH) : List.of(soundSpec));
	}

	private static EffectSpec soundSpec(String type, SoundEffect sound, String target, @Nullable String at) {
		Map<String, String> args = new LinkedHashMap<>();
		args.put("Sound", sound.sound());
		args.put("Volume", String.valueOf(sound.volume()));
		args.put("Pitch", String.valueOf(sound.pitch()));
		args.put("Target", target);
		if (at != null) args.put("At", at);
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
