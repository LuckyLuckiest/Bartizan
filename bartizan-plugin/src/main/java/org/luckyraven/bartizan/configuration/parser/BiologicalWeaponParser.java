package org.luckyraven.bartizan.configuration.parser;

import org.bukkit.configuration.InvalidConfigurationException;
import org.luckyraven.keystone.persistence.config.ConfigReport;
import org.luckyraven.keystone.persistence.config.NodeReader;
import org.luckyraven.bartizan.api.weapon.SelectiveFire;
import org.luckyraven.bartizan.ammo.AmmunitionManager;
import org.luckyraven.bartizan.configuration.parser.AmmunitionSectionParser.ParsedAmmo;
import org.luckyraven.bartizan.api.weapon.dto.AmmunitionData;
import org.luckyraven.bartizan.api.weapon.dto.BiologicalData;
import org.luckyraven.bartizan.api.weapon.dto.ChargeData;
import org.luckyraven.bartizan.api.weapon.dto.ReloadData;
import org.luckyraven.bartizan.api.weapon.dto.StatusData;
import org.luckyraven.bartizan.api.weapon.BiologicalWeapon;

import java.util.EnumSet;
import java.util.List;

/**
 * Parses the {@code Shoot:} section of a BIOLOGICAL weapon YAML and constructs a {@link BiologicalWeapon}.
 */
public class BiologicalWeaponParser {

	private final AmmunitionSectionParser ammoParser;

	public BiologicalWeaponParser(AmmunitionManager ammunitionManager) {
		this.ammoParser = new AmmunitionSectionParser(ammunitionManager);
	}

	public BiologicalWeapon parse(NodeReader root, NodeReader shoot, ConfigReport report, WeaponBaseData base)
			throws InvalidConfigurationException {
		if (shoot == null) {
			throw new InvalidConfigurationException("Shoot section not found for biological weapon");
		}

		ChargeData   charge           = ChargeSectionParser.parse(shoot, report);
		double       range            = shoot.get("Range").asDouble().min(0).orDefault(30.0);
		double       baseDamage       = shoot.get("Base_Damage").asDouble().min(0).orDefault(4.0);
		List<String> effectsPerLevel  = shoot.get("Effects_Per_Level").asList().ofStrings().orEmpty();
		boolean      cumulativeLevels = shoot.get("Cumulative_Levels").asBool().orDefault(false);
		StatusData   status           = StatusSectionParser.parse(shoot, report, base.displayName(), charge.getMaxLevel());

		// BZ-CF-05: a BIOLOGICAL weapon with no Effects_Per_Level used to load clean and then silently apply no
		// status effect on every shot (BiologicalAction.effectsForLevel returns List.of() for an empty list) —
		// mirrors ThrowableWeaponParser's Type: STUN + empty Effects guard so an admin who omits the key finds out
		// at load time instead of shipping an inert weapon.
		if (effectsPerLevel.isEmpty()) {
			throw new InvalidConfigurationException(
					"Biological weapon '" + base.fileName() + "' has an empty or missing Effects_Per_Level");
		}

		BiologicalData biologicalData = new BiologicalData(charge, effectsPerLevel, range, baseDamage, status,
		                                                    cumulativeLevels);
		ParsedAmmo     parsed         = ammoParser.parse(root, report);
		ReloadData     reloadData     = parsed != null ? parsed.reload() : null;
		AmmunitionData ammunitionData = parsed != null ? parsed.ammo() : null;

		BiologicalWeapon weapon = new BiologicalWeapon(null, base.fileName(), base.displayName(), base.category(),
		                                               base.material(), base.customModelData(), base.durability(),
		                                               base.lore(), base.dropHologram(), base.deathMessages(),
		                                               biologicalData, reloadData, ammunitionData);

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
