package org.luckyraven.bartizan.configuration.parser;

import org.bukkit.Material;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.ammo.AmmunitionManager;
import org.luckyraven.bartizan.api.weapon.MeleeWeapon;
import org.luckyraven.bartizan.api.weapon.SelectiveFire;
import org.luckyraven.bartizan.api.weapon.WeaponType;
import org.luckyraven.keystone.persistence.config.ConfigDocument;
import org.luckyraven.keystone.persistence.config.ConfigParser;
import org.luckyraven.keystone.persistence.config.ConfigReport;
import org.luckyraven.keystone.persistence.config.NodeReader;

import java.io.StringReader;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * BZ-CF-07: {@link MeleeWeaponParser} used to never call {@code setAllowedSelectiveFires}, leaving
 * {@code Weapon.allowedSelectiveFires} at its no-default {@code null} — {@code SelectiveFire.getNextState(Set)}
 * treats {@code null} as "no restriction", so a melee weapon configured with {@code Selective_Fire: single} still
 * cycled through every mode.
 */
@DisplayName("MeleeWeaponParser — Selective_Fire (BZ-CF-07)")
class MeleeWeaponParserTest {

	private static final Path FIXTURE = Path.of("weapon.yml");

	private MeleeWeaponParser parser;
	private ConfigReport      report;

	@BeforeEach
	void setUp() {
		parser = new MeleeWeaponParser(new AmmunitionManager());
		report = new ConfigReport();
	}

	@Test
	@DisplayName("Selective_Fire: single with no Allowed_Modes -> allowed is the single-mode set, not null/unrestricted")
	void selectiveFireSingle_restrictsAllowedModes() throws Exception {
		MeleeWeapon weapon = parse("""
				Attack:
				   Damage: 7.5
				   Range: 2.7
				   Selective_Fire: single
				""");

		assertEquals(SelectiveFire.SINGLE, weapon.getCurrentSelectiveFire());
		assertEquals(Set.of(SelectiveFire.SINGLE), weapon.getAllowedSelectiveFires());
	}

	@Test
	@DisplayName("Selective_Fire absent entirely -> still defaults to a restricted single-mode set")
	void selectiveFireAbsent_defaultsToRestrictedSingle() throws Exception {
		MeleeWeapon weapon = parse("""
				Attack:
				   Damage: 7.5
				   Range: 2.7
				""");

		assertEquals(SelectiveFire.SINGLE, weapon.getCurrentSelectiveFire());
		assertEquals(Set.of(SelectiveFire.SINGLE), weapon.getAllowedSelectiveFires());
	}

	private MeleeWeapon parse(String yaml) throws Exception {
		ConfigDocument doc   = new ConfigParser().parse(FIXTURE, new StringReader(yaml), report);
		NodeReader     root  = NodeReader.of(doc.root(), report);
		NodeReader     shoot = NodeReader.of(root.get("Attack").asMapping().orNull(), report);

		WeaponBaseData base = new WeaponBaseData("test_knife", "&bTest Knife", WeaponType.MELEE, Material.IRON_HOE,
		                                         0, (short) 100, List.of(), false, null);

		return parser.parse(root, shoot, report, base);
	}

}
