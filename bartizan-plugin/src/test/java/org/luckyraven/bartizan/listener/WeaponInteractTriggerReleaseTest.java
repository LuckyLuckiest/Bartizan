package org.luckyraven.bartizan.listener;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.luckyraven.bartizan.api.combat.CombatEligibility;
import org.luckyraven.bartizan.api.raytrace.WeaponRaytracer;
import org.luckyraven.bartizan.api.support.WeaponFixtures;
import org.luckyraven.bartizan.api.weapon.BiologicalWeapon;
import org.luckyraven.bartizan.api.weapon.GunWeapon;
import org.luckyraven.bartizan.api.weapon.IncendiaryWeapon;
import org.luckyraven.bartizan.api.weapon.SelectiveFire;
import org.luckyraven.bartizan.api.weapon.WeaponType;
import org.luckyraven.bartizan.api.weapon.dto.DurabilityData;
import org.luckyraven.bartizan.api.weapon.dto.ProjectileData;
import org.luckyraven.bartizan.api.weapon.ProjectileType;
import org.luckyraven.bartizan.api.weapon.modifiers.BlockDamageManager;
import org.luckyraven.bartizan.api.weapon.recoil.RecoilManager;
import org.luckyraven.bartizan.effect.EffectRunner;
import org.luckyraven.bartizan.fire.PluginFireRegistry;
import org.luckyraven.bartizan.scope.SpyglassScopeTask;
import org.luckyraven.bartizan.status.StatusEffectService;
import org.luckyraven.bartizan.support.TickScheduler;
import org.luckyraven.bartizan.weapon.WeaponService;
import org.luckyraven.bartizan.weapon.action.ChargeController;
import org.luckyraven.bartizan.weapon.action.FullAutoTask;
import org.luckyraven.bartizan.weapon.action.GunAction;
import org.luckyraven.bartizan.weapon.action.GunFireDispatcher;
import org.luckyraven.bartizan.weapon.action.IncendiaryAction;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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
 * charge release and the flamethrower spray.
 */
@DisplayName("WeaponInteract - trigger release timing")
class WeaponInteractTriggerReleaseTest {

	private final TickScheduler   clock         = new TickScheduler();
	private final Player          player        = mock(Player.class);
	private final PlayerInventory inventory     = mock(PlayerInventory.class);
	private final WeaponService   weaponService = mock(WeaponService.class);
	private final ItemStack       item          = mock(ItemStack.class);
	private final RecoilManager   recoil        = mock(RecoilManager.class);

	private MockedStatic<Bukkit> bukkit;
	private WeaponInteract       listener;

	@BeforeEach
	void setUp() {
		when(player.getUniqueId()).thenReturn(UUID.randomUUID());
		when(player.getInventory()).thenReturn(inventory);
		when(inventory.getItemInMainHand()).thenReturn(item);
		when(item.hasItemMeta()).thenReturn(true);

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
		bukkit.close();
	}

	private void press() {
		listener.onPlayerInteract(new PlayerInteractEvent(player, Action.RIGHT_CLICK_AIR, item, null, null,
		                                                  EquipmentSlot.HAND));
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

}
