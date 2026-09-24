package org.luckyraven.bartizan.configuration.parser;

import org.bukkit.configuration.InvalidConfigurationException;
import org.luckyraven.keystone.persistence.config.ConfigReport;
import org.luckyraven.keystone.persistence.config.NodeReader;
import org.luckyraven.bartizan.api.weapon.SelectiveFire;
import org.luckyraven.bartizan.ammo.AmmunitionManager;
import org.luckyraven.bartizan.configuration.parser.AmmunitionSectionParser.ParsedAmmo;
import org.luckyraven.bartizan.api.weapon.dto.AmmunitionData;
import org.luckyraven.bartizan.api.weapon.dto.MeleeData;
import org.luckyraven.bartizan.api.weapon.dto.ReloadData;
import org.luckyraven.bartizan.api.weapon.MeleeWeapon;

import java.util.EnumSet;

/**
 * Parses the {@code Attack:} / {@code Shoot:} section of a MELEE weapon YAML and constructs a {@link MeleeWeapon}.
 */
public class MeleeWeaponParser {

	private final AmmunitionSectionParser ammoParser;

	public MeleeWeaponParser(AmmunitionManager ammunitionManager) {
		this.ammoParser = new AmmunitionSectionParser(ammunitionManager);
	}

	public MeleeWeapon parse(NodeReader root, NodeReader shoot, ConfigReport report, WeaponBaseData base)
			throws InvalidConfigurationException {
		if (shoot == null) {
			throw new InvalidConfigurationException("Attack/Shoot section not found for melee weapon");
		}

		double damage    = shoot.get("Damage").asDouble().min(0).required().orDefault(4.0);
		double range     = shoot.get("Range").asDouble().min(0).required().orDefault(2.5);
		int    cooldown  = shoot.get("Cooldown").asInt().min(0).orDefault(10);
		double knockback = shoot.get("Knockback").asDouble().orDefault(0.5);

		MeleeData      meleeData      = new MeleeData(damage, range, cooldown, knockback);
		ParsedAmmo     parsed         = ammoParser.parse(root, report);
		ReloadData     reloadData     = parsed != null ? parsed.reload() : null;
		AmmunitionData ammunitionData = parsed != null ? parsed.ammo() : null;

		MeleeWeapon weapon = new MeleeWeapon(null, base.fileName(), base.displayName(), base.category(),
		                                     base.material(), base.customModelData(), base.durability(), base.lore(),
		                                     base.dropHologram(), base.deathMessages(), meleeData, reloadData,
		                                     ammunitionData);

		// BZ-CF-07: without this, Weapon.allowedSelectiveFires stays null (no default) and
		// SelectiveFire.getNextState(Set) treats null as "no restriction", so a melee weapon configured with
		// Selective_Fire: single still cycles through AUTO/BURST on a sneak+swap-hand.
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
