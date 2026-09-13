package org.luckyraven.bartizan.weapon.action;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.event.WeaponChargeLevelEvent;
import org.luckyraven.bartizan.api.support.WeaponFixtures;
import org.luckyraven.bartizan.api.weapon.BiologicalWeapon;
import org.luckyraven.bartizan.api.weapon.dto.ChargeData;
import org.luckyraven.bartizan.api.weapon.dto.EffectHook;
import org.luckyraven.bartizan.effect.EffectContext;
import org.luckyraven.bartizan.effect.EffectRunner;
import org.luckyraven.keystone.timer.RepeatingTimer;
import org.luckyraven.keystone.util.ActionBarManager;
import org.luckyraven.keystone.util.ParticleUtil;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * Drives {@link ChargeController#tick(Player, long)} directly — the shape {@code HB}/{@code HC} share is designed
 * to be unit-testable without a real scheduler (weapons-roadmap.md gate {@code HB} §2.1, gate {@code HC} §3.2 step
 * 1). {@code ActionBarManager}/{@code ParticleUtil} are statically mocked: both route through XSeries classes whose
 * static initialisers reflect over live server internals that don't exist outside a running Spigot server, throwing
 * regardless of how thoroughly {@link Player} itself is stubbed.
 */
@DisplayName("ChargeController")
class ChargeControllerTest {

	private final Player                    player       = mock(Player.class, Mockito.RETURNS_DEEP_STUBS);
	private final EffectRunner              effectRunner = mock(EffectRunner.class);
	private final Map<UUID, RepeatingTimer> activeTasks  = new HashMap<>();

	private ChargeController controller(BiologicalWeapon weapon, ChargeData data, IntConsumer onFire) {
		return new ChargeController(mock(JavaPlugin.class), weapon, data, effectRunner, activeTasks, onFire);
	}

	private ChargeController controller(BiologicalWeapon weapon, ChargeData data, IntConsumer onFire,
	                                    ChargeController.TickListener tickListener) {
		return new ChargeController(mock(JavaPlugin.class), weapon, data, effectRunner, activeTasks, onFire,
		                            tickListener);
	}

	/**
	 * Runs {@code ticks} through {@link ChargeController#tick(Player, long)} with {@code Bukkit}/
	 * {@code ActionBarManager}/{@code ParticleUtil} statically stubbed out, and returns the {@link PluginManager}
	 * mock used to capture any {@link WeaponChargeLevelEvent}s — still valid to {@code verify} after this returns,
	 * since closing a {@link MockedStatic} doesn't invalidate a plain mock's recorded invocations.
	 */
	private PluginManager tick(ChargeController controller, long... ticks) {
		PluginManager pluginManager = mock(PluginManager.class);

		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
		     MockedStatic<ActionBarManager> actionBar = mockStatic(ActionBarManager.class);
		     MockedStatic<ParticleUtil> particles = mockStatic(ParticleUtil.class)) {
			bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);

			for (long tickCount : ticks) {
				controller.tick(player, tickCount);
			}
		}

		return pluginManager;
	}

	@Test
	@DisplayName("levels increment every Time_Per_Level ticks and cap at Max_Level; On_Charge_Level fires per "
			+ "increment, On_Charge_Full fires once")
	void levelsIncrementAndCapAtMax() {
		BiologicalWeapon weapon = WeaponFixtures.biologicalWeapon(10);
		ChargeData       data   = new ChargeData(5, 3, 1, false);
		AtomicInteger    fired  = new AtomicInteger(-1);
		ChargeController controller = controller(weapon, data, fired::set);

		PluginManager pluginManager = tick(controller,
		                                   0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L, 12L, 13L, 14L, 15L,
		                                   16L, 17L, 18L, 19L);

		ArgumentCaptor<WeaponChargeLevelEvent> events = ArgumentCaptor.forClass(WeaponChargeLevelEvent.class);
		verify(pluginManager, times(3)).callEvent(events.capture());

		List<WeaponChargeLevelEvent> raised = events.getAllValues();
		assertEquals(1, raised.get(0).getLevel());
		assertEquals(2, raised.get(1).getLevel());
		assertEquals(3, raised.get(2).getLevel());
		assertEquals(3, raised.get(2).getMaxLevel());

		verify(effectRunner, times(3)).run(eq(weapon), eq(EffectHook.ON_CHARGE_LEVEL), any(EffectContext.class));
		verify(effectRunner, times(1)).run(eq(weapon), eq(EffectHook.ON_CHARGE_FULL), any(EffectContext.class));
		verifyNoMoreInteractions(effectRunner);
		assertEquals(-1, fired.get(), "ticking alone never releases");
	}

	@Test
	@DisplayName("release below Min_Level_To_Fire cancels silently — onFire is never called")
	void releaseBelowMinLevelToFireDoesNotFire() {
		BiologicalWeapon weapon = WeaponFixtures.biologicalWeapon(10);
		ChargeData       data   = new ChargeData(5, 3, 2, false);
		AtomicInteger    fired  = new AtomicInteger(-1);
		ChargeController controller = controller(weapon, data, fired::set);

		tick(controller, 0L); // level -> 1, below Min_Level_To_Fire (2)
		controller.release(player);

		assertEquals(-1, fired.get());
	}

	@Test
	@DisplayName("release at level N calls onFire(N) exactly once")
	void releaseAtLevelCallsOnFireOnce() {
		BiologicalWeapon weapon    = WeaponFixtures.biologicalWeapon(10);
		ChargeData       data      = new ChargeData(5, 3, 1, false);
		AtomicInteger    fired     = new AtomicInteger(-1);
		AtomicInteger    fireCount = new AtomicInteger(0);
		ChargeController controller = controller(weapon, data, level -> {
			fired.set(level);
			fireCount.incrementAndGet();
		});

		tick(controller, 0L, 5L); // level -> 1, then -> 2

		controller.release(player);
		controller.release(player); // idempotent — a second release must not fire again

		assertEquals(2, fired.get());
		assertEquals(1, fireCount.get());
	}

	@Test
	@DisplayName("Auto_Fire_At_Max releases as soon as the max level is reached, and the later manual release "
			+ "is a no-op")
	void autoFireAtMaxReleasesOnceThenManualReleaseIsNoop() {
		BiologicalWeapon weapon    = WeaponFixtures.biologicalWeapon(10);
		ChargeData       data      = new ChargeData(5, 2, 1, true);
		AtomicInteger    fired     = new AtomicInteger(-1);
		AtomicInteger    fireCount = new AtomicInteger(0);
		ChargeController controller = controller(weapon, data, level -> {
			fired.set(level);
			fireCount.incrementAndGet();
		});

		tick(controller, 0L, 5L); // level -> 1, then -> 2 == Max_Level -> auto-releases

		assertEquals(2, fired.get());
		assertEquals(1, fireCount.get());

		controller.release(player); // the physical RMB-release that follows must be a no-op

		assertEquals(1, fireCount.get());
	}

	@Test
	@DisplayName("tickListener is invoked with the current level on every tick while charging, and stops being "
			+ "called after release")
	void tickListenerCalledWhileChargingAndStopsAfterRelease() {
		BiologicalWeapon weapon = WeaponFixtures.biologicalWeapon(10);
		ChargeData       data   = new ChargeData(5, 3, 1, false);

		List<Integer>    seenLevels = new ArrayList<>();
		ChargeController.TickListener listener = (p, level, tickCount) -> seenLevels.add(level);
		ChargeController controller = controller(weapon, data, level -> { }, listener);

		tick(controller, 0L, 1L, 5L); // level -> 1 at tick 0, unchanged at 1, -> 2 at tick 5
		assertEquals(List.of(1, 1, 2), seenLevels, "invoked every tick with the level reached so far");

		controller.release(player);
		seenLevels.clear();

		tick(controller, 6L); // simulates a stray tick after release (the real timer would already be stopped)
		assertEquals(List.of(), seenLevels, "must not be invoked once released");
	}

}
