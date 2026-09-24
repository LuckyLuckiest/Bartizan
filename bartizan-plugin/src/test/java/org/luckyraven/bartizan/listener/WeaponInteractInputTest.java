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
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.combat.CombatEligibility;
import org.luckyraven.bartizan.api.raytrace.WeaponRaytracer;
import org.luckyraven.bartizan.api.weapon.modifiers.BlockDamageManager;
import org.luckyraven.bartizan.effect.EffectRunner;
import org.luckyraven.bartizan.fire.PluginFireRegistry;
import org.luckyraven.bartizan.scope.SpyglassScopeTask;
import org.luckyraven.bartizan.status.StatusEffectService;
import org.luckyraven.bartizan.weapon.WeaponService;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Click routing in {@link WeaponInteract} (0.5.1 docket, interact cluster batch 2). The Bukkit scheduler is a mock,
 * so every timer the listener starts is scheduled but never runs.
 */
@DisplayName("WeaponInteract - click routing")
class WeaponInteractInputTest {

	private final Player          player        = mock(Player.class);
	private final PlayerInventory inventory     = mock(PlayerInventory.class);
	private final WeaponService   weaponService = mock(WeaponService.class);
	private final EffectRunner    effectRunner  = mock(EffectRunner.class);
	private final BukkitScheduler scheduler     = mock(BukkitScheduler.class);
	private final ItemStack       item          = mock(ItemStack.class);

	private MockedStatic<Bukkit> bukkit;
	private WeaponInteract       listener;

	@BeforeEach
	void setUp() {
		when(player.getInventory()).thenReturn(inventory);
		when(inventory.getItemInMainHand()).thenReturn(item);

		CombatEligibility eligibility = mock(CombatEligibility.class);
		when(eligibility.canBeHit(player)).thenReturn(true);

		bukkit = mockStatic(Bukkit.class);
		bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
		bukkit.when(Bukkit::getPluginManager).thenReturn(mock(PluginManager.class));

		listener = new WeaponInteract(mock(JavaPlugin.class), weaponService, mock(WeaponRaytracer.class),
		                              mock(PluginFireRegistry.class), eligibility, effectRunner,
		                              mock(BlockDamageManager.class), mock(StatusEffectService.class),
		                              mock(SpyglassScopeTask.class));
	}

	@AfterEach
	void tearDown() {
		bukkit.close();
	}

	private PlayerInteractEvent click(Action action) {
		return new PlayerInteractEvent(player, action, item, null, null, EquipmentSlot.HAND);
	}

	// BZ-EV-16

	@Test
	@DisplayName("a weapon-tagged item that no longer resolves still has its vanilla use denied")
	void unresolvedWeaponItem_vanillaUseDenied() {
		when(weaponService.getHeldWeaponName(item)).thenReturn("removed_crossbow");

		PlayerInteractEvent event = click(Action.RIGHT_CLICK_AIR);
		listener.onPlayerInteract(event);

		assertEquals(Event.Result.DENY, event.useItemInHand());
	}

	@Test
	@DisplayName("a plain item keeps its vanilla use")
	void plainItem_vanillaUseUntouched() {
		PlayerInteractEvent event = click(Action.RIGHT_CLICK_AIR);
		listener.onPlayerInteract(event);

		assertEquals(Event.Result.DEFAULT, event.useItemInHand());
	}

}
