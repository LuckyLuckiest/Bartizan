package org.luckyraven.bartizan.api.weapon;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.support.WeaponFixtures;
import org.luckyraven.bartizan.api.testsupport.BukkitRegistryFixture;
import org.luckyraven.bartizan.api.weapon.dto.ScopeData;
import org.luckyraven.bartizan.api.weapon.dto.SkinsData;
import org.luckyraven.keystone.item.ItemBuilder;

import java.lang.reflect.Method;
import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure coverage for gate {@code HJ}: {@link Weapon#currentSkinState(Player)}'s fixed priority order and
 * {@link Weapon#resolveCustomModelData(SkinState)}/{@link Weapon#resolveItemModel(SkinState)}'s named-skin -&gt;
 * state-skin -&gt; {@code Information} fallback chain. Most of this class touches neither {@code ItemStack} nor
 * {@code Material}, so no {@code BukkitRegistryFixture} is needed — the exception is the CMD-clearing test below,
 * which exercises the private {@code applySkinRendering}/{@code clearCustomModelData} path against a mocked
 * {@code ItemStack}/{@code ItemMeta} and so needs the fixture installed for {@code NmsVersion.current()}.
 */
@DisplayName("Weapon skin state + resolution (gate HJ)")
class WeaponSkinResolutionTest {

	@BeforeAll
	static void bootstrapBukkitRegistry() {
		BukkitRegistryFixture.install();
	}

	private static Map<SkinState, Integer> states(SkinState state, int value) {
		Map<SkinState, Integer> map = new EnumMap<>(SkinState.class);
		map.put(state, value);
		return map;
	}

	// --- currentSkinState priority ---

	@Test
	@DisplayName("reloading outranks scope/no-ammo/sprint")
	void reloading_outranksEverythingElse() {
		GunWeapon weapon = spy(WeaponFixtures.gunWeapon(10, 1));
		when(weapon.isReloading()).thenReturn(true);
		ScopeData scope = new ScopeData();
		scope.setLevel(2);
		scope.setScoped(true);
		weapon.setScopeData(scope);

		assertEquals(SkinState.RELOAD, weapon.currentSkinState(null));
	}

	@Test
	@DisplayName("being scoped outranks an empty magazine and sprinting")
	void scoped_outranksNoAmmoAndSprint() {
		GunWeapon weapon = WeaponFixtures.gunWeapon(10, 1);
		ScopeData scope = new ScopeData();
		scope.setLevel(2);
		scope.setScoped(true);
		weapon.setScopeData(scope);
		while (weapon.consumeShot()) {
			// drain the magazine to empty - scope must still win over the resulting NO_AMMO tier
		}

		Player sprinting = mock(Player.class);
		when(sprinting.isSprinting()).thenReturn(true);

		assertEquals(SkinState.SCOPE, weapon.currentSkinState(sprinting));
	}

	@Test
	@DisplayName("an empty magazine outranks sprinting")
	void emptyMagazine_outranksSprinting() {
		GunWeapon weapon = WeaponFixtures.gunWeapon(1, 1);
		weapon.consumeShot(); // 0/1

		Player sprinting = mock(Player.class);
		when(sprinting.isSprinting()).thenReturn(true);

		assertEquals(SkinState.NO_AMMO, weapon.currentSkinState(sprinting));
	}

	@Test
	@DisplayName("sprinting applies once nothing higher-priority is active")
	void sprinting_appliesWhenNothingElseActive() {
		GunWeapon weapon = WeaponFixtures.gunWeapon(10, 1);

		Player sprinting = mock(Player.class);
		when(sprinting.isSprinting()).thenReturn(true);

		assertEquals(SkinState.SPRINT, weapon.currentSkinState(sprinting));
	}

	@Test
	@DisplayName("a null player never resolves to SPRINT")
	void nullPlayer_neverSprints() {
		GunWeapon weapon = WeaponFixtures.gunWeapon(10, 1);

		assertEquals(SkinState.DEFAULT, weapon.currentSkinState(null));
	}

	// --- resolveCustomModelData / resolveItemModel ---

	@Test
	@DisplayName("no SkinsData -> resolveCustomModelData falls back to Information.Custom_Model_Data")
	void noSkinsData_fallsBackToInformationCmd() {
		GunWeapon weapon = WeaponFixtures.gunWeapon(10, 1);

		assertEquals(weapon.getCustomModelData(), weapon.resolveCustomModelData(SkinState.SCOPE));
		assertNull(weapon.resolveItemModel(SkinState.SCOPE));
	}

	@Test
	@DisplayName("a configured root state CMD wins over the root Default, which wins over Information")
	void configuredState_winsOverRootDefault() {
		GunWeapon weapon = WeaponFixtures.gunWeapon(10, 1);
		Map<SkinState, Integer> rootStates = states(SkinState.DEFAULT, 1000);
		rootStates.put(SkinState.SCOPE, 1001);
		weapon.setSkinsData(new SkinsData(rootStates, Map.of()));

		assertEquals(1001, weapon.resolveCustomModelData(SkinState.SCOPE));
		assertEquals(1000, weapon.resolveCustomModelData(SkinState.RELOAD)); // unconfigured -> root Default
	}

	@Test
	@DisplayName("an all-empty SkinsData falls all the way back to Information.Custom_Model_Data")
	void emptySkinsData_fallsBackToInformationCmd() {
		GunWeapon weapon = WeaponFixtures.gunWeapon(10, 1);
		weapon.setSkinsData(new SkinsData(Map.of(), Map.of()));

		assertEquals(weapon.getCustomModelData(), weapon.resolveCustomModelData(SkinState.SCOPE));
	}

	@Test
	@DisplayName("a selected named skin's own state override wins over the root state table")
	void namedSkin_stateOverride_winsOverRoot() {
		GunWeapon weapon = WeaponFixtures.gunWeapon(10, 1);
		Map<SkinState, Integer> rootStates = states(SkinState.DEFAULT, 1000);
		rootStates.put(SkinState.SCOPE, 1001);

		NamespacedKey goldModel = NamespacedKey.fromString("bartizan:rifle_gold");
		Map<String, SkinsData.NamedSkin> named = Map.of("gold",
				new SkinsData.NamedSkin(states(SkinState.SCOPE, 2001), goldModel));
		weapon.setSkinsData(new SkinsData(rootStates, named));

		assertTrue(weapon.setSelectedSkin("gold"));
		assertEquals(2001, weapon.resolveCustomModelData(SkinState.SCOPE));
		// "gold" has no Reload or Default override of its own -> falls through to the root state/Default chain
		assertEquals(1000, weapon.resolveCustomModelData(SkinState.RELOAD));
		assertEquals(goldModel, weapon.resolveItemModel(SkinState.SCOPE));
	}

	@Test
	@DisplayName("a named skin's own Default covers an unconfigured state before falling through to root")
	void namedSkin_ownDefault_beatsRootFallthrough() {
		GunWeapon weapon = WeaponFixtures.gunWeapon(10, 1);
		weapon.setSkinsData(new SkinsData(states(SkinState.DEFAULT, 1000),
		                                  Map.of("gold", new SkinsData.NamedSkin(states(SkinState.DEFAULT, 2000), null))));

		assertTrue(weapon.setSelectedSkin("gold"));

		assertEquals(2000, weapon.resolveCustomModelData(SkinState.RELOAD));
		assertNull(weapon.resolveItemModel(SkinState.RELOAD)); // no Item_Model anywhere configured
	}

	@Test
	@DisplayName("setSelectedSkin rejects an unknown name and leaves the current selection untouched")
	void setSelectedSkin_unknownName_rejected() {
		GunWeapon weapon = WeaponFixtures.gunWeapon(10, 1);
		weapon.setSkinsData(new SkinsData(Map.of(), Map.of("gold", new SkinsData.NamedSkin(Map.of(), null))));

		assertFalse(weapon.setSelectedSkin("platinum"));
		assertNull(weapon.getSelectedSkin());
	}

	@Test
	@DisplayName("setSelectedSkin(null) clears the selection back to the root fallbacks")
	void setSelectedSkin_null_clearsSelection() {
		GunWeapon weapon = WeaponFixtures.gunWeapon(10, 1);
		weapon.setSkinsData(new SkinsData(Map.of(), Map.of("gold", new SkinsData.NamedSkin(Map.of(), null))));
		weapon.setSelectedSkin("gold");

		assertTrue(weapon.setSelectedSkin(null));
		assertNull(weapon.getSelectedSkin());
	}

	// --- applySkinRendering / clearCustomModelData (review finding 3) ---

	@Test
	@DisplayName("a resolved CMD of 0 clears a stale custom model data instead of leaving it stuck")
	void resolvedCmdZero_clearsStaleCustomModelData() throws Exception {
		// only Reload configured, no Default and no Information.Custom_Model_Data (fixture's is 0) - DEFAULT
		// resolves all the way through to 0.
		GunWeapon weapon = WeaponFixtures.gunWeapon(10, 1);
		weapon.setSkinsData(new SkinsData(states(SkinState.RELOAD, 1002), Map.of()));
		assertEquals(0, weapon.resolveCustomModelData(SkinState.DEFAULT));

		ItemStack stack = mock(ItemStack.class);
		ItemMeta  meta  = mock(ItemMeta.class);
		when(stack.getItemMeta()).thenReturn(meta);
		when(meta.hasCustomModelData()).thenReturn(true); // simulates a stale CMD left from a previous RELOAD state

		ItemBuilder builder = new ItemBuilder(stack);

		Method applySkinRendering = Weapon.class.getDeclaredMethod("applySkinRendering", ItemBuilder.class,
		                                                           SkinState.class);
		applySkinRendering.setAccessible(true);
		applySkinRendering.invoke(weapon, builder, SkinState.DEFAULT);

		verify(meta).setCustomModelData(null);
		verify(stack).setItemMeta(meta);
	}

	@Test
	@DisplayName("a resolved CMD of 0 with no stale CMD present never touches the item meta")
	void resolvedCmdZero_noStaleCmd_leavesMetaUntouched() throws Exception {
		GunWeapon weapon = WeaponFixtures.gunWeapon(10, 1);
		weapon.setSkinsData(new SkinsData(states(SkinState.RELOAD, 1002), Map.of()));

		ItemStack stack = mock(ItemStack.class);
		ItemMeta  meta  = mock(ItemMeta.class);
		when(stack.getItemMeta()).thenReturn(meta);
		when(meta.hasCustomModelData()).thenReturn(false);

		ItemBuilder builder = new ItemBuilder(stack);

		Method applySkinRendering = Weapon.class.getDeclaredMethod("applySkinRendering", ItemBuilder.class,
		                                                           SkinState.class);
		applySkinRendering.setAccessible(true);
		applySkinRendering.invoke(weapon, builder, SkinState.DEFAULT);

		verify(meta, never()).setCustomModelData(null);
		verify(stack, never()).setItemMeta(meta);
	}

}
