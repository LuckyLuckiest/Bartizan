package org.luckyraven.bartizan.configuration;

import com.cryptomorin.xseries.XAttribute;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.luckyraven.bartizan.ammo.AmmunitionManager;
import org.luckyraven.bartizan.api.testsupport.BukkitRegistryFixture;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.api.weapon.dto.HandlingData;
import org.luckyraven.bartizan.api.weapon.dto.HandlingData.Circumstance;
import org.luckyraven.bartizan.api.weapon.dto.HandlingData.Rule;
import org.luckyraven.bartizan.api.weapon.dto.HandlingData.Trigger;
import org.luckyraven.keystone.persistence.FileHandler;
import org.luckyraven.keystone.persistence.config.ConfigReport;
import org.luckyraven.keystone.persistence.config.Severity;
import org.luckyraven.keystone.testkit.PluginMocks;
import org.mockito.MockedStatic;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Covers gate {@code HE} part a: {@code WeaponAddon.registerWeapon}'s parsing of {@code Information.Equip_Delay}/
 * {@code Deny_Use_In_Crafting}/{@code Cancel}/{@code Attributes} and {@code Shoot.Trigger}/{@code Circumstance}/
 * {@code Destroy_When_Empty}/{@code Reset_Fall_Distance} into {@link HandlingData} — an addon-level test (no
 * standalone {@code HandlingSectionParser} exists; the keys are parsed inline in {@code registerWeapon}, mirroring
 * {@code Information:}'s other inline fields).
 */
class HandlingConfigTest {

	@TempDir
	Path tempDir;

	private WeaponAddon weaponAddon;
	/**
	 * {@code XAttribute} itself is statically mocked in {@link #registerWithReport} so {@code WeaponAddon}'s
	 * {@code Attributes:} parsing is exercised without a real Bukkit attribute registry; this is the stand-in
	 * {@code Attribute} every resolved name maps to.
	 *
	 * <p>Tried dropping this in favour of the real {@code XAttribute.of(String)} call now that
	 * {@code BukkitRegistryFixture}'s proxy elements return a real {@code getKey()} (previously {@code null}, which
	 * crashed {@code XRegistry.getBukkitName}) — confirmed empirically ({@code everyKey_parsesIntoHandlingData}
	 * failed with two non-identical {@code Attribute} proxies for the same name) that it still doesn't resolve
	 * reliably: the fixture mints a brand-new proxy on every {@code Registry#get} call instead of caching by key,
	 * so XSeries' own dedup-by-bukkit-form-identity in {@code XRegistry#pullSystemValues} (which separately walks
	 * {@code Attribute}'s own {@code MOVEMENT_SPEED} etc. constants) registers a second, non-identical
	 * {@code XAttribute} wrapper under the same name and wins the last write — a real Bukkit registry caches by
	 * key and would never hit this. The mock stays.
	 */
	private final Attribute mockAttribute = mock(Attribute.class);

	@BeforeAll
	static void bootstrapBukkitRegistry() {
		BukkitRegistryFixture.install();
	}

	@Test
	@DisplayName("no Handling keys configured: every default applies")
	void defaults_applyWhenNoHandlingKeysConfigured() throws Exception {
		Weapon weapon = register("minimal.yml", """
				Information:
				   Name: "&7Minimal&r"
				   Category: melee
				   Material: IRON_HOE
				   Durability:
				      Base: 100

				Attack:
				   Damage: 5.0
				   Range: 2.5
				""");

		HandlingData handling = weapon.getHandlingData();
		assertNotNull(handling, "HandlingData must always be set by registerWeapon");
		assertEquals(0, handling.getEquipDelay());
		assertTrue(handling.isDenyUseInCrafting());
		assertEquals(new HandlingData.Cancel(false, false, true, false), handling.getCancel());
		assertTrue(handling.getAttributes().isEmpty());
		assertEquals(Trigger.RIGHT_CLICK, handling.getTrigger());
		assertTrue(handling.getCircumstances().isEmpty());
		assertFalse(handling.isDestroyWhenEmpty());
		assertFalse(handling.isResetFallDistance());
	}

	@Test
	@DisplayName("every key configured: all parse into HandlingData")
	void everyKey_parsesIntoHandlingData() throws Exception {
		Weapon weapon = register("full.yml", """
				Information:
				   Name: "&7Full&r"
				   Category: melee
				   Material: IRON_HOE
				   Durability:
				      Base: 100
				   Equip_Delay: 10
				   Deny_Use_In_Crafting: false
				   Cancel:
				      Drop_Item: true
				      Swap_Hands: true
				      Break_Blocks: false
				      Arm_Swing: true
				   Attributes:
				      - "MOVEMENT_SPEED ADD_SCALAR -0.1"

				Attack:
				   Damage: 5.0
				   Range: 2.5
				   Trigger: left_click
				   Circumstance:
				      Sprinting: deny
				      Zooming: required
				   Destroy_When_Empty: true
				   Reset_Fall_Distance: true
				""");

		HandlingData handling = weapon.getHandlingData();
		assertNotNull(handling);
		assertEquals(10, handling.getEquipDelay());
		assertFalse(handling.isDenyUseInCrafting());
		assertEquals(new HandlingData.Cancel(true, true, false, true), handling.getCancel());
		assertEquals(Trigger.LEFT_CLICK, handling.getTrigger());
		assertEquals(Rule.DENY, handling.getCircumstances().get(Circumstance.SPRINTING));
		assertEquals(Rule.REQUIRED, handling.getCircumstances().get(Circumstance.ZOOMING));
		assertTrue(handling.isDestroyWhenEmpty());
		assertTrue(handling.isResetFallDistance());

		assertEquals(1, handling.getAttributes().size());
		HandlingData.AttributeEntry entry = handling.getAttributes().get(0);
		assertEquals(mockAttribute, entry.attribute());
		assertEquals(AttributeModifier.Operation.ADD_SCALAR, entry.operation());
		assertEquals(-0.1, entry.amount());
	}

	@Test
	@DisplayName("a malformed Attributes entry is a ConfigReport warning and is skipped, the rest still loads")
	void badAttributeEntry_isWarningAndSkipped() throws Exception {
		ConfigReport report = registerWithReport("bad_attribute.yml", """
				Information:
				   Name: "&7Bad Attribute&r"
				   Category: melee
				   Material: IRON_HOE
				   Durability:
				      Base: 100
				   Attributes:
				      - "MOVEMENT_SPEED ADD_SCALAR -0.1"
				      - "not_enough_tokens"

				Attack:
				   Damage: 5.0
				   Range: 2.5
				""");

		assertFalse(report.hasErrors(), "a malformed Attributes entry must not fail the whole weapon's load");
		assertTrue(report.issues().stream().anyMatch(
				           issue -> issue.severity() == Severity.WARNING &&
				                    issue.code().equals("handling.unknown_attribute")),
		           "expected a handling.unknown_attribute warning");

		Weapon       weapon   = weaponAddon.getWeapon("bad_attribute");
		HandlingData handling = weapon.getHandlingData();
		assertEquals(1, handling.getAttributes().size(), "only the well-formed entry should be kept");
	}

	@Test
	@DisplayName("an unrecognised Circumstance value is a ConfigReport warning, the key is left unset")
	void badCircumstanceValue_isWarningAndSkipped() throws Exception {
		ConfigReport report = registerWithReport("bad_circumstance.yml", """
				Information:
				   Name: "&7Bad Circumstance&r"
				   Category: melee
				   Material: IRON_HOE
				   Durability:
				      Base: 100

				Attack:
				   Damage: 5.0
				   Range: 2.5
				   Circumstance:
				      Swimming: not_a_rule
				""");

		assertFalse(report.hasErrors());
		assertTrue(report.issues().stream().anyMatch(
				           issue -> issue.severity() == Severity.WARNING &&
				                    issue.code().equals("handling.unknown_swimming")));

		Weapon weapon = weaponAddon.getWeapon("bad_circumstance");
		assertFalse(weapon.getHandlingData().getCircumstances().containsKey(Circumstance.SWIMMING));
	}

	@Test
	@DisplayName("an unrecognised Trigger value is a ConfigReport warning, falling back to right_click")
	void badTriggerValue_isWarningAndDefaultsToRightClick() throws Exception {
		ConfigReport report = registerWithReport("bad_trigger.yml", """
				Information:
				   Name: "&7Bad Trigger&r"
				   Category: melee
				   Material: IRON_HOE
				   Durability:
				      Base: 100

				Attack:
				   Damage: 5.0
				   Range: 2.5
				   Trigger: sideways
				""");

		assertFalse(report.hasErrors());
		assertTrue(report.issues().stream().anyMatch(
				           issue -> issue.severity() == Severity.WARNING &&
				                    issue.code().equals("handling.unknown_trigger")));

		Weapon weapon = weaponAddon.getWeapon("bad_trigger");
		assertEquals(Trigger.RIGHT_CLICK, weapon.getHandlingData().getTrigger());
	}

	private Weapon register(String fileName, String yaml) throws Exception {
		registerWithReport(fileName, yaml);
		return weaponAddon.getWeapon(fileName.replaceFirst("\\.yml$", ""));
	}

	private ConfigReport registerWithReport(String fileName, String yaml) throws Exception {
		JavaPlugin        plugin            = PluginMocks.plugin(tempDir);
		AmmunitionManager ammunitionManager = new AmmunitionManager();
		File              weaponFile        = writeWeaponFile(fileName, yaml);

		weaponAddon = new WeaponAddon(null);

		XAttribute mockXAttribute = mock(XAttribute.class);
		when(mockXAttribute.get()).thenReturn(mockAttribute);

		try (MockedStatic<XAttribute> xAttribute = mockStatic(XAttribute.class)) {
			xAttribute.when(() -> XAttribute.of(anyString())).thenReturn(Optional.of(mockXAttribute));
			return weaponAddon.registerWeapon(ammunitionManager, new FileHandler(plugin, weaponFile));
		}
	}

	private File writeWeaponFile(String name, String yaml) throws IOException {
		File file = tempDir.resolve("weapon/" + name).toFile();
		Files.createDirectories(file.getParentFile().toPath());
		Files.writeString(file.toPath(), yaml);
		return file;
	}

}
