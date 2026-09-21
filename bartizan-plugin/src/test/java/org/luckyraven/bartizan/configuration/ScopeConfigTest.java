package org.luckyraven.bartizan.configuration;

import com.cryptomorin.xseries.XMaterial;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.luckyraven.bartizan.ammo.AmmunitionManager;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.api.weapon.dto.ScopeData;
import org.luckyraven.bartizan.api.weapon.dto.ScopeType;
import org.luckyraven.keystone.persistence.FileHandler;
import org.luckyraven.keystone.persistence.config.ConfigReport;
import org.luckyraven.keystone.persistence.config.Severity;
import org.luckyraven.keystone.testkit.PluginMocks;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@code WeaponAddon.applyScope}'s gate-{@code HH} additions: {@code Night_Vision}, {@code Zoom_Stacking},
 * {@code Shoot_Delay_After_Scope}, the {@code Zoom_Amount} alias for {@code Level}, and the {@code ConfigReport}
 * warning when both {@code Level} and {@code Zoom_Amount} are configured (Level still wins at runtime).
 */
class ScopeConfigTest {

	@TempDir
	Path tempDir;

	@AfterEach
	void restoreSpyglassSupported() {
		// A lambda, not a method reference - see WeaponAddon.spyglassSupported's javadoc for why.
		WeaponAddon.spyglassSupported = () -> XMaterial.SPYGLASS.isSupported();
	}

	@Test
	@DisplayName("full Scope block parses every HH key")
	void scope_fullBlock_parsesEveryKey() throws Exception {
		Weapon weapon = registerAndGet("full_scope.yml", """
				Information:
				   Name: "&7Test Knife&r"
				   Category: melee
				   Material: IRON_HOE

				Attack:
				   Damage: 5.0
				   Range: 2.5

				Scope:
				   Level: 2
				   Night_Vision: true
				   Zoom_Stacking:
				      Maximum_Stacks: 3
				      Increase_Per_Stack: 2
				   Shoot_Delay_After_Scope: 5
				""");

		ScopeData scopeData = weapon.getScopeData();
		assertNotNull(scopeData);
		assertEquals(2, scopeData.getLevel());
		assertTrue(scopeData.isNightVision());
		assertEquals(3, scopeData.getZoomStacks());
		assertEquals(2, scopeData.getZoomPerStack());
		assertEquals(5, scopeData.getShootDelayAfterScope());
	}

	@Test
	@DisplayName("Zoom_Amount alone sets Level (WeaponMechanics-style alias)")
	void zoomAmount_alone_setsLevel() throws Exception {
		Weapon weapon = registerAndGet("zoom_amount.yml", """
				Information:
				   Name: "&7Test Knife&r"
				   Category: melee
				   Material: IRON_HOE

				Attack:
				   Damage: 5.0
				   Range: 2.5

				Scope:
				   Zoom_Amount: 3
				""");

		assertEquals(3, weapon.getScopeData().getLevel());
	}

	@Test
	@DisplayName("Level and Zoom_Amount both configured -> warning, Level wins")
	void levelAndZoomAmount_bothConfigured_warnsAndLevelWins() throws Exception {
		JavaPlugin  plugin      = PluginMocks.plugin(tempDir);
		WeaponAddon weaponAddon = new WeaponAddon(null);

		File weaponFile = writeWeaponFile("level_and_zoom_amount.yml", """
				Information:
				   Name: "&7Test Knife&r"
				   Category: melee
				   Material: IRON_HOE

				Attack:
				   Damage: 5.0
				   Range: 2.5

				Scope:
				   Level: 2
				   Zoom_Amount: 5
				""");

		ConfigReport report = weaponAddon.registerWeapon(new AmmunitionManager(), new FileHandler(plugin, weaponFile));

		Weapon weapon = weaponAddon.getWeapon("level_and_zoom_amount");
		assertNotNull(weapon);
		assertEquals(2, weapon.getScopeData().getLevel(), "Level must win over Zoom_Amount");
		assertTrue(report.issues().stream().anyMatch(
				issue -> issue.severity() == Severity.WARNING
				         && issue.code().equals("scope.level_and_zoom_amount")));
		assertTrue(report.issues().stream().noneMatch(issue -> issue.code().equals("config.unknown_key")),
		          "Zoom_Amount must be read even though Level wins - it must never look unknown");
	}

	@Test
	@DisplayName("optional HH keys default to no-op values when omitted")
	void scope_defaults_whenOptionalKeysOmitted() throws Exception {
		Weapon weapon = registerAndGet("bare_scope.yml", """
				Information:
				   Name: "&7Test Knife&r"
				   Category: melee
				   Material: IRON_HOE

				Attack:
				   Damage: 5.0
				   Range: 2.5

				Scope:
				   Level: 2
				""");

		ScopeData scopeData = weapon.getScopeData();
		assertFalse(scopeData.isNightVision());
		assertEquals(1, scopeData.getZoomStacks());
		assertEquals(1, scopeData.getZoomPerStack());
		assertEquals(0, scopeData.getShootDelayAfterScope());
	}

	// --- gate HP: Scope.Type ---

	@Test
	@DisplayName("Scope.Type omitted defaults to slowness")
	void scopeType_omitted_defaultsToSlowness() throws Exception {
		Weapon weapon = registerAndGet("no_scope_type.yml", """
				Information:
				   Name: "&7Test Knife&r"
				   Category: melee
				   Material: IRON_HOE

				Attack:
				   Damage: 5.0
				   Range: 2.5

				Scope:
				   Level: 2
				""");

		assertEquals(ScopeType.SLOWNESS, weapon.getScopeData().getType());
	}

	@Test
	@DisplayName("Scope.Type: spyglass on a pre-1.17 server warns once and falls back to slowness")
	void scopeType_spyglassUnsupportedServer_warnsAndFallsBackToSlowness() throws Exception {
		WeaponAddon.spyglassSupported = () -> false;

		RegisterResult result = registerWithReport("old_server_spyglass.yml", """
				Information:
				   Name: "&7Scout&r"
				   Category: gun
				   Material: SPYGLASS
				   Durability:
				      Base: 100

				Shoot:
				   Trigger: left_click
				   Selective_Fire: single
				   Allowed_Modes:
				      - single
				   Projectile:
				      Speed: 8
				      Type: BULLET
				      Damage:
				         Base: 10
				   Weapon_Consumed:
				      Consume_On_Shot: 0

				Scope:
				   Type: spyglass
				   Level: 0
				""");

		assertEquals(ScopeType.SLOWNESS, result.weapon().getScopeData().getType());
		assertTrue(result.report().issues().stream().anyMatch(
				issue -> issue.severity() == Severity.WARNING
				         && issue.code().equals("scope.spyglass_unsupported_version")));
		assertFalse(result.report().hasErrors());
	}

	@Test
	@DisplayName("Scope.Type: spyglass with a Material other than SPYGLASS is a loader error")
	void scopeType_spyglassWrongMaterial_isLoaderError() throws Exception {
		WeaponAddon.spyglassSupported = () -> true;

		RegisterResult result = registerWithReport("wrong_material_spyglass.yml", """
				Information:
				   Name: "&7Scout&r"
				   Category: gun
				   Material: IRON_HOE
				   Durability:
				      Base: 100

				Shoot:
				   Trigger: left_click
				   Selective_Fire: single
				   Allowed_Modes:
				      - single
				   Projectile:
				      Speed: 8
				      Type: BULLET
				      Damage:
				         Base: 10
				   Weapon_Consumed:
				      Consume_On_Shot: 0

				Scope:
				   Type: spyglass
				   Level: 0
				""");

		assertEquals(ScopeType.SLOWNESS, result.weapon().getScopeData().getType());
		assertTrue(result.report().hasErrors());
		assertTrue(result.report().issues().stream().anyMatch(
				issue -> issue.severity() == Severity.ERROR && issue.code().equals("scope.material_mismatch")));
	}

	@Test
	@DisplayName("Scope.Type: spyglass with the default Trigger: right_click warns")
	void scopeType_spyglassRightClickTrigger_warns() throws Exception {
		WeaponAddon.spyglassSupported = () -> true;

		RegisterResult result = registerWithReport("right_click_spyglass.yml", """
				Information:
				   Name: "&7Scout&r"
				   Category: gun
				   Material: SPYGLASS
				   Durability:
				      Base: 100

				Shoot:
				   Selective_Fire: single
				   Allowed_Modes:
				      - single
				   Projectile:
				      Speed: 8
				      Type: BULLET
				      Damage:
				         Base: 10
				   Weapon_Consumed:
				      Consume_On_Shot: 0

				Scope:
				   Type: spyglass
				   Level: 0
				""");

		assertEquals(ScopeType.SPYGLASS, result.weapon().getScopeData().getType());
		assertTrue(result.report().issues().stream().anyMatch(
				issue -> issue.severity() == Severity.WARNING
				         && issue.code().equals("scope.spyglass_right_click_trigger")));
	}

	@Test
	@DisplayName("Scope.Type: spyglass ignores Zoom_Stacking with a warning")
	void scopeType_spyglassWithZoomStacking_warnsAndIgnores() throws Exception {
		WeaponAddon.spyglassSupported = () -> true;

		RegisterResult result = registerWithReport("zoom_stacking_spyglass.yml", """
				Information:
				   Name: "&7Scout&r"
				   Category: gun
				   Material: SPYGLASS
				   Durability:
				      Base: 100

				Shoot:
				   Trigger: left_click
				   Selective_Fire: single
				   Allowed_Modes:
				      - single
				   Projectile:
				      Speed: 8
				      Type: BULLET
				      Damage:
				         Base: 10
				   Weapon_Consumed:
				      Consume_On_Shot: 0

				Scope:
				   Type: spyglass
				   Level: 0
				   Zoom_Stacking:
				      Maximum_Stacks: 3
				      Increase_Per_Stack: 1
				""");

		ScopeData scopeData = result.weapon().getScopeData();
		assertEquals(ScopeType.SPYGLASS, scopeData.getType());
		assertEquals(1, scopeData.getZoomStacks(), "Zoom_Stacking must be ignored for Scope.Type: spyglass");
		assertTrue(result.report().issues().stream().anyMatch(
				issue -> issue.severity() == Severity.WARNING
				         && issue.code().equals("scope.zoom_stacking_ignored_spyglass")));
	}

	private RegisterResult registerWithReport(String fileName, String yaml) throws Exception {
		JavaPlugin  plugin      = PluginMocks.plugin(tempDir);
		WeaponAddon weaponAddon = new WeaponAddon(null);

		File          weaponFile = writeWeaponFile(fileName, yaml);
		ConfigReport  report     = weaponAddon.registerWeapon(new AmmunitionManager(), new FileHandler(plugin, weaponFile));
		Weapon        weapon     = weaponAddon.getWeapon(fileName.replace(".yml", ""));

		assertNotNull(weapon);
		return new RegisterResult(weapon, report);
	}

	private record RegisterResult(Weapon weapon, ConfigReport report) {
	}

	private Weapon registerAndGet(String fileName, String yaml) throws Exception {
		JavaPlugin  plugin      = PluginMocks.plugin(tempDir);
		WeaponAddon weaponAddon = new WeaponAddon(null);

		File weaponFile = writeWeaponFile(fileName, yaml);
		weaponAddon.registerWeapon(new AmmunitionManager(), new FileHandler(plugin, weaponFile));

		Weapon weapon = weaponAddon.getWeapon(fileName.replace(".yml", ""));
		assertNotNull(weapon);
		return weapon;
	}

	private File writeWeaponFile(String name, String yaml) throws IOException {
		File file = tempDir.resolve("weapon/" + name).toFile();
		Files.createDirectories(file.getParentFile().toPath());
		Files.writeString(file.toPath(), yaml);
		return file;
	}

}
