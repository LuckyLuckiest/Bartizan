package org.luckyraven.bartizan.listener.reload;

import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.event.WeaponReloadCompleteEvent;
import org.luckyraven.bartizan.api.event.WeaponReloadStageEvent;
import org.luckyraven.bartizan.api.event.WeaponReloadStartEvent;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.api.weapon.dto.EffectHook;
import org.luckyraven.bartizan.effect.EffectContext;
import org.luckyraven.bartizan.effect.EffectRunner;
import org.luckyraven.bartizan.weapon.WeaponService;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers gate {@code HA} follow-up item E: {@link WeaponReloadListener#onReloadEnd} skips {@code ON_RELOAD_END} for
 * an interrupted (swap-cancelled) {@link WeaponReloadCompleteEvent}, and {@link WeaponReloadListener#onHeldSlotChange}
 * only runs {@code ON_RELOAD_CANCEL} when the weapon was actually reloading at the moment of the swap.
 */
@DisplayName("WeaponReloadListener")
class WeaponReloadListenerTest {

	@Test
	@DisplayName("onReloadEnd: an interrupted complete event does not run ON_RELOAD_END")
	void onReloadEnd_interrupted_doesNotRunOnReloadEnd() {
		EffectRunner         effectRunner = mock(EffectRunner.class);
		WeaponReloadListener listener     = new WeaponReloadListener(mock(WeaponService.class), effectRunner);

		Weapon weapon = mock(Weapon.class);
		Player player = mock(Player.class);
		when(player.getUniqueId()).thenReturn(UUID.randomUUID());

		WeaponReloadCompleteEvent event = mock(WeaponReloadCompleteEvent.class);
		when(event.getWeapon()).thenReturn(weapon);
		when(event.getPlayer()).thenReturn(player);
		when(event.isInterrupted()).thenReturn(true);

		listener.onReloadEnd(event);

		verify(effectRunner, never()).run(any(), eq(EffectHook.ON_RELOAD_END), any());
	}

	@Test
	@DisplayName("onReloadEnd: a non-interrupted complete event runs ON_RELOAD_END")
	void onReloadEnd_notInterrupted_runsOnReloadEndHook() {
		EffectRunner         effectRunner = mock(EffectRunner.class);
		WeaponReloadListener listener     = new WeaponReloadListener(mock(WeaponService.class), effectRunner);

		Weapon weapon = mock(Weapon.class);
		Player player = mock(Player.class);
		when(player.getUniqueId()).thenReturn(UUID.randomUUID());

		WeaponReloadCompleteEvent event = mock(WeaponReloadCompleteEvent.class);
		when(event.getWeapon()).thenReturn(weapon);
		when(event.getPlayer()).thenReturn(player);
		when(event.isInterrupted()).thenReturn(false);

		listener.onReloadEnd(event);

		verify(effectRunner).run(eq(weapon), eq(EffectHook.ON_RELOAD_END), any(EffectContext.class));
	}

	@Test
	@DisplayName("onReloadStage: runs ON_RELOAD_STAGE (weapons-roadmap.md gate HO)")
	void onReloadStage_runsOnReloadStageHook() {
		EffectRunner         effectRunner = mock(EffectRunner.class);
		WeaponReloadListener listener     = new WeaponReloadListener(mock(WeaponService.class), effectRunner);

		Weapon weapon = mock(Weapon.class);
		Player player = mock(Player.class);

		WeaponReloadStageEvent event = mock(WeaponReloadStageEvent.class);
		when(event.getWeapon()).thenReturn(weapon);
		when(event.getPlayer()).thenReturn(player);

		listener.onReloadStage(event);

		verify(effectRunner).run(eq(weapon), eq(EffectHook.ON_RELOAD_STAGE), any(EffectContext.class));
	}

	@Test
	@DisplayName("onHeldSlotChange: weapon was not reloading -> ON_RELOAD_CANCEL never runs")
	void onHeldSlotChange_notReloading_neverRunsOnReloadCancel() {
		WeaponService        weaponService = mock(WeaponService.class);
		EffectRunner         effectRunner  = mock(EffectRunner.class);
		WeaponReloadListener listener      = new WeaponReloadListener(weaponService, effectRunner);

		Player          player    = mock(Player.class);
		PlayerInventory inventory = mock(PlayerInventory.class);
		ItemStack       heldItem  = mock(ItemStack.class);
		when(player.getInventory()).thenReturn(inventory);
		when(inventory.getItemInMainHand()).thenReturn(heldItem);

		Weapon weapon = mock(Weapon.class);
		when(weapon.isReloading()).thenReturn(false);
		when(weaponService.validateAndGetWeapon(player, heldItem)).thenReturn(weapon);

		// The listener only inspects reloadingPlayers -> weapon lookup path once the player is tracked as reloading.
		markReloading(listener, player);

		PlayerItemHeldEvent event = mock(PlayerItemHeldEvent.class);
		when(event.getPlayer()).thenReturn(player);

		listener.onHeldSlotChange(event);

		verify(weapon).stopReloading();
		verify(effectRunner, never()).run(any(), eq(EffectHook.ON_RELOAD_CANCEL), any());
	}

	@Test
	@DisplayName("onHeldSlotChange: weapon was reloading -> ON_RELOAD_CANCEL runs")
	void onHeldSlotChange_reloading_runsOnReloadCancel() {
		WeaponService        weaponService = mock(WeaponService.class);
		EffectRunner         effectRunner  = mock(EffectRunner.class);
		WeaponReloadListener listener      = new WeaponReloadListener(weaponService, effectRunner);

		Player          player    = mock(Player.class);
		PlayerInventory inventory = mock(PlayerInventory.class);
		ItemStack       heldItem  = mock(ItemStack.class);
		when(player.getInventory()).thenReturn(inventory);
		when(inventory.getItemInMainHand()).thenReturn(heldItem);

		Weapon weapon = mock(Weapon.class);
		when(weapon.isReloading()).thenReturn(true);
		when(weaponService.validateAndGetWeapon(player, heldItem)).thenReturn(weapon);

		markReloading(listener, player);

		PlayerItemHeldEvent event = mock(PlayerItemHeldEvent.class);
		when(event.getPlayer()).thenReturn(player);

		listener.onHeldSlotChange(event);

		verify(weapon).stopReloading();
		verify(effectRunner).run(eq(weapon), eq(EffectHook.ON_RELOAD_CANCEL), any(EffectContext.class));
	}

	/**
	 * {@code onHeldSlotChange} early-returns unless {@code reloadingPlayers} already tracks the player — the same
	 * membership {@link WeaponReloadListener#onReloadStart} records. Routing through the real start event keeps
	 * this test from reaching into the listener's private state.
	 */
	private void markReloading(WeaponReloadListener listener, Player player) {
		WeaponReloadStartEvent startEvent = mock(WeaponReloadStartEvent.class);
		when(startEvent.getPlayer()).thenReturn(player);
		when(startEvent.getWeapon()).thenReturn(mock(Weapon.class));
		listener.onReloadStart(startEvent);
	}

}
