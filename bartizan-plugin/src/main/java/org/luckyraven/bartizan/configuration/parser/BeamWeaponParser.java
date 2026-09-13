package org.luckyraven.bartizan.configuration.parser;

import org.bukkit.configuration.InvalidConfigurationException;
import org.jetbrains.annotations.Nullable;
import org.luckyraven.keystone.persistence.config.ConfigReport;
import org.luckyraven.keystone.persistence.config.NodeReader;
import org.luckyraven.keystone.persistence.config.Severity;
import org.luckyraven.bartizan.ammo.AmmunitionManager;
import org.luckyraven.bartizan.api.weapon.BeamWeapon;
import org.luckyraven.bartizan.api.weapon.SelectiveFire;
import org.luckyraven.bartizan.api.weapon.dto.AmmunitionData;
import org.luckyraven.bartizan.api.weapon.dto.BeamData;
import org.luckyraven.bartizan.api.weapon.dto.ChargeData;
import org.luckyraven.bartizan.api.weapon.dto.EffectHook;
import org.luckyraven.bartizan.api.weapon.dto.EffectSpec;
import org.luckyraven.bartizan.api.weapon.dto.EffectsData;
import org.luckyraven.bartizan.api.weapon.dto.ModifiersData;
import org.luckyraven.bartizan.api.weapon.dto.ReloadData;
import org.luckyraven.bartizan.api.weapon.modifiers.action.PenetrationModifier;
import org.luckyraven.bartizan.configuration.parser.AmmunitionSectionParser.ParsedAmmo;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses the {@code Shoot:} section of a BEAM weapon YAML and constructs a {@link BeamWeapon} (weapons-roadmap.md
 * gate {@code HC}, §3.1). Only {@code Mode: burst} is implemented — any other value is a {@link ConfigReport}
 * warning and treated as burst (sustained fire is gate {@code HN}).
 */
public class BeamWeaponParser {

	private final AmmunitionSectionParser ammoParser;

	public BeamWeaponParser(AmmunitionManager ammunitionManager) {
		this.ammoParser = new AmmunitionSectionParser(ammunitionManager);
	}

	/**
	 * If the weapon declared no {@code Modifiers.Penetration} of its own, lowers {@code Shoot.Beam.Pierce} into one
	 * — {@code Entities: -1} becomes {@link Integer#MAX_VALUE} (unlimited) and {@code Damage_Multiplier_Per_Target}
	 * becomes the modifier's {@code damageReduction} (its complement, {@code 1 - multiplier}). An explicit
	 * {@code Modifiers.Penetration} block always wins — no merging.
	 */
	public static void lowerPierce(BeamWeapon weapon) {
		ModifiersData modifiers = weapon.getModifiersData();
		if (modifiers == null) {
			modifiers = new ModifiersData();
			weapon.setModifiersData(modifiers);
		}
		if (modifiers.hasPenetration()) return;

		BeamData.PierceData pierce   = weapon.getBeam().getPierce();
		int                 entities = pierce.entities() < 0 ? Integer.MAX_VALUE : pierce.entities();

		modifiers.setPenetration(
				new PenetrationModifier(pierce.blocks(), entities, 1.0 - pierce.damageMultiplierPerTarget()));
	}

	/**
	 * Lowers {@code Shoot.Beam.Impact.Sound}/{@code .Particle} into an {@code On_Beam_Fire} sound + particle spec
	 * pair, but only when the weapon declared no {@code Effects:} list of its own for that hook (same no-merge rule
	 * as {@link ChargeSectionParser#lowerChargeFeedback}). Applies the roadmap defaults even when {@code Impact:}
	 * (or {@code Beam:} itself) is entirely absent.
	 */
	public static void lowerImpactEffects(@Nullable NodeReader shoot, EffectsData effects) {
		if (shoot == null || effects.has(EffectHook.ON_BEAM_FIRE)) return;

		ConfigReport report = shoot.report();
		NodeReader   beam   = NodeReader.of(shoot.get("Beam").asMapping().orEmpty(), report);
		NodeReader   impact = NodeReader.of(beam.get("Impact").asMapping().orEmpty(), report);

		String sound    = impact.get("Sound").asString().orDefault("ENTITY_LIGHTNING_BOLT_IMPACT");
		String particle = impact.get("Particle").asString().orDefault("FLASH");

		Map<String, String> soundArgs = new LinkedHashMap<>();
		soundArgs.put("Sound", sound);
		soundArgs.put("At", "impact");

		Map<String, String> particleArgs = new LinkedHashMap<>();
		particleArgs.put("Particle", particle);
		particleArgs.put("At", "impact");
		particleArgs.put("Count", "1");

		effects.put(EffectHook.ON_BEAM_FIRE,
		           List.of(new EffectSpec("sound", soundArgs), new EffectSpec("particle", particleArgs)));
	}

	/**
	 * Parses {@code Shoot.Beam.*} into a {@link BeamData}, applying the roadmap defaults for any absent sub-section
	 * (weapons-roadmap.md gate {@code HC}, §3.1). Public so {@code BeamWeaponParserTest} can exercise it directly
	 * without an {@link AmmunitionManager}.
	 */
	public static BeamData parseBeam(NodeReader shoot, ConfigReport report) {
		NodeReader beam = NodeReader.of(shoot.get("Beam").asMapping().orEmpty(), report);

		String mode = beam.get("Mode").asString().orDefault("burst");
		if (!"burst".equalsIgnoreCase(mode.trim())) {
			report.add(Severity.WARNING, beam.mapping().location(), beam.mapping().path() + ".Mode",
			           "beam Mode '" + mode + "' is not supported yet — only 'burst' is implemented (sustained "
			           + "fire is gate HN); treating as burst", "beam.unsupported_mode");
		}

		double range       = beam.get("Range").asDouble().min(0).orDefault(60.0);
		double width        = beam.get("Width").asDouble().min(0).orDefault(0.6);
		int    ammoPerLevel = beam.get("Ammo_Per_Level").asInt().min(1).orDefault(1);

		NodeReader pierce           = NodeReader.of(beam.get("Pierce").asMapping().orEmpty(), report);
		int        pierceEntities   = pierce.get("Entities").asInt().orDefault(-1);
		int        pierceBlocks     = pierce.get("Blocks").asInt().min(0).orDefault(0);
		double     pierceMultiplier =
				pierce.get("Damage_Multiplier_Per_Target").asDouble().min(0).max(1).orDefault(0.85);

		NodeReader damage    = NodeReader.of(beam.get("Damage").asMapping().orEmpty(), report);
		double     baseDmg   = damage.get("Base").asDouble().min(0).orDefault(6.0);
		double     perLevel  = damage.get("Per_Level").asDouble().min(0).orDefault(5.0);
		double     head      = damage.get("Head").asDouble().min(0).orDefault(4.0);
		double     knockback = damage.get("Knockback").asDouble().min(0).orDefault(0.8);
		int        fireTicks = damage.get("Fire_Ticks").asInt().min(0).orDefault(0);

		NodeReader preview        = NodeReader.of(beam.get("Preview").asMapping().orEmpty(), report);
		String     previewParticle = preview.get("Particle").asString().orDefault("END_ROD");
		double     lengthPerLevel  = preview.get("Length_Per_Level").asDouble().min(0).orDefault(1.5);
		int        interval        = preview.get("Interval").asInt().min(1).orDefault(2);
		boolean    guideLine       = preview.get("Guide_Line").asBool().orDefault(false);

		NodeReader render       = NodeReader.of(beam.get("Render").asMapping().orEmpty(), report);
		String     coreParticle = render.get("Core_Particle").asString().orDefault("DUST");
		String     coreColor    = render.get("Core_Color").asString().orDefault("#66CCFF");
		String     glowParticle = render.get("Glow_Particle").asString().orDefault("END_ROD");
		double     thickness    = render.get("Thickness").asDouble().min(0).orDefault(0.25);
		// Floor of 0.5: a full-range beam at a smaller step multiplies spawnParticle calls per tick into the
		// hundreds (weapons-roadmap.md gate HC review) — values below 0.5 fail config.range and fall back to the
		// default instead of being honoured.
		double     step         = render.get("Step").asDouble().min(0.5).orDefault(0.25);
		int        duration     = render.get("Duration").asInt().min(1).orDefault(8);

		NodeReader impact       = NodeReader.of(beam.get("Impact").asMapping().orEmpty(), report);
		boolean    scorchBlocks = impact.get("Scorch_Blocks").asBool().orDefault(false);

		return new BeamData(range, width, ammoPerLevel,
		                    new BeamData.PierceData(pierceEntities, pierceBlocks, pierceMultiplier),
		                    new BeamData.BeamDamageData(baseDmg, perLevel, head, knockback, fireTicks),
		                    new BeamData.PreviewData(previewParticle, lengthPerLevel, interval, guideLine),
		                    new BeamData.RenderData(coreParticle, coreColor, glowParticle, thickness, step, duration),
		                    scorchBlocks);
	}

	public BeamWeapon parse(NodeReader root, NodeReader shoot, ConfigReport report, WeaponBaseData base)
			throws InvalidConfigurationException {
		if (shoot == null) {
			throw new InvalidConfigurationException("Shoot section not found for beam weapon");
		}

		ChargeData charge = ChargeSectionParser.parse(shoot, report);
		BeamData   beam   = parseBeam(shoot, report);

		ParsedAmmo     parsed         = ammoParser.parse(root, report);
		ReloadData     reloadData     = parsed != null ? parsed.reload() : null;
		AmmunitionData ammunitionData = parsed != null ? parsed.ammo() : null;

		BeamWeapon weapon = new BeamWeapon(null, base.fileName(), base.displayName(), base.category(),
		                                   base.material(), base.customModelData(), base.durability(),
		                                   base.lore(), base.dropHologram(), base.deathMessages(),
		                                   beam, charge, reloadData, ammunitionData);

		SelectiveFireSectionParser.ParsedSelectiveFire parsedSelectiveFire =
				SelectiveFireSectionParser.parse(shoot, report, base.fileName());
		if (parsedSelectiveFire != null) {
			weapon.setCurrentSelectiveFire(parsedSelectiveFire.current());
			weapon.setAllowedSelectiveFires(parsedSelectiveFire.allowed());
		} else {
			weapon.setCurrentSelectiveFire(SelectiveFire.SINGLE);
			weapon.setAllowedSelectiveFires(EnumSet.of(SelectiveFire.SINGLE));
		}

		return weapon;
	}

}
