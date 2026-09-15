package org.luckyraven.bartizan.configuration.parser;

import lombok.CustomLog;
import org.bukkit.Color;
import org.bukkit.Material;
import org.luckyraven.keystone.persistence.config.ConfigReport;
import org.luckyraven.keystone.persistence.config.MappingNode;
import org.luckyraven.keystone.persistence.config.NodeReader;
import org.luckyraven.keystone.persistence.config.Severity;
import org.luckyraven.bartizan.api.weapon.BeamWeapon;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.configuration.WeaponAddon;
import org.luckyraven.bartizan.api.weapon.dto.BeamData;
import org.luckyraven.bartizan.api.weapon.dto.ModifiersData;
import org.luckyraven.bartizan.api.weapon.modifiers.BreakMode;
import org.luckyraven.bartizan.api.weapon.modifiers.action.*;
import org.luckyraven.bartizan.util.BlockGroupResolver;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Parses the {@code Modifiers:} section of a weapon YAML and attaches a {@link ModifiersData} to the given weapon.
 * Mirrors the static-utility shape of {@link SelectiveFireSectionParser} — the call site in {@link WeaponAddon} simply
 * delegates to {@link #apply(NodeReader, Weapon, ConfigReport)}.
 *
 * <p>Each modifier family uses its own custom DSL:
 * <ul>
 *   <li>{@code Break_Blocks}: list of {@code <block-group>-<hits>[-<mode>]} strings.</li>
 *   <li>{@code Penetration}: single {@code <block-count>-<damage-drop>-<knockback>} string.</li>
 *   <li>{@code Ricochet}: list of {@code <max-bounces>-<mat[,mat2,…]>-<damage-mult>} strings.</li>
 *   <li>{@code Tracer}: {@code <RRGGBB>-<fullLine>-<thickness>}.</li>
 *   <li>{@code Armor_Piercing}: a single double.</li>
 *   <li>{@code Flat_Damage}: a single double.</li>
 * </ul>
 */
@CustomLog
public final class ModifiersSectionParser {

	private ModifiersSectionParser() {
	}

	public static void apply(NodeReader root, Weapon weapon, ConfigReport report) {
		MappingNode modifiersSection = root.get("Modifiers").asMapping().orNull();
		if (modifiersSection == null) return;

		NodeReader modifiers = NodeReader.of(modifiersSection, report);

		weapon.setModifiersData(new ModifiersData());

		applyBreakBlocks(modifiers, weapon);
		applyPenetration(modifiers, weapon, report);
		applyRicochet(modifiers, weapon);
		applyTracer(modifiers, weapon);
		applyArmorPiercing(modifiers, weapon);
		applyFlatDamage(modifiers, weapon);
	}

	private static void applyBreakBlocks(NodeReader modifiers, Weapon weapon) {
		for (String entry : modifiers.get("Break_Blocks").asList().ofStrings().orEmpty()) {
			String[] parts = entry.split("-");
			if (parts.length != 2 && parts.length != 3) continue;
			try {
				Set<Material> materials = BlockGroupResolver.resolve(parts[0].trim());
				if (materials.isEmpty()) continue;

				int       hits = Integer.parseInt(parts[1].trim());
				BreakMode mode = BreakMode.RESTORE;
				if (parts.length == 3) {
					String token = parts[2].trim().toUpperCase(Locale.ROOT);
					try {
						mode = BreakMode.valueOf(token);
					} catch (IllegalArgumentException ex) {
						log.warn("Unknown break mode '{}' in weapon '{}', defaulting to RESTORE", parts[2],
						         weapon.getName());
					}
				}

				weapon.getModifiersData().addBreakBlock(new BlockBreakModifier(materials, hits, mode));
			} catch (NumberFormatException ignored) { }
		}
	}

	/**
	 * {@code Penetration} plus its {@code Pierce_Entities} sugar: when no {@code Penetration} string is
	 * configured, {@code Pierce_Entities: <n>} sets an entities-only {@code PenetrationModifier(0, n, 0.0)}.
	 * Configuring both is a {@link Severity#WARNING} — {@code Penetration} always wins.
	 */
	private static void applyPenetration(NodeReader modifiers, Weapon weapon, ConfigReport report) {
		String penetrationString = modifiers.get("Penetration").asString().orNull();
		int    pierceEntities    = modifiers.get("Pierce_Entities").asInt().min(0).orDefault(0);

		if (penetrationString != null) {
			String[] parts = penetrationString.split("-");
			if (parts.length == 3) {
				try {
					weapon.getModifiersData()
					      .setPenetration(new PenetrationModifier(Integer.parseInt(parts[0].trim()),
					                                              Integer.parseInt(parts[1].trim()),
					                                              Double.parseDouble(parts[2].trim())));
				} catch (NumberFormatException ignored) { }
			}

			if (pierceEntities > 0) {
				MappingNode mapping = modifiers.mapping();
				report.add(Severity.WARNING, mapping.location(), mapping.path(),
				           "Modifiers.Penetration and Pierce_Entities both configured - Penetration takes "
				           + "precedence", "modifiers.pierce_entities_and_penetration");
			}
			warnIfBeamPierceOverridden(modifiers, weapon, report);
			return;
		}

		if (pierceEntities > 0) {
			weapon.getModifiersData().setPenetration(new PenetrationModifier(0, pierceEntities, 0.0));
			warnIfBeamPierceOverridden(modifiers, weapon, report);
		}
	}

	/**
	 * {@code ModifiersSectionParser.apply} runs before {@code BeamWeaponParser.lowerPierce}, so a
	 * {@code Modifiers.Penetration}/{@code Pierce_Entities} config on a BEAM weapon silently wins over
	 * {@code Shoot.Beam.Pierce} — {@code lowerPierce} sees {@code hasPenetration()} already true and skips.
	 * Same warning family as the {@code Penetration}/{@code Pierce_Entities} collision above, so the file author
	 * finds out instead of quietly losing the beam's own pierce config.
	 */
	private static void warnIfBeamPierceOverridden(NodeReader modifiers, Weapon weapon, ConfigReport report) {
		if (!(weapon instanceof BeamWeapon beamWeapon)) return;

		BeamData.PierceData pierce = beamWeapon.getBeam().getPierce();
		boolean beamPierceConfigured = pierce.entities() != -1 || pierce.blocks() != 0
		                                || pierce.damageMultiplierPerTarget() != 0.85;
		if (!beamPierceConfigured) return;

		MappingNode mapping = modifiers.mapping();
		report.add(Severity.WARNING, mapping.location(), mapping.path(),
		           "Modifiers.Penetration/Pierce_Entities and Shoot.Beam.Pierce both configured - Modifiers takes "
		           + "precedence", "modifiers.pierce_entities_and_beam_pierce");
	}

	private static void applyRicochet(NodeReader modifiers, Weapon weapon) {
		for (String entry : modifiers.get("Ricochet").asList().ofStrings().orEmpty()) {
			String[] parts = entry.split("-");
			if (parts.length != 3) continue;

			try {
				Set<Material> bounceOffBlocks = new HashSet<>();
				for (String matName : parts[1].trim().split(","))
					bounceOffBlocks.addAll(BlockGroupResolver.resolve(matName.trim()));
				weapon.getModifiersData()
				      .addRicochet(new RicochetModifier(Integer.parseInt(parts[0].trim()), bounceOffBlocks,
				                                        Double.parseDouble(parts[2].trim())));
			} catch (NumberFormatException ignored) { }
		}
	}

	private static void applyTracer(NodeReader modifiers, Weapon weapon) {
		String tracerString = modifiers.get("Tracer").asString().orNull();
		if (tracerString == null) return;

		String[] parts = tracerString.split("-");
		if (parts.length != 3) return;

		try {
			String colorHex = parts[0].trim();
			Color color = Color.fromRGB(Integer.parseInt(colorHex.substring(0, 2), 16),
			                            Integer.parseInt(colorHex.substring(2, 4), 16),
			                            Integer.parseInt(colorHex.substring(4, 6), 16));
			weapon.getModifiersData()
			      .setTracer(new TracerModifier(color, Boolean.parseBoolean(parts[1].trim()),
			                                    Float.parseFloat(parts[2].trim())));
		} catch (NumberFormatException | IndexOutOfBoundsException ignored) { }
	}

	private static void applyArmorPiercing(NodeReader modifiers, Weapon weapon) {
		String armorPiercingString = modifiers.get("Armor_Piercing").asString().orNull();
		if (armorPiercingString == null) return;

		try {
			weapon.getModifiersData()
			      .setArmorPiercing(new ArmorPiercingModifier(Double.parseDouble(armorPiercingString.trim())));
		} catch (NumberFormatException ignored) { }
	}

	private static void applyFlatDamage(NodeReader modifiers, Weapon weapon) {
		String flatDamageString = modifiers.get("Flat_Damage").asString().orNull();
		if (flatDamageString == null) return;

		try {
			double bonus = Double.parseDouble(flatDamageString.trim());
			if (bonus > 0) weapon.getModifiersData().setFlatDamage(new FlatDamageModifier(bonus));
		} catch (NumberFormatException ignored) { }
	}

}
