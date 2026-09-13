package org.luckyraven.bartizan.configuration.parser;

import org.bukkit.Color;
import org.jetbrains.annotations.Nullable;
import org.luckyraven.keystone.persistence.config.ConfigReport;
import org.luckyraven.keystone.persistence.config.NodeReader;
import org.luckyraven.bartizan.api.weapon.dto.EffectHook;
import org.luckyraven.bartizan.api.weapon.dto.EffectSpec;
import org.luckyraven.bartizan.api.weapon.dto.EffectsData;
import org.luckyraven.bartizan.api.weapon.dto.ModifiersData;
import org.luckyraven.bartizan.api.weapon.dto.StatusData;
import org.luckyraven.bartizan.api.weapon.modifiers.action.TracerModifier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parses {@code Shoot.Status}/{@code Shoot.Feedback} for a biological weapon and lowers {@code Feedback.*} into
 * {@code EffectsData} hooks, plus the {@code Charge_Feedback.Tracer_Color} default (weapons-roadmap.md gate
 * {@code HB} §2.1). Every default applies when {@code Status:}/{@code Feedback:} are absent entirely, so old
 * {@code syringe_gun.yml}-shaped files keep loading with victim/shooter feedback the day this gate lands.
 */
public final class StatusSectionParser {

	private static final String DEFAULT_BOSS_BAR_TEXT = "&f%icon% %status% &7Lv %level% · %seconds%s";

	private StatusSectionParser() {
	}

	/**
	 * @param weaponDisplayName default {@code Status.Name} when the weapon declares none.
	 * @param chargeMaxLevel default {@code Status.Max_Level} — mirrors {@code Shoot.Charge.Max_Level}.
	 */
	public static StatusData parse(@Nullable NodeReader shoot, ConfigReport report, String weaponDisplayName,
	                               int chargeMaxLevel) {
		NodeReader status         = section(shoot, "Status");
		NodeReader feedback       = section(shoot, "Feedback");
		NodeReader feedbackVictim = section(feedback, "Victim");
		NodeReader feedbackShooter = section(feedback, "Shooter");

		String name = status != null ? status.get("Name").asString().orDefault(weaponDisplayName) : weaponDisplayName;
		String icon = status != null ? status.get("Icon").asString().orDefault("") : "";
		int durationPerLevel = status != null ? status.get("Duration_Per_Level").asInt().min(1).orDefault(200) : 200;
		StatusData.Stacking stacking = parseStacking(status);
		int maxLevel = status != null ? status.get("Max_Level").asInt().min(1).orDefault(chargeMaxLevel) : chargeMaxLevel;
		int killCreditWindow = status != null ? status.get("Kill_Credit_Window").asInt().min(0).orDefault(200) : 200;

		StatusData.ContagionData contagion = parseContagion(status);
		StatusData.CureData      cure      = parseCure(status);
		StatusData.BossBarData   bossBar   = parseBossBar(feedbackVictim);

		String ambientParticle = feedbackVictim != null ? feedbackVictim.get("Ambient_Particle").asString().orNull() : null;
		String ambientColor    = feedbackVictim != null ? feedbackVictim.get("Ambient_Color").asString().orNull() : null;
		int ambientInterval = feedbackVictim != null ? feedbackVictim.get("Ambient_Interval").asInt().min(1).orDefault(20)
		                                             : 20;
		String messageSpread = feedbackShooter != null ? feedbackShooter.get("Message_Spread").asString().orNull() : null;

		return new StatusData(name, icon, durationPerLevel, stacking, maxLevel, killCreditWindow, contagion, cure,
		                      bossBar, ambientParticle, ambientColor, ambientInterval, messageSpread);
	}

	/**
	 * Lowers {@code Shoot.Feedback.Victim/Shooter} into {@code On_Status_Apply}/{@code On_Status_Expire}, but only
	 * for a hook the weapon declared no {@code Effects:} list for — same no-merge rule as
	 * {@link EffectsSectionParser#lowerLegacySounds}/{@link ChargeSectionParser#lowerChargeFeedback}. With no
	 * {@code Feedback:} block at all, a default hit-marker sound is still lowered into {@code On_Status_Apply} — the
	 * roadmap's "boss bar and hit marker only" default.
	 */
	public static void lowerFeedback(@Nullable NodeReader shoot, EffectsData effects, String statusName, String icon) {
		NodeReader feedback        = section(shoot, "Feedback");
		NodeReader feedbackVictim  = section(feedback, "Victim");
		NodeReader feedbackShooter = section(feedback, "Shooter");

		List<EffectSpec> applySpecs  = new ArrayList<>();
		List<EffectSpec> expireSpecs = new ArrayList<>();

		if (feedbackVictim != null) {
			String title    = feedbackVictim.get("Title").asString().orNull();
			String subtitle = feedbackVictim.get("Subtitle").asString().orNull();
			if (title != null || subtitle != null) {
				Map<String, String> args = new LinkedHashMap<>();
				if (title != null) args.put("Title", substitute(title, statusName, icon));
				if (subtitle != null) args.put("Subtitle", substitute(subtitle, statusName, icon));
				args.put("Target", "victim");
				applySpecs.add(new EffectSpec("title", args));
			}

			String soundApply = feedbackVictim.get("Sound_Apply").asString().orNull();
			if (soundApply != null) applySpecs.add(soundSpec(soundApply, "victim"));

			String soundExpire = feedbackVictim.get("Sound_Expire").asString().orNull();
			if (soundExpire != null) expireSpecs.add(soundSpec(soundExpire, "victim"));

			String messageExpire = feedbackVictim.get("Message_Expire").asString().orNull();
			if (messageExpire != null) {
				Map<String, String> args = new LinkedHashMap<>();
				args.put("Text", substitute(messageExpire, statusName, icon));
				args.put("Target", "victim");
				expireSpecs.add(new EffectSpec("message", args));
			}
		}

		if (feedbackShooter != null) {
			String hitMarker = feedbackShooter.get("Hit_Marker_Sound").asString().orNull();
			if (hitMarker != null) applySpecs.add(soundSpec(hitMarker, "source"));

			String actionBar = feedbackShooter.get("Action_Bar").asString().orNull();
			if (actionBar != null) {
				Map<String, String> args = new LinkedHashMap<>();
				args.put("Text", substitute(actionBar, statusName, icon));
				args.put("Target", "source");
				applySpecs.add(new EffectSpec("action_bar", args));
			}
			// Message_Spread is read by parse() straight onto StatusData — the service formats/sends it, not a hook.
		}

		if (feedback == null) applySpecs.add(soundSpec("ENTITY_EXPERIENCE_ORB_PICKUP", "source"));

		if (!applySpecs.isEmpty() && !effects.has(EffectHook.ON_STATUS_APPLY)) {
			effects.put(EffectHook.ON_STATUS_APPLY, applySpecs);
		}
		if (!expireSpecs.isEmpty() && !effects.has(EffectHook.ON_STATUS_EXPIRE)) {
			effects.put(EffectHook.ON_STATUS_EXPIRE, expireSpecs);
		}
	}

	/**
	 * {@code Shoot.Charge_Feedback.Tracer_Color} defaults {@code Modifiers.Tracer} when the weapon declared no
	 * explicit tracer of its own — the raytracer already draws tracer modifiers for any weapon, so nothing else
	 * needs to change for the ray to become visible.
	 */
	public static void lowerTracer(@Nullable NodeReader shoot, ModifiersData modifiers) {
		if (shoot == null || modifiers.hasTracer()) return;

		NodeReader chargeFeedback = section(shoot, "Charge_Feedback");
		String     hex            = chargeFeedback != null ? chargeFeedback.get("Tracer_Color").asString().orNull() : null;
		if (hex == null) return;

		String cleaned = hex.replace("#", "").trim();
		if (cleaned.length() != 6) return;

		try {
			Color color = Color.fromRGB(Integer.parseInt(cleaned.substring(0, 2), 16),
			                            Integer.parseInt(cleaned.substring(2, 4), 16),
			                            Integer.parseInt(cleaned.substring(4, 6), 16));
			modifiers.setTracer(new TracerModifier(color, false, 0.5f));
		} catch (NumberFormatException ignored) {
		}
	}

	private static String substitute(String text, String statusName, String icon) {
		return text.replace("%status%", statusName)
		           .replace("%icon%", icon)
		           .replace("%shooter%", "%player%");
	}

	private static EffectSpec soundSpec(String sound, String target) {
		Map<String, String> args = new LinkedHashMap<>();
		args.put("Sound", sound);
		args.put("Target", target);
		return new EffectSpec("sound", args);
	}

	private static StatusData.Stacking parseStacking(@Nullable NodeReader status) {
		String raw = status != null ? status.get("Stacking").asString().orDefault("refresh") : "refresh";
		try {
			return StatusData.Stacking.valueOf(raw.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException exception) {
			return StatusData.Stacking.REFRESH;
		}
	}

	@Nullable
	private static StatusData.ContagionData parseContagion(@Nullable NodeReader status) {
		NodeReader contagion = section(status, "Contagion");
		if (contagion == null) return null;

		double radius    = contagion.get("Radius").asDouble().min(0).orDefault(3.0);
		double chance    = contagion.get("Chance").asDouble().min(0).max(1).orDefault(0.15);
		int    interval  = contagion.get("Interval").asInt().min(1).orDefault(40);
		int    levelDrop = contagion.get("Level_Drop").asInt().min(0).orDefault(1);
		return new StatusData.ContagionData(radius, chance, interval, levelDrop);
	}

	private static StatusData.CureData parseCure(@Nullable NodeReader status) {
		NodeReader cure = section(status, "Cure");
		if (cure == null) return new StatusData.CureData(List.of(), null);

		List<String> items         = cure.get("Items").asList().ofStrings().orEmpty();
		String       wearableTrait = cure.get("Wearable_Trait").asString().orNull();
		return new StatusData.CureData(items, wearableTrait);
	}

	private static StatusData.BossBarData parseBossBar(@Nullable NodeReader feedbackVictim) {
		NodeReader bossBar = section(feedbackVictim, "Boss_Bar");
		String     text    = bossBar != null ? bossBar.get("Text").asString().orDefault(DEFAULT_BOSS_BAR_TEXT)
		                                     : DEFAULT_BOSS_BAR_TEXT;
		String color = bossBar != null ? bossBar.get("Color").asString().orDefault("WHITE") : "WHITE";
		String style = bossBar != null ? bossBar.get("Style").asString().orDefault("SOLID") : "SOLID";
		return new StatusData.BossBarData(text, color, style);
	}

	@Nullable
	private static NodeReader section(@Nullable NodeReader parent, String key) {
		return parent != null ? parent.get(key).asMapping().reader() : null;
	}

}
