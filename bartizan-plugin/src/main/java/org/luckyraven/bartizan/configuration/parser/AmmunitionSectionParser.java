package org.luckyraven.bartizan.configuration.parser;

import org.jetbrains.annotations.Nullable;
import org.luckyraven.keystone.persistence.config.ConfigReport;
import org.luckyraven.keystone.persistence.config.MappingNode;
import org.luckyraven.keystone.persistence.config.NodeReader;
import org.luckyraven.keystone.persistence.config.Severity;
import org.luckyraven.bartizan.api.ammo.Ammunition;
import org.luckyraven.bartizan.ammo.AmmunitionManager;
import org.luckyraven.bartizan.api.weapon.dto.AmmunitionData;
import org.luckyraven.bartizan.api.weapon.dto.ReloadData;
import org.luckyraven.bartizan.api.weapon.dto.ReloadStagesData;
import org.luckyraven.bartizan.api.weapon.reload.ReloadType;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses the {@code Ammunition:} and optional {@code Reload:} sections from a weapon YAML file. Returns a
 * {@link ParsedAmmo} record containing both the ammo DTO and the reload DTO, or {@code null} if the {@code
 * Ammunition} section is absent entirely (no magazine at all — melee/throwable etc., or a WM import still missing
 * the block). An {@code Ammunition:} section that IS present but names an unregistered ammo id (single
 * {@code Ammo_Type} or inside a {@code Types:} list), or sets both {@code Ammo_Type} and {@code Types}, is a config
 * error: it also returns {@code null}, but additionally records a {@link Severity#ERROR} on {@code report} —
 * {@code WeaponAddon.registerWeapon} turns that into a load failure for every weapon category, not just guns.
 */
public class AmmunitionSectionParser {

	private final AmmunitionManager ammunitionManager;

	public AmmunitionSectionParser(AmmunitionManager ammunitionManager) {
		this.ammunitionManager = ammunitionManager;
	}

	@Nullable
	public ParsedAmmo parse(NodeReader root, ConfigReport report) {
		MappingNode ammoSection = root.get("Ammunition").asMapping().orNull();
		if (ammoSection == null) return null;

		NodeReader ammo = NodeReader.of(ammoSection, report);

		// Read all admin-facing ammo/reload keys unconditionally, BEFORE deciding whether the ammo type(s)
		// resolve. Bailing early on an unregistered ammo type would leave these keys untouched and surface them as
		// spurious unknown-key warnings, even though the admin is authoring a coherent config.
		// Not .required(): Ammo_Type is one of two alternative ways to configure ammo (the other is Types:) and
		// may legitimately be absent — NodeReader.required() records a config.required ConfigReport ERROR for a
		// missing key, which would wrongly fail every Types:-based or no-magazine weapon now that
		// WeaponAddon.registerWeapon treats any report error as a load failure.
		String       ammoTypeString = ammo.get("Ammo_Type").asString().orNull();
		// [Types] alternative to Ammo_Type: a list of ammo ids: reload consumes the first the player carries, in
		// this list's order. Setting both Ammo_Type and Types is a config error.
		List<String> typesList      = ammo.get("Types").asList().ofStrings().orEmpty();
		int          capacity       = ammo.get("Capacity").asInt().min(0).orDefault(0);
		int          consume        = ammo.get("Consume").asInt().min(0).orDefault(1);
		int          restore        = ammo.get("Restore").asInt().min(0).orDefault(capacity);

		int              cooldown              = 0;
		ReloadType       reloadType            = ReloadType.getType("instant");
		boolean          unloadAmmoOnReload    = false;
		int              shootDelayAfterReload = 0;
		boolean          autoReloadWhenEmpty   = false;
		ReloadStagesData stages                = ReloadStagesData.defaults();

		MappingNode reloadSection = root.get("Reload").asMapping().orNull();
		if (reloadSection != null) {
			NodeReader reload = NodeReader.of(reloadSection, report);

			cooldown = reload.get("Cooldown").asInt().min(0).orDefault(0);

			String typeStr    = reload.get("Type").asString().orDefault("instant");
			int    typeAmount = 1;
			if (typeStr.contains("-")) {
				String[] parts = typeStr.split("-");
				typeStr = parts[0];
				try {
					typeAmount = Integer.parseInt(parts[1]);
				} catch (NumberFormatException ignored) { }
			}
			reloadType = ReloadType.getType(typeStr);
			reloadType.setAmount(typeAmount);

			unloadAmmoOnReload    = reload.get("Unload_Ammo_On_Reload").asBool().orDefault(false);
			shootDelayAfterReload = reload.get("Shoot_Delay_After_Reload").asInt().min(0).orDefault(0);
			autoReloadWhenEmpty   = reload.get("Auto_Reload_When_Empty").asBool().orDefault(false);
			stages                = parseStages(reload, report);
		}

		ReloadData reloadData = ReloadData.builder()
				.cooldown(cooldown)
				.type(reloadType)
				.unloadAmmoOnReload(unloadAmmoOnReload)
				.shootDelayAfterReload(shootDelayAfterReload)
				.autoReloadWhenEmpty(autoReloadWhenEmpty)
				.stages(stages)
				.build();

		boolean hasSingle = ammoTypeString != null && !ammoTypeString.isEmpty();
		boolean hasList   = !typesList.isEmpty();

		if (hasSingle && hasList) {
			report.add(Severity.ERROR, ammoSection.location(), "Ammunition",
			           "Ammo_Type and Types cannot both be set", "ammo.both_ammo_type_and_types");
			return null;
		}

		// Ammo_Type: none — a magazine that reloads without consuming any item (infinite supply).
		if (hasSingle && "none".equalsIgnoreCase(ammoTypeString)) {
			return new ParsedAmmo(reloadData, new AmmunitionData(List.of(), capacity, consume, restore));
		}

		List<Ammunition> resolved = new ArrayList<>();
		if (hasSingle) {
			resolved.add(resolveAmmo(ammoSection, report, "Ammunition.Ammo_Type", ammoTypeString));
		} else if (hasList) {
			for (String id : typesList) {
				resolved.add(resolveAmmo(ammoSection, report, "Ammunition.Types", id));
			}
		} else {
			// Ammunition: present but neither key set — silently no magazine, same as the section being absent.
			return null;
		}

		if (resolved.contains(null)) return null;

		return new ParsedAmmo(reloadData, new AmmunitionData(resolved, capacity, consume, restore));
	}

	/**
	 * {@code Reload.Stages} (weapons-roadmap.md gate {@code HO}) — absent entirely means {@link
	 * ReloadStagesData#defaults()} (resume is on by default). An unrecognised child key (anything but
	 * {@code Resume_Window}/{@code Open}/{@code Insert}/{@code Close}) is a {@link Severity#WARNING} and is
	 * ignored, same house style as {@code EffectsSectionParser}'s unknown hooks.
	 */
	private static ReloadStagesData parseStages(NodeReader reload, ConfigReport report) {
		MappingNode stagesSection = reload.get("Stages").asMapping().orNull();
		if (stagesSection == null) return ReloadStagesData.defaults();

		NodeReader       stages   = NodeReader.of(stagesSection, report);
		ReloadStagesData defaults = ReloadStagesData.defaults();

		// Not .min(0): a non-positive Resume_Window/Share is a deliberate, documented fallback (ReloadStagesData's
		// javadoc), not a config error - .min(0) would record a ConfigReport ERROR and fail the whole file's load
		// for a value the DTO already clamps/normalises on its own.
		int    resumeWindow = stages.get("Resume_Window").asInt().orDefault(defaults.getResumeWindowTicks());
		double openShare    = shareOf(stages, "Open", defaults.getOpenShare());
		double insertShare  = shareOf(stages, "Insert", defaults.getInsertShare());
		double closeShare   = shareOf(stages, "Close", defaults.getCloseShare());

		for (String key : stages.keys()) {
			if (key.equalsIgnoreCase("Resume_Window") || key.equalsIgnoreCase("Open")
					|| key.equalsIgnoreCase("Insert") || key.equalsIgnoreCase("Close")) {
				continue;
			}

			report.add(Severity.WARNING, stagesSection.location(), "Reload.Stages." + key,
			           "unknown reload stage '" + key + "'", "reload.unknown_stage");
		}

		return ReloadStagesData.of(resumeWindow, openShare, insertShare, closeShare);
	}

	private static double shareOf(NodeReader stages, String key, double fallback) {
		MappingNode stageMapping = stages.get(key).asMapping().orNull();
		if (stageMapping == null) return fallback;

		// Not .min(0) - see the Resume_Window comment above; a non-positive share falls back, it isn't an error.
		return NodeReader.of(stageMapping, stages.report()).get("Share").asDouble().orDefault(fallback);
	}

	@Nullable
	private Ammunition resolveAmmo(MappingNode ammoSection, ConfigReport report, String path, String id) {
		Ammunition ammo = ammunitionManager.getAmmunition(id);
		if (ammo == null) {
			report.add(Severity.ERROR, ammoSection.location(), path, "unknown ammo type '" + id + "'",
			           "ammo.unknown_type");
		}
		return ammo;
	}

	public record ParsedAmmo(ReloadData reload, AmmunitionData ammo) { }

}
