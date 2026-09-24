package org.luckyraven.bartizan.listener;

import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.combat.CombatEligibility;
import org.luckyraven.bartizan.api.raytrace.WeaponRaytracer;
import org.luckyraven.bartizan.api.support.WeaponFixtures;
import org.luckyraven.bartizan.api.weapon.ThrowableWeapon;
import org.luckyraven.bartizan.api.weapon.modifiers.BlockDamageManager;
import org.luckyraven.bartizan.effect.EffectRunner;
import org.luckyraven.bartizan.fire.PluginFireRegistry;
import org.luckyraven.bartizan.scope.SpyglassScopeTask;
import org.luckyraven.bartizan.status.StatusEffectService;
import org.luckyraven.bartizan.weapon.WeaponService;
import org.luckyraven.bartizan.weapon.action.ThrowableAction;
import org.luckyraven.keystone.timer.RepeatingTimer;
import org.mockito.MockedConstruction;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

/**
 * BZ-WM-06: every throwable of a type carries one deterministic uuid (so the items stack), and the press-lock gates
 * were keyed by that uuid alone - one player's grenade throw press-locked every other player holding that grenade
 * type until the watchdog cleared.
 */
@DisplayName("WeaponInteract - throwable press lock is per player (BZ-WM-06)")
class WeaponInteractThrowablePressTest {

	@Test
	@DisplayName("two players throwing the same grenade type in the same tick both throw")
	void throwablePress_samePressKeyAcrossPlayers_doesNotLockOthers() {
		ThrowableWeapon grenade       = WeaponFixtures.throwableWeapon(1);
		WeaponService   weaponService = mock(WeaponService.class);
		when(weaponService.validateAndGetWeapon(any(), any())).thenReturn(grenade);

		CombatEligibility eligibility = mock(CombatEligibility.class);
		when(eligibility.canBeHit(any())).thenReturn(true);

		WeaponInteract interact = new WeaponInteract(mock(JavaPlugin.class), weaponService,
		                                             mock(WeaponRaytracer.class), mock(PluginFireRegistry.class),
		                                             eligibility, mock(EffectRunner.class),
		                                             mock(BlockDamageManager.class), mock(StatusEffectService.class),
		                                             mock(SpyglassScopeTask.class));

		try (MockedConstruction<RepeatingTimer> ignoredTimers = mockConstruction(RepeatingTimer.class);
		     MockedConstruction<ThrowableAction> throwsMade = mockConstruction(ThrowableAction.class)) {
			interact.onPlayerInteract(rightClick(player()));
			interact.onPlayerInteract(rightClick(player()));

			assertEquals(2, throwsMade.constructed().size(), "the second player's throw was press-locked");
		}
	}

	@Test
	@DisplayName("the same player's held right-click still throws only once per press")
	void throwablePress_samePlayerHeld_stillGated() {
		ThrowableWeapon grenade       = WeaponFixtures.throwableWeapon(1);
		WeaponService   weaponService = mock(WeaponService.class);
		when(weaponService.validateAndGetWeapon(any(), any())).thenReturn(grenade);

		CombatEligibility eligibility = mock(CombatEligibility.class);
		when(eligibility.canBeHit(any())).thenReturn(true);

		WeaponInteract interact = new WeaponInteract(mock(JavaPlugin.class), weaponService,
		                                             mock(WeaponRaytracer.class), mock(PluginFireRegistry.class),
		                                             eligibility, mock(EffectRunner.class),
		                                             mock(BlockDamageManager.class), mock(StatusEffectService.class),
		                                             mock(SpyglassScopeTask.class));

		Player thrower = player();
		try (MockedConstruction<RepeatingTimer> ignoredTimers = mockConstruction(RepeatingTimer.class);
		     MockedConstruction<ThrowableAction> throwsMade = mockConstruction(ThrowableAction.class)) {
			interact.onPlayerInteract(rightClick(thrower));
			interact.onPlayerInteract(rightClick(thrower));

			assertEquals(1, throwsMade.constructed().size());
		}
	}

	private static Player player() {
		Player player = mock(Player.class);
		when(player.getUniqueId()).thenReturn(UUID.randomUUID());
		return player;
	}

	private static PlayerInteractEvent rightClick(Player player) {
		PlayerInteractEvent event = mock(PlayerInteractEvent.class);
		when(event.getPlayer()).thenReturn(player);
		when(event.getItem()).thenReturn(mock(ItemStack.class));
		when(event.getAction()).thenReturn(Action.RIGHT_CLICK_AIR);
		return event;
	}

}
