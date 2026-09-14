package org.luckyraven.bartizan.api.weapon.reload;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.ammo.Ammunition;
import org.luckyraven.bartizan.api.support.WeaponFixtures;
import org.luckyraven.bartizan.api.weapon.MeleeWeapon;
import org.luckyraven.bartizan.api.weapon.WeaponType;
import org.luckyraven.bartizan.api.weapon.dto.AmmunitionData;
import org.luckyraven.bartizan.api.weapon.dto.MeleeData;
import org.luckyraven.bartizan.api.weapon.dto.ReloadData;

import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers gate {@code HG}'s new {@link Reload} behaviour shared by every reload type: {@link
 * Reload#resolveAmmoType} (which of {@code Ammunition.Ammo_Type}/{@code Types} a reload run should consume) and
 * {@link Reload#unloadAmmoIfConfigured} ({@code Reload.Unload_Ammo_On_Reload}). Exercised through {@link
 * InstantReload} against a bare {@link MeleeWeapon} fixture — both methods live on the shared {@code Reload} base
 * class and don't care which concrete reload type or weapon category calls them.
 *
 * <p>{@code Ammunition} is mocked (rather than built from the real class) so {@code buildItem}'s NBT-tagging path
 * — which needs a live NBT-API provider outside a plain unit test — never runs; only the identity of which
 * configured {@code Ammunition} gets picked, and the plain {@code ItemStack} its stubbed {@code buildItem} hands
 * back, matter here.
 */
@DisplayName("Reload — resolveAmmoType / unloadAmmoIfConfigured")
class ReloadAmmoTypeTest {

	private Ammunition ammoA;
	private Ammunition ammoB;

	@BeforeEach
	void setUp() {
		ammoA = mockAmmo("ammo_a", Material.COAL);
		ammoB = mockAmmo("ammo_b", Material.IRON_INGOT);
	}

	@Test
	@DisplayName("resolveAmmoType: Ammo_Type: none (empty list) resolves to null")
	void resolveAmmoType_none_returnsNull() {
		InstantReload reload = reloadFor(weaponWith(new AmmunitionData(List.of(), 6, 1, 1)));

		assertNull(reload.resolveAmmoType(mock(PlayerInventory.class), mock(Player.class), 1));
	}

	@Test
	@DisplayName("resolveAmmoType: NPC path (null inventory) resolves to the first configured type")
	void resolveAmmoType_npcPath_returnsFirstConfigured() {
		InstantReload reload = reloadFor(weaponWith(new AmmunitionData(List.of(ammoA, ammoB), 6, 1, 1)));

		assertSame(ammoA, reload.resolveAmmoType(null, null, 1));
	}

	@Test
	@DisplayName("resolveAmmoType: prefers the first listed type the player actually carries")
	void resolveAmmoType_prefersFirstCarriedInListOrder() {
		InstantReload   reload    = reloadFor(weaponWith(new AmmunitionData(List.of(ammoA, ammoB), 6, 1, 1)));
		PlayerInventory inventory = mock(PlayerInventory.class);
		Player          player    = mock(Player.class);

		// player carries none of ammoA but plenty of ammoB
		when(inventory.containsAtLeast(argThat(matchesMaterial(Material.COAL)), anyInt())).thenReturn(false);
		when(inventory.containsAtLeast(argThat(matchesMaterial(Material.IRON_INGOT)), anyInt())).thenReturn(true);

		assertSame(ammoB, reload.resolveAmmoType(inventory, player, 1));
	}

	@Test
	@DisplayName("resolveAmmoType: probes with the player carrying the inventory (placeholder-bearing ammo names)")
	void resolveAmmoType_probesWithThePlayer() {
		InstantReload   reload    = reloadFor(weaponWith(new AmmunitionData(List.of(ammoA), 6, 1, 1)));
		PlayerInventory inventory = mock(PlayerInventory.class);
		Player          player    = mock(Player.class);
		when(inventory.containsAtLeast(any(ItemStack.class), anyInt())).thenReturn(true);

		reload.resolveAmmoType(inventory, player, 1);

		verify(ammoA).buildItem(player, 1);
	}

	@Test
	@DisplayName("resolveAmmoType: falls back to the first configured type when the player carries none of them")
	void resolveAmmoType_playerCarriesNone_fallsBackToFirstConfigured() {
		InstantReload   reload    = reloadFor(weaponWith(new AmmunitionData(List.of(ammoA, ammoB), 6, 1, 1)));
		PlayerInventory inventory = mock(PlayerInventory.class);
		Player          player    = mock(Player.class);
		when(inventory.containsAtLeast(any(ItemStack.class), anyInt())).thenReturn(false);

		assertSame(ammoA, reload.resolveAmmoType(inventory, player, 1));
	}

	@Test
	@DisplayName("unloadAmmoIfConfigured: no-op when Unload_Ammo_On_Reload is off")
	void unloadAmmoIfConfigured_flagOff_doesNothing() {
		MeleeWeapon weapon = weaponWith(reloadDataWith(false), new AmmunitionData(List.of(ammoA), 6, 1, 2));
		weapon.setCurrentMagCapacity(6);
		InstantReload   reload    = reloadFor(weapon);
		PlayerInventory inventory = mock(PlayerInventory.class);

		reload.unloadAmmoIfConfigured(inventory, mock(Player.class), true);

		verify(inventory, never()).addItem(any(ItemStack.class));
		assertEquals(6, weapon.getCurrentMagCapacity());
	}

	@Test
	@DisplayName("unloadAmmoIfConfigured: no-op for Ammo_Type: none — nothing to return")
	void unloadAmmoIfConfigured_none_doesNothing() {
		MeleeWeapon weapon = weaponWith(reloadDataWith(true), new AmmunitionData(List.of(), 6, 1, 2));
		weapon.setCurrentMagCapacity(6);
		InstantReload   reload    = reloadFor(weapon);
		PlayerInventory inventory = mock(PlayerInventory.class);

		reload.unloadAmmoIfConfigured(inventory, mock(Player.class), true);

		verify(inventory, never()).addItem(any(ItemStack.class));
		assertEquals(6, weapon.getCurrentMagCapacity());
	}

	@Test
	@DisplayName("unloadAmmoIfConfigured: skipped for NPCs (player == null)")
	void unloadAmmoIfConfigured_npc_doesNothing() {
		MeleeWeapon weapon = weaponWith(reloadDataWith(true), new AmmunitionData(List.of(ammoA), 6, 1, 2));
		weapon.setCurrentMagCapacity(6);
		InstantReload reload = reloadFor(weapon);

		reload.unloadAmmoIfConfigured(mock(PlayerInventory.class), null, true);

		assertEquals(6, weapon.getCurrentMagCapacity());
	}

	@Test
	@DisplayName("unloadAmmoIfConfigured: skipped when the magazine is already empty")
	void unloadAmmoIfConfigured_emptyMagazine_doesNothing() {
		MeleeWeapon weapon = weaponWith(reloadDataWith(true), new AmmunitionData(List.of(ammoA), 6, 1, 2));
		weapon.setCurrentMagCapacity(0);
		InstantReload   reload    = reloadFor(weapon);
		PlayerInventory inventory = mock(PlayerInventory.class);

		reload.unloadAmmoIfConfigured(inventory, mock(Player.class), true);

		verify(inventory, never()).addItem(any(ItemStack.class));
	}

	@Test
	@DisplayName("unloadAmmoIfConfigured: returns floor(currentMag / restore) items and zeroes the magazine")
	void unloadAmmoIfConfigured_returnsRoundsAndZeroesMagazine() {
		MeleeWeapon weapon = weaponWith(reloadDataWith(true), new AmmunitionData(List.of(ammoA), 6, 1, 2));
		weapon.setCurrentMagCapacity(5); // floor(5 / 2) = 2 rounds back
		InstantReload   reload    = reloadFor(weapon);
		PlayerInventory inventory = mock(PlayerInventory.class);
		Player          player    = mock(Player.class);
		when(inventory.addItem(any(ItemStack.class))).thenReturn(new HashMap<>());

		reload.unloadAmmoIfConfigured(inventory, player, true);

		verify(inventory)
				.addItem(argThat((ItemStack stack) -> stack.getType() == Material.COAL && stack.getAmount() == 2));
		assertEquals(0, weapon.getCurrentMagCapacity());
	}

	@Test
	@DisplayName("unloadAmmoIfConfigured: creative (removeAmmunition == false) zeroes the magazine but hands "
	             + "back nothing")
	void unloadAmmoIfConfigured_creative_zeroesMagazineWithoutReturningItems() {
		MeleeWeapon weapon = weaponWith(reloadDataWith(true), new AmmunitionData(List.of(ammoA), 6, 1, 2));
		weapon.setCurrentMagCapacity(5); // would return floor(5 / 2) = 2 rounds if removeAmmunition were true
		InstantReload   reload    = reloadFor(weapon);
		PlayerInventory inventory = mock(PlayerInventory.class);
		Player          player    = mock(Player.class);

		reload.unloadAmmoIfConfigured(inventory, player, false);

		verify(inventory, never()).addItem(any(ItemStack.class));
		verify(player, never()).getWorld();
		assertEquals(0, weapon.getCurrentMagCapacity());
	}

	// ---------------------------------------------------------------------------------------------------------------

	private static Ammunition mockAmmo(String name, Material material) {
		Ammunition ammo = mock(Ammunition.class);
		when(ammo.getName()).thenReturn(name);
		when(ammo.buildItem(any(), anyInt())).thenAnswer(inv -> new ItemStack(material, inv.getArgument(1)));
		when(ammo.buildItem(any(Player.class))).thenAnswer(inv -> new ItemStack(material));
		return ammo;
	}

	private static org.mockito.ArgumentMatcher<ItemStack> matchesMaterial(Material material) {
		return stack -> stack != null && stack.getType() == material;
	}

	private static ReloadData reloadDataWith(boolean unloadOnReload) {
		return ReloadData.builder().cooldown(20).type(ReloadType.getType("instant"))
		                 .unloadAmmoOnReload(unloadOnReload).build();
	}

	private static MeleeWeapon weaponWith(AmmunitionData ammunitionData) {
		return weaponWith(WeaponFixtures.instantReload(), ammunitionData);
	}

	private static MeleeWeapon weaponWith(ReloadData reloadData, AmmunitionData ammunitionData) {
		MeleeData melee = new MeleeData(8.0, 3.0, 10, 0.5);
		return new MeleeWeapon(UUID.randomUUID(), "test_knife", "&fTest Knife", WeaponType.MELEE, Material.IRON_HOE,
		                       0, (short) 50, List.of(), false, null, melee, reloadData, ammunitionData);
	}

	private static InstantReload reloadFor(MeleeWeapon weapon) {
		return new InstantReload(weapon, weapon.getAmmunitionData().getAmmoType());
	}

}
