package org.luckyraven.bartizan.command;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.api.weapon.WeaponType;
import org.luckyraven.bartizan.weapon.WeaponManager;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BZ-CM-05: {@code /bartizan weapon give|get <weapon> <amount>} minted ONE registered weapon and stamped its uuid
 * onto every stack, so N physical rifles shared one live instance - reload, scope and press state followed whichever
 * of them was held. Every non-throwable stack must be its own unregistered transient weapon.
 */
@DisplayName("WeaponGiveHelper - one weapon identity per given item (BZ-CM-05)")
class WeaponGiveHelperTest {

	@Test
	@DisplayName("a gun given N times becomes N single items, each from its own transient weapon, none registered")
	void give_gun_eachItemIsItsOwnWeapon() {
		WeaponManager manager  = managerBuilding(WeaponType.GUN, 64); // stackable material, e.g. BLAZE_ROD
		Player        receiver = receiver();

		assertTrue(WeaponGiveHelper.give(manager, receiver, "flamethrower", 3));

		ItemStack[] given = givenItems(receiver);
		assertEquals(3, given.length);
		assertEquals(3, Arrays.stream(given).distinct().count());
		for (ItemStack item : given) verify(item).setAmount(1);

		verify(manager, atLeast(3)).createTransientWeapon("flamethrower");
		verify(manager, never()).getWeapon(any(), any(), anyString(), anyBoolean());
	}

	@Test
	@DisplayName("throwables keep stacking up to the material's max stack size")
	void give_throwable_stillStacks() {
		WeaponManager manager  = managerBuilding(WeaponType.THROWABLE, 16);
		Player        receiver = receiver();

		assertTrue(WeaponGiveHelper.give(manager, receiver, "grenade", 20));

		ItemStack[] given = givenItems(receiver);
		assertEquals(2, given.length);
		verify(given[0]).setAmount(16);
		verify(given[1]).setAmount(4);
	}

	private static WeaponManager managerBuilding(WeaponType category, int maxStackSize) {
		WeaponManager manager = mock(WeaponManager.class);
		when(manager.getWeaponTemplate(anyString())).thenReturn(mock(Weapon.class));
		when(manager.createTransientWeapon(anyString())).thenAnswer(invocation -> {
			Weapon    weapon = mock(Weapon.class);
			ItemStack item   = mock(ItemStack.class);
			when(item.getMaxStackSize()).thenReturn(maxStackSize);
			when(weapon.getCategory()).thenReturn(category);
			when(weapon.buildItem(any(Player.class))).thenReturn(item);
			return weapon;
		});
		// the old path: one registered weapon for every stack
		when(manager.getWeapon(any(), any(), anyString(), anyBoolean())).thenAnswer(
				invocation -> manager.createTransientWeapon(invocation.getArgument(2)));
		return manager;
	}

	private static Player receiver() {
		Player          player    = mock(Player.class);
		PlayerInventory inventory = mock(PlayerInventory.class);
		when(player.getInventory()).thenReturn(inventory);
		when(inventory.addItem(any(ItemStack[].class))).thenReturn(new HashMap<>());
		return player;
	}

	private static ItemStack[] givenItems(Player receiver) {
		ArgumentCaptor<ItemStack[]> captor = ArgumentCaptor.forClass(ItemStack[].class);
		verify(receiver.getInventory()).addItem(captor.capture());
		return Arrays.stream(captor.getValue()).filter(Objects::nonNull).toArray(ItemStack[]::new);
	}

}
