package org.luckyraven.bartizan.api.weapon.reload;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.ammo.Ammunition;
import org.luckyraven.bartizan.api.event.WeaponReloadCompleteEvent;
import org.luckyraven.bartizan.api.weapon.MeleeWeapon;
import org.luckyraven.bartizan.api.weapon.WeaponType;
import org.luckyraven.bartizan.api.weapon.dto.AmmunitionData;
import org.luckyraven.bartizan.api.weapon.dto.MeleeData;
import org.luckyraven.bartizan.api.weapon.dto.ReloadData;
import org.luckyraven.bartizan.api.weapon.dto.SoundData;
import org.luckyraven.keystone.sound.SoundEffect;
import org.luckyraven.keystone.timer.SequenceTimer;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BZ-WM-15: the reload's insert commit took the ammo even when the weapon was no longer a top-level inventory item
 * (picked onto the cursor, into the crafting grid, an ender chest or a bundle). The fill could not be written back,
 * so the next lookup re-synced the old magazine from the item and the ammo was gone. The commit now interrupts
 * first, consuming nothing. Timer callbacks are captured from a mocked {@link SequenceTimer} and run by hand, as in
 * {@link ReloadStageResumeTest}; an empty {@code getContents()} is the "not in the inventory" case.
 */
@DisplayName("Reload - no commit while the weapon is outside the player inventory (BZ-WM-15)")
class ReloadWeaponGoneTest {

	@Test
	@DisplayName("instant: the insert commit interrupts and keeps the magazine item when the weapon is gone")
	void instant_weaponGone_consumesNothing() {
		Ammunition ammo     = mock(Ammunition.class);
		ItemStack  magazine = mock(ItemStack.class);
		when(ammo.buildItem(any(), anyInt())).thenReturn(magazine);

		MeleeWeapon   weapon = weapon(ReloadData.builder().cooldown(4).type(ReloadType.getType("instant")).build(),
		                              ammo);
		InstantReload reload = new InstantReload(weapon, ammo);

		PlayerInventory inventory = carryingAmmo();
		Player          player    = playerWith(inventory);

		List<Event> events = run(reload, player, 3); // anchor, open, insert

		verify(inventory, never()).removeItem(any(ItemStack[].class));
		assertEquals(0, weapon.getCurrentMagCapacity(), "no rounds without the ammo, no ammo taken either");
		assertFalse(reload.isReloading());
		assertTrue(interruptedCompletion(events), "the reload ends as interrupted");
	}

	@Test
	@DisplayName("numbered: a shell insertion interrupts and keeps the ammo when the weapon is gone")
	void numbered_weaponGone_consumesNothing() {
		Ammunition ammo      = mock(Ammunition.class);
		ItemStack  ammoStack = mock(ItemStack.class);
		when(ammoStack.getType()).thenReturn(Material.IRON_NUGGET);
		when(ammoStack.getAmount()).thenReturn(6);
		when(ammo.buildItem(any(), anyInt())).thenReturn(ammoStack);
		when(ammo.buildItem(anyInt())).thenReturn(ammoStack);

		MeleeWeapon    weapon = weapon(ReloadData.builder().cooldown(1).type(ReloadType.getType("num")).build(),
		                               ammo);
		NumberedReload reload = new NumberedReload(weapon, ammo, 1);

		PlayerInventory inventory = carryingAmmo();
		when(inventory.getSize()).thenReturn(1);
		when(inventory.getItem(0)).thenReturn(ammoStack);
		Player player = playerWith(inventory);

		List<Event> events;
		try (MockedStatic<Ammunition> ammunition = mockStatic(Ammunition.class)) {
			ammunition.when(() -> Ammunition.isAmmunition(ammoStack)).thenReturn(true);

			events = run(reload, player, 2); // anchor, first shell
		}

		verify(inventory, never()).removeItem(any(ItemStack[].class));
		assertEquals(0, weapon.getCurrentMagCapacity());
		assertFalse(reload.isReloading());
		assertTrue(interruptedCompletion(events));
	}

	/**
	 * Starts {@code reload} and runs its first {@code callbacks} captured timer callbacks in order.
	 */
	private static List<Event> run(Reload reload, Player player, int callbacks) {
		List<Consumer<SequenceTimer>> captured = new ArrayList<>();
		List<Event>                   events   = new ArrayList<>();

		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
		     MockedStatic<SoundEffect> soundEffect = mockStatic(SoundEffect.class);
		     MockedConstruction<SequenceTimer> timerCtor = mockConstruction(SequenceTimer.class,
				     (timerMock, context) -> doAnswer(invocation -> {
					     captured.add(invocation.getArgument(1));
					     return null;
				     }).when(timerMock).addIntervalTaskPair(anyLong(), any()))) {

			PluginManager pluginManager = mock(PluginManager.class);
			doAnswer(invocation -> {
				events.add(invocation.getArgument(0));
				return null;
			}).when(pluginManager).callEvent(any());
			bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);

			reload.reload(mock(JavaPlugin.class), player, true);

			SequenceTimer timerMock = timerCtor.constructed().get(0);
			for (int i = 0; i < callbacks; i++) {
				captured.get(i).accept(timerMock);
			}
		}

		return events;
	}

	private static boolean interruptedCompletion(List<Event> events) {
		return events.stream()
		             .filter(WeaponReloadCompleteEvent.class::isInstance)
		             .map(WeaponReloadCompleteEvent.class::cast)
		             .anyMatch(WeaponReloadCompleteEvent::isInterrupted);
	}

	private static PlayerInventory carryingAmmo() {
		PlayerInventory inventory = mock(PlayerInventory.class);
		when(inventory.containsAtLeast(any(), anyInt())).thenReturn(true);
		// the weapon is on the cursor / in a container: in none of the player inventory's slots
		when(inventory.getContents()).thenReturn(new ItemStack[0]);
		return inventory;
	}

	private static Player playerWith(PlayerInventory inventory) {
		Player player = mock(Player.class);
		when(player.getInventory()).thenReturn(inventory);
		return player;
	}

	private static MeleeWeapon weapon(ReloadData reloadData, Ammunition ammo) {
		MeleeData   melee  = new MeleeData(8.0, 3.0, 10, 0.5);
		MeleeWeapon weapon = new MeleeWeapon(UUID.randomUUID(), "test_knife", "&fTest Knife", WeaponType.MELEE,
		                                     Material.IRON_HOE, 0, (short) 50, List.of(), false, null, melee,
		                                     reloadData, new AmmunitionData(ammo, 6, 1, 1));
		weapon.setSoundData(new SoundData());
		weapon.setCurrentMagCapacity(0);
		return weapon;
	}

}
