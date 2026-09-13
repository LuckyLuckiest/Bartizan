package org.luckyraven.bartizan.configuration.parser;

import org.jetbrains.annotations.Nullable;
import org.luckyraven.keystone.persistence.config.ConfigReport;
import org.luckyraven.keystone.persistence.config.MappingNode;
import org.luckyraven.keystone.persistence.config.NodeReader;
import org.luckyraven.bartizan.api.weapon.dto.ChargeData;
import org.luckyraven.bartizan.api.weapon.dto.EffectHook;
import org.luckyraven.bartizan.api.weapon.dto.EffectSpec;
import org.luckyraven.bartizan.api.weapon.dto.EffectsData;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared yml parser for the {@code Shoot.Charge} block every charge-then-release weapon type uses — biological
 * (gate {@code HB}) and beam (gate {@code HC}). Old files without a {@code Charge:} block keep loading: the legacy
 * {@code Shoot.Charge_Time_Per_Level}/{@code Shoot.Max_Charge_Level} keys are read as aliases (weapons-roadmap.md
 * gate {@code HB} §2.1).
 *
 * <p>Schema:
 * <pre>
 * Shoot:
 *   Charge:
 *     Time_Per_Level: 20
 *     Max_Level: 3
 *     Min_Level_To_Fire: 1
 *     Auto_Fire_At_Max: false
 *   Charge_Feedback:
 *     Sound_Level_Up: BLOCK_NOTE_BLOCK_PLING
 *     Pitch_Per_Level: 0.25
 *     Sound_Full: BLOCK_BEACON_ACTIVATE
 * </pre>
 */
public final class ChargeSectionParser {

	private ChargeSectionParser() {
	}

	/**
	 * Parses {@code Shoot.Charge.*}, falling back to the legacy {@code Shoot.Charge_Time_Per_Level}/
	 * {@code Shoot.Max_Charge_Level} aliases when the {@code Charge:} block is absent. Defaults: 20 ticks per
	 * level, max level 3, min level to fire 1, auto-fire-at-max off.
	 */
	public static ChargeData parse(@Nullable NodeReader shoot, ConfigReport report) {
		if (shoot == null) return new ChargeData(20, 3, 1, false);

		MappingNode chargeSection = shoot.get("Charge").asMapping().orNull();

		if (chargeSection != null) {
			NodeReader charge = NodeReader.of(chargeSection, report);

			int     timePerLevel   = charge.get("Time_Per_Level").asInt().min(1).orDefault(20);
			int     maxLevel       = charge.get("Max_Level").asInt().min(1).orDefault(3);
			int     minLevelToFire = charge.get("Min_Level_To_Fire").asInt().min(1).orDefault(1);
			boolean autoFireAtMax  = charge.get("Auto_Fire_At_Max").asBool().orDefault(false);

			return new ChargeData(timePerLevel, maxLevel, minLevelToFire, autoFireAtMax);
		}

		int timePerLevel = shoot.get("Charge_Time_Per_Level").asInt().min(1).orDefault(20);
		int maxLevel     = shoot.get("Max_Charge_Level").asInt().min(1).orDefault(3);

		return new ChargeData(timePerLevel, maxLevel, 1, false);
	}

	/**
	 * Lowers the optional {@code Shoot.Charge_Feedback} block into {@code On_Charge_Level}/{@code On_Charge_Full}
	 * sound specs, but only for a hook the weapon declared no {@code Effects:} list for — a weapon's own list
	 * always wins, no merging (same rule as {@link EffectsSectionParser#lowerLegacySounds}). {@code Tracer_Color}
	 * is left alone — {@code HB}'s status feedback handles it.
	 */
	public static void lowerChargeFeedback(@Nullable NodeReader shoot, EffectsData effects) {
		if (shoot == null) return;

		NodeReader feedback = shoot.get("Charge_Feedback").asMapping().reader();
		if (feedback == null) return;

		String soundLevelUp  = feedback.get("Sound_Level_Up").asString().orNull();
		double pitchPerLevel = feedback.get("Pitch_Per_Level").asDouble().orDefault(0.0);
		if (soundLevelUp != null && !effects.has(EffectHook.ON_CHARGE_LEVEL)) {
			Map<String, String> args = new LinkedHashMap<>();
			args.put("Sound", soundLevelUp);
			args.put("Target", "source");
			args.put("Pitch_Per_Level", String.valueOf(pitchPerLevel));
			effects.put(EffectHook.ON_CHARGE_LEVEL, List.of(new EffectSpec("sound", args)));
		}

		String soundFull = feedback.get("Sound_Full").asString().orNull();
		if (soundFull != null && !effects.has(EffectHook.ON_CHARGE_FULL)) {
			Map<String, String> args = new LinkedHashMap<>();
			args.put("Sound", soundFull);
			args.put("Target", "source");
			effects.put(EffectHook.ON_CHARGE_FULL, List.of(new EffectSpec("sound", args)));
		}
	}

}
