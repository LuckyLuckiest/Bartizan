package org.luckyraven.bartizan.listener;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
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
import org.luckyraven.bartizan.api.support.WeaponFixtures;
import org.luckyraven.bartizan.api.weapon.BiologicalWeapon;
import org.luckyraven.bartizan.api.weapon.GunWeapon;
import org.luckyraven.bartizan.api.weapon.IncendiaryWeapon;
import org.luckyraven.bartizan.api.weapon.SelectiveFire;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.weapon.action.ChargeController;
import org.luckyraven.bartizan.weapon.action.FullAutoTask;
import org.luckyraven.bartizan.weapon.action.IncendiaryAction;
import org.luckyraven.bartizan.api.weapon.MeleeWeapon;
import org.luckyraven.bartizan.weapon.action.MeleeAction;
import org.luckyraven.bartizan.api.weapon.dto.DurabilityData;
import org.luckyraven.bartizan.api.weapon.dto.EffectHook;
import org.luckyraven.bartizan.api.weapon.dto.HandlingData;
import org.luckyraven.bartizan.api.weapon.dto.ScopeData;
import org.luckyraven.bartizan.weapon.action.GunAction;
import org.luckyraven.bartizan.weapon.action.GunFireDispatcher;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
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

	// BZ-EV-14

	private GunWeapon gun(HandlingData.Trigger trigger) {
		GunWeapon gun      = WeaponFixtures.gunWeapon(30, 1);
		HandlingData handling = new HandlingData();
		handling.setTrigger(trigger);
		gun.setHandlingData(handling);
		gun.setCurrentMagCapacity(30);
		DurabilityData durability = new DurabilityData();
		durability.setConsumeOnTime(-1);
		gun.setDurabilityData(durability);
		when(weaponService.isWeapon(item)).thenReturn(true);
		when(weaponService.validateAndGetWeapon(player, item)).thenReturn(gun);
		return gun;
	}

	@Test
	@DisplayName("right-clicking an entity with a left_click-trigger gun does not fire it")
	void entityRightClick_leftClickTriggerGun_doesNotFire() {
		GunWeapon gun = gun(HandlingData.Trigger.LEFT_CLICK);

		PlayerInteractEntityEvent event = new PlayerInteractEntityEvent(player, mock(Entity.class));
		try (MockedConstruction<GunAction> shots = mockConstruction(GunAction.class)) {
			listener.onPlayerInteractWithEntity(event);

			assertTrue(shots.constructed().isEmpty());
		}
		assertFalse(GunFireDispatcher.isLocked(gun.getUuid()));
		assertTrue(event.isCancelled());
	}

	@Test
	@DisplayName("right-clicking an entity with a right_click-trigger gun still fires it")
	void entityRightClick_rightClickTriggerGun_fires() {
		gun(HandlingData.Trigger.RIGHT_CLICK);

		try (MockedConstruction<GunAction> shots = mockConstruction(GunAction.class)) {
			listener.onPlayerInteractWithEntity(new PlayerInteractEntityEvent(player, mock(Entity.class)));

			assertEquals(1, shots.constructed().size());
		}
	}

	// BZ-EV-03

	@Test
	@DisplayName("a plain left click with a scopeless melee weapon swings it")
	void scopelessMelee_plainLeftClick_swings() {
		MeleeWeapon melee = WeaponFixtures.meleeWeapon(1);
		when(weaponService.validateAndGetWeapon(player, item)).thenReturn(melee);

		try (MockedConstruction<MeleeAction> swings = mockConstruction(MeleeAction.class)) {
			listener.onPlayerInteract(click(Action.LEFT_CLICK_AIR));

			assertEquals(1, swings.constructed().size());
			verify(swings.constructed().get(0)).activate(player);
		}
	}

	// BZ-EV-17

	/** Selects {@code weapon} in hotbar slot 1, which seeds its Equip_Delay when it has one. */
	private void equipByHotbar(Weapon weapon, int equipDelayTicks) {
		HandlingData handling = new HandlingData();
		handling.setEquipDelay(equipDelayTicks);
		weapon.setHandlingData(handling);
		weapon.setCurrentMagCapacity(weapon.getAmmunitionData().getMaxMagCapacity());
		when(weaponService.validateAndGetWeapon(player, item)).thenReturn(weapon);
		when(inventory.getItem(1)).thenReturn(item);

		listener.onWeaponHeld(new PlayerItemHeldEvent(player, 0, 1));
	}

	@Test
	@DisplayName("a biological charge does not start inside its Equip_Delay")
	void biologicalCharge_blockedByEquipDelay() {
		equipByHotbar(WeaponFixtures.biologicalWeapon(10), 100);

		try (MockedConstruction<ChargeController> charges = mockConstruction(ChargeController.class)) {
			listener.onPlayerInteract(click(Action.RIGHT_CLICK_AIR));

			verify(charges.constructed().get(0), never()).start(player);
		}
	}

	@Test
	@DisplayName("a biological charge starts once there is no Equip_Delay")
	void biologicalCharge_startsWithoutEquipDelay() {
		equipByHotbar(WeaponFixtures.biologicalWeapon(10), 0);

		try (MockedConstruction<ChargeController> charges = mockConstruction(ChargeController.class)) {
			listener.onPlayerInteract(click(Action.RIGHT_CLICK_AIR));

			verify(charges.constructed().get(0)).start(player);
		}
	}

	@Test
	@DisplayName("an AUTO flamethrower does not spray inside its Equip_Delay")
	void incendiaryAuto_blockedByEquipDelay() {
		IncendiaryWeapon flamer = WeaponFixtures.incendiaryWeapon(10, 1);
		flamer.setCurrentSelectiveFire(SelectiveFire.AUTO);
		equipByHotbar(flamer, 100);

		try (MockedConstruction<IncendiaryAction> sprays = mockConstruction(IncendiaryAction.class)) {
			listener.onPlayerInteract(click(Action.RIGHT_CLICK_AIR));

			verify(sprays.constructed().get(0), never()).fireOnce(player);
		}
	}

	@Test
	@DisplayName("an AUTO flamethrower sprays once there is no Equip_Delay")
	void incendiaryAuto_spraysWithoutEquipDelay() {
		IncendiaryWeapon flamer = WeaponFixtures.incendiaryWeapon(10, 1);
		flamer.setCurrentSelectiveFire(SelectiveFire.AUTO);
		equipByHotbar(flamer, 0);

		try (MockedConstruction<IncendiaryAction> sprays = mockConstruction(IncendiaryAction.class)) {
			listener.onPlayerInteract(click(Action.RIGHT_CLICK_AIR));

			verify(sprays.constructed().get(0)).fireOnce(player);
		}
	}

	// BZ-EV-15

	@Test
	@DisplayName("swapping a gun into the main hand seeds its Equip_Delay and runs ON_EQUIP")
	void swapIntoMainHand_seedsEquipDelay() {
		GunWeapon gun      = gun(HandlingData.Trigger.RIGHT_CLICK);
		gun.getHandlingData().setEquipDelay(100);

		listener.onSwapHands(new PlayerSwapHandItemsEvent(player, item, null));

		verify(effectRunner).run(eq(gun), eq(EffectHook.ON_EQUIP), any());
		try (MockedConstruction<GunAction> shots = mockConstruction(GunAction.class)) {
			listener.onPlayerInteract(click(Action.RIGHT_CLICK_AIR));

			assertTrue(shots.constructed().isEmpty(), "the swapped-in gun fired inside its Equip_Delay");
		}
	}

	@Test
	@DisplayName("swapping a gun out of the main hand holsters it")
	void swapOutOfMainHand_holsters() {
		GunWeapon gun = gun(HandlingData.Trigger.RIGHT_CLICK);

		listener.onSwapHands(new PlayerSwapHandItemsEvent(player, null, item));

		verify(effectRunner).run(eq(gun), eq(EffectHook.ON_HOLSTER), any());
		verify(effectRunner, never()).run(eq(gun), eq(EffectHook.ON_EQUIP), any());
	}

	// BZ-EV-02: the release watchdogs write WeaponData.shooting, which the main thread writes too

	private void assertNoAsyncTimer() {
		verify(scheduler, never()).runTaskTimerAsynchronously(any(Plugin.class), any(Runnable.class), anyLong(),
		                                                      anyLong());
		verify(scheduler, atLeastOnce()).runTaskTimer(any(Plugin.class), any(Runnable.class), anyLong(), anyLong());
	}

	@Test
	@DisplayName("the SINGLE press-lock watchdog runs on the main thread")
	void singleWatchdog_isSynchronous() {
		gun(HandlingData.Trigger.RIGHT_CLICK);

		try (MockedConstruction<GunAction> ignored = mockConstruction(GunAction.class)) {
			listener.onPlayerInteract(click(Action.RIGHT_CLICK_AIR));
		}

		assertNoAsyncTimer();
	}

	@Test
	@DisplayName("the AUTO gun watchdog runs on the main thread")
	void autoWatchdog_isSynchronous() {
		gun(HandlingData.Trigger.RIGHT_CLICK).setCurrentSelectiveFire(SelectiveFire.AUTO);

		try (MockedConstruction<FullAutoTask> ignored = mockConstruction(FullAutoTask.class)) {
			listener.onPlayerInteract(click(Action.RIGHT_CLICK_AIR));
		}

		assertNoAsyncTimer();
	}

	@Test
	@DisplayName("the AUTO flamethrower watchdog runs on the main thread")
	void incendiaryAutoWatchdog_isSynchronous() {
		IncendiaryWeapon flamer = WeaponFixtures.incendiaryWeapon(10, 1);
		flamer.setCurrentSelectiveFire(SelectiveFire.AUTO);
		equipByHotbar(flamer, 0);

		try (MockedConstruction<IncendiaryAction> ignored = mockConstruction(IncendiaryAction.class,
				(action, context) -> when(action.fireOnce(player)).thenReturn(true))) {
			listener.onPlayerInteract(click(Action.RIGHT_CLICK_AIR));
		}

		assertNoAsyncTimer();
	}

	// BZ-EV-01 review: clearWeaponState runs on every swap, and must not reset the Melee.Cooldown gate

	@Test
	@DisplayName("a hotbar swap away from a melee weapon and back keeps its Melee.Cooldown")
	@SuppressWarnings("unchecked")
	void hotbarSwap_keepsMeleeCooldown() throws Exception {
		MeleeWeapon melee = WeaponFixtures.meleeWeapon(1);
		when(weaponService.validateAndGetWeapon(player, item)).thenReturn(melee);
		when(inventory.getItem(0)).thenReturn(item);

		Field field = WeaponInteract.class.getDeclaredField("meleeCooldowns");
		field.setAccessible(true);
		Map<UUID, Long> cooldowns = (Map<UUID, Long>) field.get(listener);
		cooldowns.put(melee.getUuid(), System.currentTimeMillis());

		listener.onWeaponHeld(new PlayerItemHeldEvent(player, 0, 1));
		listener.onWeaponHeld(new PlayerItemHeldEvent(player, 1, 0));

		assertTrue(cooldowns.containsKey(melee.getUuid()), "the swap must not let the next swing skip Cooldown");
	}

	// BZ-EV-15 review + BZ-EV-09: an F swap or an inventory click unscopes only what this plugin scoped

	private ScopeData scopeOn(GunWeapon gun, boolean scoped) {
		ScopeData scope = new ScopeData();
		scope.setScoped(scoped);
		gun.setScopeData(scope);
		return scope;
	}

	/**
	 * {@code unScope(player, true)} strips SLOWNESS whether or not this plugin applied it - a flashbang's included.
	 * Pinned on the call, since this test environment resolves no {@code PotionEffectType} to observe the removal.
	 */
	@Test
	@DisplayName("F-swapping a gun out of the main hand never force-unscopes it (a flashbang's SLOWNESS stays)")
	void swapOut_gun_neverForceUnscopes() {
		GunWeapon gun = spy(gun(HandlingData.Trigger.RIGHT_CLICK));
		scopeOn(gun, false);
		when(weaponService.validateAndGetWeapon(player, item)).thenReturn(gun);

		listener.onSwapHands(new PlayerSwapHandItemsEvent(player, null, item));

		verify(gun).unScope(player, false);
		verify(gun, never()).unScope(player, true);
	}

	@Test
	@DisplayName("F-swapping a scoped gun out of the main hand unscopes it")
	void swapOut_scopedGun_unscopes() {
		ScopeData scope = scopeOn(gun(HandlingData.Trigger.RIGHT_CLICK), true);

		listener.onSwapHands(new PlayerSwapHandItemsEvent(player, null, item));

		assertFalse(scope.isScoped(), "a scoped weapon moved off-hand kept the player slowed (BZ-EV-09)");
	}

	@Test
	@DisplayName("an inventory click unscopes the scoped weapon in the main hand")
	void inventoryClick_unscopesMainHandWeapon() {
		ScopeData scope = scopeOn(gun(HandlingData.Trigger.RIGHT_CLICK), true);

		InventoryClickEvent click = mock(InventoryClickEvent.class);
		when(click.getWhoClicked()).thenReturn(player);
		listener.onInventoryClick(click);

		assertFalse(scope.isScoped(), "clicking the scoped weapon out of the held slot left it scoped");
	}

	@Test
	@DisplayName("an inventory drag unscopes the scoped weapon in the main hand")
	void inventoryDrag_unscopesMainHandWeapon() {
		ScopeData scope = scopeOn(gun(HandlingData.Trigger.RIGHT_CLICK), true);

		InventoryDragEvent drag = mock(InventoryDragEvent.class);
		when(drag.getWhoClicked()).thenReturn(player);
		listener.onInventoryDrag(drag);

		assertFalse(scope.isScoped());
	}

	// BZ-FA-06: On_Shot now wears a biological/beam weapon down, so a worn-out one must be refused like a gun

	@Test
	@DisplayName("a worn-out biological weapon starts no charge")
	void brokenBiological_startsNoCharge() {
		BiologicalWeapon biological = WeaponFixtures.biologicalWeapon(10);
		equipByHotbar(biological, 0);
		biological.setCurrentDurability((short) 0);

		try (MockedConstruction<ChargeController> charges = mockConstruction(ChargeController.class)) {
			listener.onPlayerInteract(click(Action.RIGHT_CLICK_AIR));

			verify(charges.constructed().get(0), never()).start(player);
		}
		verify(effectRunner).run(eq(biological), eq(EffectHook.ON_EMPTY), any());
	}

}
