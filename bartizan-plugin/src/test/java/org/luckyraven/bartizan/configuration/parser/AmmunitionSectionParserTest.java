package org.luckyraven.bartizan.configuration.parser;

import org.bukkit.Material;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.ammo.AmmunitionManager;
import org.luckyraven.bartizan.api.ammo.Ammunition;
import org.luckyraven.bartizan.api.weapon.dto.ReloadData;
import org.luckyraven.bartizan.configuration.parser.AmmunitionSectionParser.ParsedAmmo;
import org.luckyraven.keystone.persistence.config.ConfigDocument;
import org.luckyraven.keystone.persistence.config.ConfigParser;
import org.luckyraven.keystone.persistence.config.ConfigReport;
import org.luckyraven.keystone.persistence.config.NodeReader;
import org.luckyraven.keystone.persistence.config.Severity;

import java.io.StringReader;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers gate {@code HG}: {@code Ammo_Type: none}, the {@code Types:} list (alternative to {@code Ammo_Type},
 * mutually exclusive with it), unknown ammo ids being a {@link Severity#ERROR} on the {@link ConfigReport}, and
 * the new {@code Reload.Unload_Ammo_On_Reload}/{@code Shoot_Delay_After_Reload}/{@code Auto_Reload_When_Empty}
 * keys' defaults and parsing.
 */
@DisplayName("AmmunitionSectionParser")
class AmmunitionSectionParserTest {

	private static final Path FIXTURE = Path.of("weapon.yml");

	private ConfigReport            report;
	private AmmunitionSectionParser parser;

	@BeforeEach
	void setUp() {
		AmmunitionManager manager = new AmmunitionManager();
		manager.register("9mm", new Ammunition("9mm", "&c9mm", Material.GOLD_NUGGET, 0, List.of()));
		manager.register("slugs", new Ammunition("slugs", "&cSlugs", Material.RED_DYE, 0, List.of()));
		parser = new AmmunitionSectionParser(manager);
	}

	@Test
	@DisplayName("no Ammunition: section -> null, no error (weapon has no magazine)")
	void noSection_returnsNullNoError() {
		ParsedAmmo parsed = parser.parse(rootReaderFor("Information:\n   Name: test\n"), report);

		assertNull(parsed);
		assertFalse(report.hasErrors());
	}

	@Test
	@DisplayName("Ammunition: present but neither Ammo_Type nor Types set -> null, no error")
	void sectionPresentButEmpty_returnsNullNoError() {
		ParsedAmmo parsed = parser.parse(rootReaderFor("""
				Ammunition:
				   Capacity: 6
				"""), report);

		assertNull(parsed);
		assertFalse(report.hasErrors());
	}

	@Test
	@DisplayName("Ammo_Type: none -> infinite supply, empty ammoTypes, no error")
	void ammoTypeNone_infiniteSupply() {
		ParsedAmmo parsed = parser.parse(rootReaderFor("""
				Ammunition:
				   Ammo_Type: none
				   Capacity: 6
				   Consume: 1
				   Restore: 1
				"""), report);

		assertTrue(parsed.ammo().getAmmoTypes().isEmpty());
		assertNull(parsed.ammo().getAmmoType());
		assertEquals(6, parsed.ammo().getMaxMagCapacity());
		assertFalse(report.hasErrors());
	}

	@Test
	@DisplayName("Types: list resolves multiple ammo types in list order")
	void typesList_resolvesMultipleInOrder() {
		ParsedAmmo parsed = parser.parse(rootReaderFor("""
				Ammunition:
				   Types:
				      - slugs
				      - 9mm
				   Capacity: 6
				   Consume: 1
				   Restore: 1
				"""), report);

		assertEquals(List.of("slugs", "9mm"),
		             parsed.ammo().getAmmoTypes().stream().map(Ammunition::getName).toList());
		assertEquals("slugs", parsed.ammo().getAmmoType().getName(), "getAmmoType() is the first of the list");
		assertFalse(report.hasErrors());
	}

	@Test
	@DisplayName("both Ammo_Type and Types given -> config error, null")
	void bothAmmoTypeAndTypes_isConfigError() {
		ParsedAmmo parsed = parser.parse(rootReaderFor("""
				Ammunition:
				   Ammo_Type: 9mm
				   Types:
				      - slugs
				   Capacity: 6
				"""), report);

		assertNull(parsed);
		assertTrue(report.hasErrors());
		assertTrue(report.issues().stream().anyMatch(
				issue -> issue.severity() == Severity.ERROR
				         && issue.code().equals("ammo.both_ammo_type_and_types")));
	}

	@Test
	@DisplayName("unknown Ammo_Type -> config error, null")
	void unknownAmmoType_isConfigError() {
		ParsedAmmo parsed = parser.parse(rootReaderFor("""
				Ammunition:
				   Ammo_Type: plasma
				   Capacity: 6
				"""), report);

		assertNull(parsed);
		assertTrue(report.hasErrors());
		assertTrue(report.issues().stream().anyMatch(
				issue -> issue.severity() == Severity.ERROR && issue.code().equals("ammo.unknown_type")));
	}

	@Test
	@DisplayName("unknown id inside Types: -> config error, null")
	void unknownTypeInList_isConfigError() {
		ParsedAmmo parsed = parser.parse(rootReaderFor("""
				Ammunition:
				   Types:
				      - 9mm
				      - plasma
				   Capacity: 6
				"""), report);

		assertNull(parsed);
		assertTrue(report.hasErrors());
		assertTrue(report.issues().stream().anyMatch(
				issue -> issue.severity() == Severity.ERROR && issue.code().equals("ammo.unknown_type")));
	}

	@Test
	@DisplayName("Reload's new keys default to false/0 when absent")
	void reloadNewKeys_defaultToFalseZero() {
		ParsedAmmo parsed = parser.parse(rootReaderFor("""
				Ammunition:
				   Ammo_Type: 9mm
				   Capacity: 6
				Reload:
				   Cooldown: 1
				"""), report);

		ReloadData reload = parsed.reload();
		assertFalse(reload.isUnloadAmmoOnReload());
		assertEquals(0, reload.getShootDelayAfterReload());
		assertFalse(reload.isAutoReloadWhenEmpty());
	}

	@Test
	@DisplayName("Reload's new keys parse when set")
	void reloadNewKeys_parseWhenSet() {
		ParsedAmmo parsed = parser.parse(rootReaderFor("""
				Ammunition:
				   Ammo_Type: 9mm
				   Capacity: 6
				Reload:
				   Cooldown: 1
				   Unload_Ammo_On_Reload: true
				   Shoot_Delay_After_Reload: 12
				   Auto_Reload_When_Empty: true
				"""), report);

		ReloadData reload = parsed.reload();
		assertTrue(reload.isUnloadAmmoOnReload());
		assertEquals(12, reload.getShootDelayAfterReload());
		assertTrue(reload.isAutoReloadWhenEmpty());
	}

	@Test
	@DisplayName("BZ-CF-04: Capacity/Restore of 0 are clamped to 1, not left at 0 (avoids a reload division by zero)")
	void capacityAndRestore_zero_clampedToOne() {
		ParsedAmmo parsed = parser.parse(rootReaderFor("""
				Ammunition:
				   Ammo_Type: 9mm
				   Capacity: 0
				   Restore: 0
				"""), report);

		assertEquals(1, parsed.ammo().getMaxMagCapacity());
		assertEquals(1, parsed.ammo().getRestore());
	}

	@Test
	@DisplayName("BZ-CF-04: Reload.Type: numbered-0 is clamped to 1 with a WARNING, not left at 0")
	void reloadTypeNumberedZero_clampedToOneWithWarning() {
		ParsedAmmo parsed = parser.parse(rootReaderFor("""
				Ammunition:
				   Ammo_Type: 9mm
				   Capacity: 6
				Reload:
				   Type: num-0
				"""), report);

		assertEquals(1, parsed.reload().getAmount());
		assertTrue(report.issues().stream().anyMatch(
				issue -> issue.severity() == Severity.WARNING
				         && issue.code().equals("reload.type_amount_too_low")));
	}

	@Test
	@DisplayName("BZ-CF-04: Reload.Type with a non-numeric amount is clamped to 1 with a WARNING")
	void reloadTypeMalformedAmount_clampedToOneWithWarning() {
		ParsedAmmo parsed = parser.parse(rootReaderFor("""
				Ammunition:
				   Ammo_Type: 9mm
				   Capacity: 6
				Reload:
				   Type: num-abc
				"""), report);

		assertEquals(1, parsed.reload().getAmount());
		assertTrue(report.issues().stream().anyMatch(
				issue -> issue.severity() == Severity.WARNING
				         && issue.code().equals("reload.type_amount_malformed")));
	}

	@Test
	@DisplayName("BZ-CF-04: Reload.Type: num-3 parses the amount normally, no warning")
	void reloadTypeNumberedValid_parsesNormally() {
		ParsedAmmo parsed = parser.parse(rootReaderFor("""
				Ammunition:
				   Ammo_Type: 9mm
				   Capacity: 6
				Reload:
				   Type: num-3
				"""), report);

		assertEquals(3, parsed.reload().getAmount());
		assertFalse(report.hasErrors());
	}

	private NodeReader rootReaderFor(String yaml) {
		report = new ConfigReport();
		ConfigDocument doc = new ConfigParser().parse(FIXTURE, new StringReader(yaml), report);
		return NodeReader.of(doc.root(), report);
	}

}
