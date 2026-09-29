package org.luckyraven.bartizan.listener;

import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.ViaAPI;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.UnsafeValues;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemFactory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.luckyraven.bartizan.api.combat.CombatEligibility;
import org.luckyraven.bartizan.api.event.WeaponReloadCompleteEvent;
import org.luckyraven.bartizan.api.raytrace.WeaponRaytracer;
import org.luckyraven.bartizan.api.support.WeaponFixtures;
import org.luckyraven.bartizan.api.weapon.BiologicalWeapon;
import org.luckyraven.bartizan.api.weapon.GunWeapon;
import org.luckyraven.bartizan.api.weapon.IncendiaryWeapon;
import org.luckyraven.bartizan.api.weapon.SelectiveFire;
import org.luckyraven.bartizan.api.weapon.WeaponType;
import org.luckyraven.bartizan.api.weapon.dto.DurabilityData;
import org.luckyraven.bartizan.api.weapon.dto.HandlingData;
import org.luckyraven.bartizan.api.weapon.dto.ProjectileData;
import org.luckyraven.bartizan.api.weapon.ProjectileType;
import org.luckyraven.bartizan.api.weapon.modifiers.BlockDamageManager;
import org.luckyraven.bartizan.api.weapon.recoil.RecoilManager;
import org.luckyraven.bartizan.effect.EffectRunner;
import org.luckyraven.bartizan.fire.PluginFireRegistry;
import org.luckyraven.bartizan.scope.SpyglassScopeTask;
import org.luckyraven.bartizan.status.StatusEffectService;
import org.luckyraven.bartizan.support.TickScheduler;
import org.luckyraven.bartizan.weapon.CircumstanceRules;
import org.luckyraven.bartizan.weapon.TriggerRelease;
import org.luckyraven.bartizan.weapon.WeaponService;
import org.luckyraven.bartizan.weapon.action.ChargeController;
import org.luckyraven.bartizan.weapon.action.FullAutoTask;
import org.luckyraven.bartizan.weapon.action.GunAction;
import org.luckyraven.bartizan.weapon.action.GunFireDispatcher;
import org.luckyraven.bartizan.weapon.action.IncendiaryAction;
import org.luckyraven.keystone.nms.NmsVersion;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongConsumer;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Trigger-release timing in {@link WeaponInteract} (0.5.2), driven tick by tick through {@link TickScheduler}.
 * Spigot sends no "released right-click" event: a vanilla client re-sends USE_ITEM every 4 ticks while right-click
 * is held on an item it is not using, and nothing on release. These pin the fallback's release bound - fire stops
 * at most 4 ticks after the last repeat, whatever the cooldown - and the slack on the SINGLE/BURST re-arm, the
 * charge release and the flamethrower spray; and exact release detection (1.21.11+), where the gun's vanilla use
 * state is the hold and fire stops on the first tick after it ends.
 */
@DisplayName("WeaponInteract - trigger release timing")
class WeaponInteractTriggerReleaseTest {

	private final TickScheduler           clock         = new TickScheduler();
	private final Player                  player        = mock(Player.class);
	private final PlayerInventory         inventory     = mock(PlayerInventory.class);
	private final WeaponService           weaponService = mock(WeaponService.class);
	private final ItemStack               item          = mock(ItemStack.class);
	private final ItemMeta                itemMeta      = mock(ItemMeta.class);
	private final PersistentDataContainer itemData      = mock(PersistentDataContainer.class);
	private final RecoilManager           recoil        = mock(RecoilManager.class);

	private MockedStatic<Bukkit> bukkit;
	private WeaponInteract       listener;
	/**
	 * {@code Player#isHandRaised()}: the server-side use state.
	 */
	private boolean              handRaised;
	private boolean              dead;
	/**
	 * {@code Player#getOpenInventory()}'s type: {@code CRAFTING} is the player's own inventory, i.e. no container.
	 */
	private InventoryType        openInventory = InventoryType.CRAFTING;

	@BeforeEach
	void setUp() {
		when(player.getUniqueId()).thenReturn(UUID.randomUUID());
		when(player.getInventory()).thenReturn(inventory);
		when(inventory.getItemInMainHand()).thenReturn(item);
		when(item.hasItemMeta()).thenReturn(true);
		when(player.isHandRaised()).thenAnswer(invocation -> handRaised);
		when(player.isDead()).thenAnswer(invocation -> dead);
		InventoryView view = mock(InventoryView.class);
		when(view.getType()).thenAnswer(invocation -> openInventory);
		when(player.getOpenInventory()).thenReturn(view);
		// the gun item already carries the 1.21.11+ use state (TriggerRelease's marker) - read only in exact mode
		when(item.getItemMeta()).thenReturn(itemMeta);
		when(itemMeta.getPersistentDataContainer()).thenReturn(itemData);
		when(itemData.has(any(NamespacedKey.class), eq(PersistentDataType.BYTE))).thenReturn(true);

		CombatEligibility eligibility = mock(CombatEligibility.class);
		when(eligibility.canBeHit(player)).thenReturn(true);

		bukkit = mockStatic(Bukkit.class);
		bukkit.when(Bukkit::getScheduler).thenReturn(clock.scheduler());
		bukkit.when(Bukkit::getPluginManager).thenReturn(mock(PluginManager.class));

		listener = new WeaponInteract(mock(JavaPlugin.class), weaponService, mock(WeaponRaytracer.class),
		                              mock(PluginFireRegistry.class), eligibility, mock(EffectRunner.class),
		                              mock(BlockDamageManager.class), mock(StatusEffectService.class),
		                              mock(SpyglassScopeTask.class));
	}

	@AfterEach
	void tearDown() {
		exactMode(false, false);
		bukkit.close();
	}

	private PlayerInteractEvent press() {
		PlayerInteractEvent event = new PlayerInteractEvent(player, Action.RIGHT_CLICK_AIR, item, null, null,
		                                                    EquipmentSlot.HAND);
		listener.onPlayerInteract(event);
		return event;
	}

	/**
	 * Ticks 0..{@code lastTick}: each tick's scheduler heartbeat first, then {@code events} for that tick - the
	 * order the server runs them in.
	 */
	private void run(long lastTick, LongConsumer events) {
		for (long tick = 0; tick <= lastTick; tick++) {
			if (tick > 0) clock.tick();
			events.accept(tick);
		}
	}

	/**
	 * Held from tick 0 through {@code lastRepeat}: the press at 0 and a repeat every 4 ticks after it.
	 */
	private static boolean repeatAt(long tick, long lastRepeat) {
		return tick <= lastRepeat && tick % 4 == 0;
	}

	private GunWeapon gun(int cooldown, SelectiveFire mode) {
		ProjectileData projectile = ProjectileData.builder()
				.speed(3.0).type(ProjectileType.BULLET).damage(5.0).consumed(1).perShot(1).cooldown(cooldown)
				.distance(60).particle(false).gravity(0.0)
				.build();
		GunWeapon gun = spy(new GunWeapon(UUID.randomUUID(), "test_rifle", "&fTest Rifle", WeaponType.GUN,
		                                  Material.IRON_HOE, 0, (short) 100, List.of(), false, null, mode, 0,
		                                  projectile, WeaponFixtures.instantReload(),
		                                  WeaponFixtures.ammoData(30, 1, 30)));
		DurabilityData durability = new DurabilityData();
		durability.setConsumeOnTime(-1);
		gun.setDurabilityData(durability);
		doReturn(recoil).when(gun).getRecoil();
		when(weaponService.isWeapon(item)).thenReturn(true);
		when(weaponService.validateAndGetWeapon(player, item)).thenReturn(gun);
		return gun;
	}

	@SuppressWarnings("unchecked")
	private <V> Map<UUID, V> map(String field) throws ReflectiveOperationException {
		Field declared = WeaponInteract.class.getDeclaredField(field);
		declared.setAccessible(true);
		return (Map<UUID, V>) declared.get(listener);
	}

	// ---------------------------------------------------------------------------------------------------------
	// AUTO, fallback (repeat-driven) release
	// ---------------------------------------------------------------------------------------------------------

	@ParameterizedTest(name = "cooldown {0}, last repeat at tick {1}")
	@CsvSource({"0, 0", "0, 4", "0, 8", "1, 0", "1, 4", "1, 12", "2, 0", "2, 4", "2, 8", "4, 0", "4, 4", "4, 8"})
	@DisplayName("AUTO keeps its cadence while held and fires nothing later than 4 ticks after the last repeat")
	void auto_releaseOverrunIsAtMostFourTicksAfterTheLastRepeat(int cooldown, long lastRepeat) {
		gun(cooldown, SelectiveFire.AUTO);
		List<Long> rounds = new ArrayList<>();

		try (MockedConstruction<GunAction> ignored = mockConstruction(GunAction.class,
				(shot, context) -> rounds.add(clock.now()))) {
			run(lastRepeat + 20, tick -> {
				if (repeatAt(tick, lastRepeat)) press();
			});
		}

		// Projectile.Cooldown 0 and 1 fire every tick, 2 every other tick, 4 every fourth - from the press on,
		// through the 4 ticks a repeat may still be on its way, and not one tick later.
		long step = Math.max(1, cooldown);
		List<Long> expected = LongStream.rangeClosed(0, lastRepeat + 4).filter(tick -> tick % step == 0).boxed()
		                                .toList();
		assertEquals(expected, rounds);
	}

	@Test
	@DisplayName("AUTO held with steady 4-tick repeats runs one task throughout and never resets recoil mid-hold")
	void auto_steadyRepeats_noSelfCancelAndNoRecoilReset() throws ReflectiveOperationException {
		GunWeapon gun = gun(0, SelectiveFire.AUTO);
		Map<UUID, FullAutoTask> autoTasks = map("autoTasks");
		List<Long>              rounds    = new ArrayList<>();
		long                    lastRepeat = 40;

		try (MockedConstruction<GunAction> ignored = mockConstruction(GunAction.class,
				(shot, context) -> rounds.add(clock.now()))) {
			FullAutoTask[] first = new FullAutoTask[1];
			run(lastRepeat, tick -> {
				if (repeatAt(tick, lastRepeat)) press();
				if (tick == 0) first[0] = autoTasks.get(gun.getUuid());
				assertSame(first[0], autoTasks.get(gun.getUuid()), "the burst restarted at tick " + tick);
			});
			assertNotNull(first[0]);
			verify(recoil, never()).resetRecoilPattern();

			for (int i = 0; i < 10; i++) clock.tick();
		}

		assertEquals(LongStream.rangeClosed(0, lastRepeat + 4).boxed().toList(), rounds);
		verify(recoil, times(1)).resetRecoilPattern();
	}

	// ---------------------------------------------------------------------------------------------------------
	// SINGLE press-hold re-arm
	// ---------------------------------------------------------------------------------------------------------

	/**
	 * The SINGLE fire-rate gate ({@code GunFireDispatcher.lock}) runs on the wall clock; this clock is simulated, so
	 * the gate is lifted before each event as real time would have by then - leaving only the press-hold gate.
	 */
	private void pressAfterFireRateWindow(GunWeapon gun) {
		GunFireDispatcher.unlock(gun.getUuid());
		press();
	}

	@Test
	@DisplayName("SINGLE: a repeat landing one tick late does not re-arm the trigger mid-hold")
	void single_lateRepeat_doesNotFireASecondShot() {
		GunWeapon gun = gun(4, SelectiveFire.SINGLE);

		try (MockedConstruction<GunAction> shots = mockConstruction(GunAction.class)) {
			run(20, tick -> {
				if (tick == 0 || tick == 4 || tick == 9 || tick == 13) pressAfterFireRateWindow(gun);
			});

			assertEquals(1, shots.constructed().size(), "the held trigger fired again");
		}
	}

	@Test
	@DisplayName("SINGLE: the trigger re-arms 6 ticks after the last event (one tick of slack past the repeat)")
	void single_rearmsOneTickPastTheRepeatWindow() {
		GunWeapon gun = gun(4, SelectiveFire.SINGLE);

		try (MockedConstruction<GunAction> shots = mockConstruction(GunAction.class)) {
			run(12, tick -> {
				if (tick == 0 || tick == 6) pressAfterFireRateWindow(gun);
			});

			assertEquals(2, shots.constructed().size(), "a fresh press after the slack window must fire");
		}
	}

	// ---------------------------------------------------------------------------------------------------------
	// Charge-then-release
	// ---------------------------------------------------------------------------------------------------------

	@Test
	@DisplayName("charge: a late repeat does not release the charge; the release lands 6 ticks after the last one")
	void charge_lateRepeatKeepsTheChargeAndReleaseLandsAfterTheSlack() {
		BiologicalWeapon biological = WeaponFixtures.biologicalWeapon(10);
		when(weaponService.validateAndGetWeapon(player, item)).thenReturn(biological);
		List<Long> releases = new ArrayList<>();

		try (MockedConstruction<ChargeController> ignored = mockConstruction(ChargeController.class,
				(controller, context) -> {
					when(controller.start(player)).thenReturn(true);
					doAnswer(invocation -> releases.add(clock.now())).when(controller)
					                   .release(player);
				})) {
			run(30, tick -> {
				if (tick == 0 || tick == 4 || tick == 9) press();
			});
		}

		assertEquals(List.of(15L), releases);
	}

	// ---------------------------------------------------------------------------------------------------------
	// Flamethrower AUTO
	// ---------------------------------------------------------------------------------------------------------

	@Test
	@DisplayName("flamethrower AUTO keeps its cadence and sprays nothing later than 4 ticks after the last repeat")
	void flamethrower_spraysStopWithinTheRepeatWindow() {
		IncendiaryWeapon flamer = WeaponFixtures.incendiaryWeapon(10, 1); // Tick_Rate 2
		flamer.setCurrentSelectiveFire(SelectiveFire.AUTO);
		when(weaponService.validateAndGetWeapon(player, item)).thenReturn(flamer);
		List<Long> sprays = new ArrayList<>();

		try (MockedConstruction<IncendiaryAction> ignored = mockConstruction(IncendiaryAction.class,
				(action, context) -> when(action.fireOnce(player)).thenAnswer(invocation -> sprays.add(clock.now())))) {
			run(30, tick -> {
				if (repeatAt(tick, 8)) press();
			});
		}

		// the press sprays at 0; the loop keeps the old cadence (Tick_Rate + 1 after the press, then every
		// Tick_Rate) up to the last repeat (8) + 4
		assertEquals(List.of(0L, 3L, 5L, 7L, 9L, 11L), sprays);
	}


	// ---------------------------------------------------------------------------------------------------------
	// Exact release detection (1.21.11+ use state)
	// ---------------------------------------------------------------------------------------------------------

	private void exactMode(boolean serverSupportsIt, boolean enabled) {
		// TriggerRelease's startup probe: a mock item factory never calls a meta empty, so the probe item reads as
		// carrying the components - a server whose item parser takes them
		bukkit.when(Bukkit::getUnsafe).thenReturn(mock(UnsafeValues.class));
		bukkit.when(Bukkit::getItemFactory).thenReturn(mock(ItemFactory.class));

		try (MockedStatic<NmsVersion> version = mockStatic(NmsVersion.class)) {
			version.when(NmsVersion::current)
			       .thenReturn(serverSupportsIt ? new NmsVersion(21, 11) : new NmsVersion(20, 6));
			TriggerRelease.configure(enabled);
		}
	}

	/**
	 * A press as the server handles it: the event, then - unless the event denied the item use - the vanilla use it
	 * starts in the same packet (the gun item's never-finishing consumable), which raises the hand.
	 */
	private PlayerInteractEvent pressAndUse() {
		PlayerInteractEvent event = press();
		if (event.useItemInHand() != Event.Result.DENY) handRaised = true;
		return event;
	}

	@Test
	@DisplayName("exact: the owner's timeline - released right after the 5th round, not one round more")
	void exact_ownersTimeline_stopsOnTheFirstTickAfterRelease() {
		exactMode(true, true);
		gun(0, SelectiveFire.AUTO); // mp5: Cooldown 0.1 truncates to 0, a round every tick
		List<Long> rounds = new ArrayList<>();

		try (MockedConstruction<GunAction> ignored = mockConstruction(GunAction.class,
				(shot, context) -> rounds.add(clock.now()))) {
			run(20, tick -> {
				if (tick == 0) {
					assertEquals(Event.Result.ALLOW, pressAndUse().useItemInHand(),
					             "the gun's vanilla use must go through, or the server never sees the hold");
				}
				if (tick == 4) handRaised = false; // RELEASE_USE_ITEM, handled after tick 4's heartbeat
			});
		}

		assertEquals(List.of(0L, 1L, 2L, 3L, 4L), rounds);
	}

	/**
	 * ViaBackwards on the server: exact is decided per client through ViaVersion, the protocol it joined with -
	 * 1.21.11 (774, {@code use_effects}) and newer follow the use state, older ones stay on their repeats.
	 */
	private MockedStatic<Via> viaBackwardsClient(int protocol) {
		PluginManager plugins = mock(PluginManager.class);
		when(plugins.getPlugin("ViaBackwards")).thenReturn(mock(Plugin.class));
		when(plugins.getPlugin("ViaVersion")).thenReturn(mock(Plugin.class));
		bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
		exactMode(true, true);

		ViaAPI<?> api = mock(ViaAPI.class);
		when(api.getPlayerVersion(player.getUniqueId())).thenReturn(protocol);
		MockedStatic<Via> via = mockStatic(Via.class);
		via.when(Via::getAPI).thenReturn(api);
		return via;
	}

	@ParameterizedTest(name = "protocol {0}")
	@CsvSource({"774", "775"})
	@DisplayName("exact with ViaBackwards installed: a 1.21.11+ client still stops on the first tick after release")
	void exact_modernClientWithViaBackwards_stopsOnTheFirstTickAfterRelease(int protocol) {
		gun(0, SelectiveFire.AUTO);
		List<Long> rounds = new ArrayList<>();

		try (MockedStatic<Via> ignoredVia = viaBackwardsClient(protocol);
		     MockedConstruction<GunAction> ignored = mockConstruction(GunAction.class,
				     (shot, context) -> rounds.add(clock.now()))) {
			run(20, tick -> {
				if (tick == 0) assertEquals(Event.Result.ALLOW, pressAndUse().useItemInHand());
				if (tick == 4) handRaised = false;
			});
		}

		assertEquals(List.of(0L, 1L, 2L, 3L, 4L), rounds);
	}

	@Test
	@DisplayName("exact with ViaBackwards installed: a 1.21.10 client is denied the use and stays on its repeats")
	void exact_olderClientWithViaBackwards_useDeniedAndFallsBackToTheRepeats() {
		gun(0, SelectiveFire.AUTO);
		List<Long> rounds = new ArrayList<>();

		try (MockedStatic<Via> ignoredVia = viaBackwardsClient(773);
		     MockedConstruction<GunAction> ignored = mockConstruction(GunAction.class,
				     (shot, context) -> rounds.add(clock.now()))) {
			run(24, tick -> {
				if (repeatAt(tick, 8)) {
					assertEquals(Event.Result.DENY, press().useItemInHand(),
					             "a client without use_effects must never be put in the use state");
				}
			});
		}

		assertEquals(LongStream.rangeClosed(0, 12).boxed().toList(), rounds);
	}

	/**
	 * Also the server's whole view of a trigger held into chat, the player's own inventory or the pause menu: those
	 * screens are client-side, send nothing, and the release only comes once they close - so fire carries on
	 * meanwhile, up to the end of the magazine ({@link #reloadComplete_resumesNothing}; migration.md §15).
	 */
	@Test
	@DisplayName("exact: a long hold with no repeats at all (or one held into a client-side screen) keeps firing, "
	             + "never resets recoil, and stops on release")
	void exact_longHoldWithoutRepeats_firesThroughoutAndStopsOnRelease() {
		exactMode(true, true);
		gun(0, SelectiveFire.AUTO);
		List<Long> rounds = new ArrayList<>();

		try (MockedConstruction<GunAction> ignored = mockConstruction(GunAction.class,
				(shot, context) -> rounds.add(clock.now()))) {
			run(39, tick -> {
				if (tick == 0) pressAndUse();
				if (tick == 39) handRaised = false;
			});
			verify(recoil, never()).resetRecoilPattern();

			for (int i = 0; i < 10; i++) clock.tick();
		}

		assertEquals(LongStream.rangeClosed(0, 39).boxed().toList(), rounds);
		verify(recoil, times(1)).resetRecoilPattern();
		assertEquals(0, clock.pending(), "the burst is over - nothing left scheduled");
	}

	/**
	 * Nothing ends a dead player's use state (keepInventory, or a plugin keeping weapons: the gun stays in hand), and
	 * a client with a screen open sends no release until it closes - the server only sees the containers it opened.
	 */
	@ParameterizedTest(name = "{0}")
	@CsvSource({"death", "server-opened container"})
	@DisplayName("exact: death or a server-opened container ends the burst on the next tick, the hand still raised")
	void exact_deathOrContainer_stopsTheBurst(String cause) {
		exactMode(true, true);
		gun(0, SelectiveFire.AUTO);
		List<Long> rounds = new ArrayList<>();

		try (MockedConstruction<GunAction> ignored = mockConstruction(GunAction.class,
				(shot, context) -> rounds.add(clock.now()))) {
			run(20, tick -> {
				if (tick == 0) pressAndUse();
				if (tick == 4) {
					if (cause.equals("death")) dead = true;
					else openInventory = InventoryType.CHEST;
				}
			});
		}

		assertEquals(List.of(0L, 1L, 2L, 3L, 4L), rounds);
	}

	@Test
	@DisplayName("exact: no use state (a predicted use-on-block, e.g. a hoe on dirt) falls back to the repeats")
	void exact_withoutAUseState_fallsBackToTheRepeats() {
		exactMode(true, true);
		gun(0, SelectiveFire.AUTO);
		List<Long> rounds = new ArrayList<>();

		try (MockedConstruction<GunAction> ignored = mockConstruction(GunAction.class,
				(shot, context) -> rounds.add(clock.now()))) {
			run(24, tick -> {
				// a use-on-block repeats as USE_ITEM_ON, and the hand never rises
				if (repeatAt(tick, 8)) {
					listener.onPlayerInteract(new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, item,
					                                                  mock(Block.class), BlockFace.UP, EquipmentSlot.HAND));
				}
			});
		}

		assertEquals(LongStream.rangeClosed(0, 12).boxed().toList(), rounds);
	}

	@Test
	@DisplayName("exact: an item built before it had the use state keeps its vanilla use denied until rebuilt")
	void exact_itemWithoutTheUseStateYet_staysDenied() {
		exactMode(true, true);
		gun(0, SelectiveFire.AUTO);
		when(itemData.has(any(NamespacedKey.class), eq(PersistentDataType.BYTE))).thenReturn(false);
		List<Long> rounds = new ArrayList<>();

		try (MockedConstruction<GunAction> ignored = mockConstruction(GunAction.class,
				(shot, context) -> rounds.add(clock.now()))) {
			run(20, tick -> {
				// an armor-piece gun would otherwise equip itself: Item#use only reaches a consumable it has
				if (tick == 0) assertEquals(Event.Result.DENY, pressAndUse().useItemInHand());
			});
		}

		// no use state on the server: the repeats decide, as in the fallback
		assertEquals(List.of(0L, 1L, 2L, 3L, 4L), rounds);
	}

	@Test
	@DisplayName("exact: SINGLE re-arms on the first tick after release, not after the repeat window")
	void exact_single_rearmsOnRelease() {
		exactMode(true, true);
		GunWeapon gun = gun(4, SelectiveFire.SINGLE);

		try (MockedConstruction<GunAction> shots = mockConstruction(GunAction.class)) {
			run(12, tick -> {
				if (tick == 0) pressAndUse();
				if (tick == 2) handRaised = false;
				if (tick == 3) {
					GunFireDispatcher.unlock(gun.getUuid()); // the wall-clock fire-rate gate, as in real time
					pressAndUse();
				}
			});

			assertEquals(2, shots.constructed().size(), "the released trigger must re-arm straight away");
		}
	}

	@Test
	@DisplayName("switch off on a 1.21.11 server: the vanilla use stays denied and the repeat fallback applies")
	void exactSwitchOff_fallsBack() {
		exactMode(true, false);
		GunWeapon  gun    = gun(0, SelectiveFire.AUTO);
		List<Long> rounds = new ArrayList<>();

		try (MockedConstruction<GunAction> ignored = mockConstruction(GunAction.class,
				(shot, context) -> rounds.add(clock.now()))) {
			run(20, tick -> {
				if (tick == 0) assertEquals(Event.Result.DENY, pressAndUse().useItemInHand());
			});
		}

		assertFalse(TriggerRelease.isExact(gun));
		assertEquals(List.of(0L, 1L, 2L, 3L, 4L), rounds);
	}

	@Test
	@DisplayName("a server older than 1.21.11: the switch is on but the repeat fallback applies")
	void exactUnsupportedServer_fallsBack() {
		exactMode(false, true);
		GunWeapon  gun    = gun(0, SelectiveFire.AUTO);
		List<Long> rounds = new ArrayList<>();

		try (MockedConstruction<GunAction> ignored = mockConstruction(GunAction.class,
				(shot, context) -> rounds.add(clock.now()))) {
			run(20, tick -> {
				if (tick == 0) assertEquals(Event.Result.DENY, pressAndUse().useItemInHand());
			});
		}

		assertFalse(TriggerRelease.isExact(gun));
		assertEquals(List.of(0L, 1L, 2L, 3L, 4L), rounds);
	}

	/**
	 * A press during a reload starts no use state: Paper then keeps the client on its repeats, which carry the fire
	 * on once the reload is done, for as long as the button is really held.
	 */
	@Test
	@DisplayName("exact: a right-click during a reload is denied its vanilla use")
	void exact_reloadingPressDeniesTheUseState() {
		exactMode(true, true);
		GunWeapon gun = gun(0, SelectiveFire.AUTO);
		doReturn(true).when(gun).isReloading();

		PlayerInteractEvent event = press();

		assertTrue(event.isCancelled(), "the block interaction stays denied while reloading");
		assertEquals(Event.Result.DENY, event.useItemInHand());
	}

	/**
	 * Nothing resumes a burst when a reload completes: a trigger held into chat, the player's own inventory or the
	 * pause menu sends no release while the screen is open, so a resume would fire unattended, reload after reload,
	 * until the ammo ran out. Unattended fire stops with the magazine; a held trigger needs a fresh press.
	 */
	@ParameterizedTest(name = "exact: {0}")
	@CsvSource({"true", "false"})
	@DisplayName("a completed reload never starts fire on its own, the hand raised or not")
	void reloadComplete_resumesNothing(boolean exact) throws ReflectiveOperationException {
		exactMode(true, exact);
		GunWeapon gun = gun(0, SelectiveFire.AUTO);
		when(weaponService.getHeldHand(player, gun.getUuid())).thenReturn(EquipmentSlot.HAND);
		handRaised = true;

		try (MockedConstruction<GunAction> rounds = mockConstruction(GunAction.class)) {
			// every handler WeaponInteract has for the event, if any
			for (var method : WeaponInteract.class.getMethods()) {
				if (method.getParameterCount() == 1 &&
				    method.getParameterTypes()[0] == WeaponReloadCompleteEvent.class) {
					method.invoke(listener, new WeaponReloadCompleteEvent(gun, player));
				}
			}
			for (int i = 0; i < 10; i++) clock.tick();

			assertEquals(0, rounds.constructed().size());
		}
	}

	@Test
	@DisplayName("exact: a creative player's own inventory reads as CREATIVE, not CRAFTING - still no container")
	void exact_creativeMode_holdsLikeSurvival() {
		exactMode(true, true);
		gun(0, SelectiveFire.AUTO);
		openInventory = InventoryType.CREATIVE; // CraftInventoryView#getType for a creative player's 2x2 grid
		List<Long> rounds = new ArrayList<>();

		try (MockedConstruction<GunAction> ignored = mockConstruction(GunAction.class,
				(shot, context) -> rounds.add(clock.now()))) {
			run(20, tick -> {
				if (tick == 0) pressAndUse();
				if (tick == 15) handRaised = false;
			});
		}

		assertEquals(LongStream.rangeClosed(0, 15).boxed().toList(), rounds);
	}

	/**
	 * A one-client-tick tap whose USE_ITEM and RELEASE_USE_ITEM both land before the next server tick (a tick over
	 * 50 ms, or network jitter): the per-tick check never sees the hand raised.
	 */
	@Test
	@DisplayName("exact: a tap released before the next tick fires the press's round only")
	void exact_tapReleasedWithinTheSameTick_firesOneRound() {
		exactMode(true, true);
		gun(0, SelectiveFire.AUTO);
		List<Long> rounds = new ArrayList<>();

		try (MockedConstruction<GunAction> ignored = mockConstruction(GunAction.class,
				(shot, context) -> rounds.add(clock.now()))) {
			run(20, tick -> {
				if (tick == 0) {
					pressAndUse();
					handRaised = false;
				}
			});
		}

		assertEquals(List.of(0L), rounds);
	}

	@Test
	@DisplayName("exact: SINGLE re-arms after a tap released before the next tick, so a fast re-press fires")
	void exact_single_tapReleasedWithinTheSameTick_rearms() {
		exactMode(true, true);
		GunWeapon gun = gun(4, SelectiveFire.SINGLE);

		try (MockedConstruction<GunAction> shots = mockConstruction(GunAction.class)) {
			run(12, tick -> {
				if (tick == 0) {
					pressAndUse();
					handRaised = false;
				}
				if (tick == 3) {
					GunFireDispatcher.unlock(gun.getUuid());
					pressAndUse();
				}
			});

			assertEquals(2, shots.constructed().size(), "the tap's release must re-arm the trigger");
		}
	}

	@Test
	@DisplayName("exact: a press on a material cooldown starts no use, so the repeats decide")
	void exact_pressOnItemCooldown_fallsBackToTheRepeats() {
		exactMode(true, true);
		gun(0, SelectiveFire.AUTO);
		when(player.hasCooldown(any())).thenReturn(true);
		List<Long> rounds = new ArrayList<>();

		try (MockedConstruction<GunAction> ignored = mockConstruction(GunAction.class,
				(shot, context) -> rounds.add(clock.now()))) {
			run(24, tick -> {
				if (repeatAt(tick, 8)) press(); // vanilla refuses the use: the hand never rises
			});
		}

		assertEquals(LongStream.rangeClosed(0, 12).boxed().toList(), rounds);
	}

	/**
	 * ViaBackwards/ViaRewind/Geyser on a proxy, where the older-client plugin check can't see them: the server
	 * enters the use state, but the client keeps sending repeats (a client in the use state sends none) and may never
	 * send the release.
	 */
	@Test
	@DisplayName("exact: a client repeating right-click through the server's use state falls back to the repeats")
	void exact_clientIgnoringTheUseState_fallsBackToTheRepeats() {
		exactMode(true, true);
		gun(0, SelectiveFire.AUTO);
		List<Long> rounds = new ArrayList<>();

		try (MockedConstruction<GunAction> ignored = mockConstruction(GunAction.class,
				(shot, context) -> rounds.add(clock.now()))) {
			run(30, tick -> {
				if (tick == 0) pressAndUse(); // the server's use state starts, and never ends
				if (repeatAt(tick, 8) && tick > 0) {
					assertEquals(Event.Result.DENY, press().useItemInHand(),
					             "no more use states for a client that doesn't follow them");
				}
			});

			assertEquals(LongStream.rangeClosed(0, 12).boxed().toList(), rounds);
		}
	}

	/**
	 * {@code Shoot.Circumstance} is checked once per press: a burst it stops mid-hold stays stopped until the
	 * trigger is released and pressed again, in both modes - the fallback's next repeat re-enters as a press, is
	 * denied, and holds the press gate until the release.
	 */
	@ParameterizedTest(name = "exact: {0}")
	@CsvSource({"true", "false"})
	@DisplayName("a burst a circumstance stops mid-hold resumes only on a fresh press, in both modes")
	void circumstanceDeniedMidHold_resumesOnlyOnAFreshPress(boolean exact) {
		exactMode(true, exact);
		GunWeapon  gun       = gun(0, SelectiveFire.AUTO);
		List<Long> rounds    = new ArrayList<>();
		boolean[]  sprinting = {false};

		try (MockedStatic<CircumstanceRules> rules = mockStatic(CircumstanceRules.class);
		     MockedConstruction<GunAction> ignored = mockConstruction(GunAction.class,
				     (shot, context) -> rounds.add(clock.now()))) {
			rules.when(() -> CircumstanceRules.firstDenied(any(), any()))
			     .thenAnswer(invocation -> sprinting[0] ? HandlingData.Circumstance.SPRINTING : null);

			run(40, tick -> {
				if (tick == 4) sprinting[0] = true; // after tick 4's round: the burst stops at 5
				if (tick == 10) sprinting[0] = false;
				if (tick == 30) GunFireDispatcher.unlock(gun.getUuid()); // the wall-clock gate, as in real time
				if (exact) {
					if (tick == 0 || tick == 30) pressAndUse();
					if (tick == 20) handRaised = false;
				} else if (repeatAt(tick, 20) || tick == 30) {
					press();
				}
			});
		}

		// held 0-20: stopped at 5 and silent through the rest of the hold; the fresh press at 30 fires again
		assertEquals(List.of(0L, 1L, 2L, 3L, 4L), rounds.subList(0, 5));
		assertEquals(30L, rounds.get(5));
	}

	/**
	 * The press's own round can put the gun's material on a cooldown - a {@code Cooldown} effect on the shot, or
	 * {@code HUD.Reload_Item_Cooldown} on an empty magazine's reload - after which vanilla refuses the use the client
	 * already predicted, and tells it nothing. Denying the use makes Paper resync the client back onto its repeats.
	 */
	@Test
	@DisplayName("exact: a round that puts the gun on a cooldown denies the use, and the repeats carry the burst")
	void exact_roundSettingACooldown_fallsBackToTheRepeats() {
		exactMode(true, true);
		gun(0, SelectiveFire.AUTO);
		boolean[] cooldown = {false};
		when(player.hasCooldown(any())).thenAnswer(invocation -> cooldown[0]);
		List<Long> rounds = new ArrayList<>();

		try (MockedConstruction<GunAction> ignored = mockConstruction(GunAction.class, (shot, context) -> {
			rounds.add(clock.now());
			cooldown[0] = true;
		})) {
			run(24, tick -> {
				if (!repeatAt(tick, 8)) return;

				PlayerInteractEvent event = press();
				listener.onUseDecided(event);
				// the repeats after it: the client knows the cooldown by then and predicts no use
				if (tick == 0) {
					assertEquals(Event.Result.DENY, event.useItemInHand(), "vanilla refuses it: the client must resync");
				}
			});
		}

		assertEquals(LongStream.rangeClosed(0, 12).boxed().toList(), rounds);
	}

	/**
	 * A plugin denying the use after Bartizan's handler: on Paper the client resyncs onto its repeats, and those are
	 * the hold now - one burst throughout, not a fresh one (and a recoil reset) per repeat.
	 */
	@Test
	@DisplayName("exact: a use another plugin denies leaves the hold on the repeats - one burst while held")
	void exact_useDeniedByAnotherPlugin_fallsBackToTheRepeats() {
		exactMode(true, true);
		gun(0, SelectiveFire.AUTO);
		List<Long> rounds = new ArrayList<>();

		try (MockedConstruction<GunAction> ignored = mockConstruction(GunAction.class,
				(shot, context) -> rounds.add(clock.now()))) {
			run(24, tick -> {
				if (!repeatAt(tick, 8)) return;

				PlayerInteractEvent event = press();
				event.setUseItemInHand(Event.Result.DENY); // a HIGH or HIGHEST listener
				listener.onUseDecided(event);
			});
		}

		assertEquals(LongStream.rangeClosed(0, 12).boxed().toList(), rounds);
		verify(recoil, times(1)).resetRecoilPattern();
	}

	@Test
	@DisplayName("a gun is never consumed, even if its use state ever ran out")
	void consumeGuard_cancelsGunsOnly() {
		GunWeapon gun = gun(0, SelectiveFire.AUTO);
		when(weaponService.getHeldWeaponName(item)).thenReturn("test_rifle");
		when(weaponService.getWeaponTemplate("test_rifle")).thenReturn(gun);
		when(item.clone()).thenReturn(item); // PlayerItemConsumeEvent#getItem hands out a copy

		PlayerItemConsumeEvent gunConsume = new PlayerItemConsumeEvent(player, item);
		listener.onItemConsume(gunConsume);
		assertTrue(gunConsume.isCancelled());

		ItemStack              bread      = mock(ItemStack.class);
		PlayerItemConsumeEvent breadEaten = new PlayerItemConsumeEvent(player, bread);
		listener.onItemConsume(breadEaten);
		assertFalse(breadEaten.isCancelled());
	}

}
