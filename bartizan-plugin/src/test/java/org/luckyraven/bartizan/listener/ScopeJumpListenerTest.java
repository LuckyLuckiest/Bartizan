package org.luckyraven.bartizan.listener;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.api.weapon.dto.ScopeData;
import org.luckyraven.bartizan.weapon.WeaponService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers {@link ScopeJumpListener#onPlayerMove}: the upward-move Y snap applies while the held weapon is scoped,
 * and is skipped while it is reloading (the auto-scope-as-reload-animation case - blocking Y movement there would
 * prevent external knockback from working, per the class javadoc).
 */
@DisplayName("ScopeJumpListener.onPlayerMove")
class ScopeJumpListenerTest {

	private final WeaponService     weaponService = mock(WeaponService.class);
	private final ScopeJumpListener listener      = new ScopeJumpListener(weaponService);
	private final World             world         = mock(World.class);

	private Player player;

	@Test
	@DisplayName("scoped and moving up: the destination Y is snapped back to the source Y")
	void scoped_movingUp_snapsYBack() {
		Weapon    weapon    = weaponHeld();
		ScopeData scopeData = new ScopeData();
		scopeData.setScoped(true);
		when(weapon.getScopeData()).thenReturn(scopeData);
		when(weapon.isReloading()).thenReturn(false);

		PlayerMoveEvent event = moveEvent(64.0, 65.0);

		listener.onPlayerMove(event);

		assertEquals(64.0, event.getTo().getY(), "the jump must be blocked while scoped");
	}

	@Test
	@DisplayName("reloading (scope auto-applied as an animation effect): Y is left untouched")
	void reloading_movingUp_doesNotSnap() {
		Weapon    weapon    = weaponHeld();
		ScopeData scopeData = new ScopeData();
		scopeData.setScoped(true);
		when(weapon.getScopeData()).thenReturn(scopeData);
		when(weapon.isReloading()).thenReturn(true);

		PlayerMoveEvent event = moveEvent(64.0, 65.0);

		listener.onPlayerMove(event);

		assertEquals(65.0, event.getTo().getY(), "reload's auto-scope must not block Y movement");
	}

	private Weapon weaponHeld() {
		ItemStack       item      = mock(ItemStack.class);
		Player          player    = mock(Player.class);
		PlayerInventory inventory = mock(PlayerInventory.class);
		when(player.getInventory()).thenReturn(inventory);
		when(inventory.getItemInMainHand()).thenReturn(item);

		Weapon weapon = mock(Weapon.class);
		when(weaponService.validateAndGetWeapon(player, item)).thenReturn(weapon);

		this.player = player;
		return weapon;
	}

	private PlayerMoveEvent moveEvent(double fromY, double toY) {
		Location from = new Location(world, 0, fromY, 0);
		Location to   = new Location(world, 0, toY, 0);
		return new PlayerMoveEvent(player, from, to);
	}

}
