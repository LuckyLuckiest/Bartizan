package org.luckyraven.bartizan.listener;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.combat.CombatEligibility;
import org.luckyraven.bartizan.api.raytrace.WeaponRaytracer;
import org.luckyraven.bartizan.api.support.WeaponFixtures;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.api.weapon.modifiers.BlockDamageManager;
import org.luckyraven.bartizan.effect.EffectRunner;
import org.luckyraven.bartizan.fire.PluginFireRegistry;
import org.luckyraven.bartizan.scope.SpyglassScopeTask;
import org.luckyraven.bartizan.status.StatusEffectService;
import org.luckyraven.bartizan.weapon.WeaponService;
import org.luckyraven.bartizan.weapon.action.GunFireDispatcher;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * BZ-EV-11 / BZ-EV-12: an {@code OFF_HAND} right click never reaches a weapon action. It threw an off-hand grenade
 * while {@code ThrowableAction} took one item from the main hand (so the off-hand stack never shrank), and fired an
 * off-hand gun on the same click as the main-hand one. The vanilla item use is still denied.
 */
@DisplayName("WeaponInteract - off-hand uses are inert (BZ-EV-11, BZ-EV-12)")
class WeaponInteractOffHandTest {

	@Test
	@DisplayName("an off-hand grenade is neither thrown nor consumed, and the vanilla use stays denied")
	void offHandThrowable_notThrown() {
		assertOffHandUseInert(WeaponFixtures.throwableWeapon(1));
	}

	@Test
	@DisplayName("an off-hand gun does not fire")
	void offHandGun_doesNotFire() {
		assertOffHandUseInert(WeaponFixtures.gunWeapon(30, 1));
	}

	private static void assertOffHandUseInert(Weapon weapon) {
		ItemStack       offHandItem = mock(ItemStack.class);
		PlayerInventory inventory   = mock(PlayerInventory.class);
		when(inventory.getItemInOffHand()).thenReturn(offHandItem);

		Player player = mock(Player.class);
		when(player.getInventory()).thenReturn(inventory);

		WeaponService weaponService = mock(WeaponService.class);
		when(weaponService.validateAndGetWeapon(player, offHandItem)).thenReturn(weapon);

		CombatEligibility eligibility = mock(CombatEligibility.class);
		when(eligibility.canBeHit(player)).thenReturn(true);

		WeaponInteract listener = new WeaponInteract(mock(JavaPlugin.class), weaponService,
		                                             mock(WeaponRaytracer.class), mock(PluginFireRegistry.class),
		                                             eligibility, mock(EffectRunner.class),
		                                             mock(BlockDamageManager.class), mock(StatusEffectService.class),
		                                             mock(SpyglassScopeTask.class));

		PlayerInteractEvent event = new PlayerInteractEvent(player, Action.RIGHT_CLICK_AIR, offHandItem, null,
		                                                    null, EquipmentSlot.OFF_HAND);

		PluginManager pluginManager = mock(PluginManager.class);
		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
			bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);

			listener.onPlayerInteract(event);
		} catch (RuntimeException ignored) {
			// the pre-fix code runs on into scheduler/raytrace calls a unit test cannot serve; the assertions
			// below still see whether it got as far as firing.
		}

		assertEquals(Event.Result.DENY, event.useItemInHand());
		// every throw/fire path arms the weapon's fire-rate gate before it acts
		assertFalse(GunFireDispatcher.isLocked(weapon.getUuid()), "the off-hand weapon must not reach its action");
		verify(pluginManager, never()).callEvent(any());
		verify(inventory, never()).setItemInMainHand(any());
		verifyNoInteractions(offHandItem);
	}

}
