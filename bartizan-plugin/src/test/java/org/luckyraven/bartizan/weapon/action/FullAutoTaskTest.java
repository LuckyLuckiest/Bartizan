package org.luckyraven.bartizan.weapon.action;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.raytrace.WeaponRaytracer;
import org.luckyraven.bartizan.api.weapon.GunWeapon;
import org.luckyraven.bartizan.api.support.WeaponFixtures;
import org.luckyraven.bartizan.effect.EffectRunner;
import org.luckyraven.bartizan.support.TickScheduler;
import org.luckyraven.bartizan.weapon.WeaponService;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * The AUTO first-shot delay: {@code WeaponInteract.shootFullAuto} fires the first round synchronously via
 * {@code fireFirstRound()} and relies on the scheduled task resuming the cadence table on the very next tick. If the
 * initial delay ever drifts back to the projectile cooldown, every fresh AUTO press waits that long before its first
 * round and the cadence table is evaluated from the wrong index. Plus the trigger-held contract (0.5.2): asked first
 * on every scheduled tick, never for the press's own round.
 */
@DisplayName("FullAutoTask")
class FullAutoTaskTest {

	private final TickScheduler clock  = new TickScheduler();
	private final Player        player = mock(Player.class);
	private final ItemStack     item   = mock(ItemStack.class);

	private FullAutoTask task(GunWeapon weapon, BooleanSupplier triggerHeld, Runnable onCancel) {
		return new FullAutoTask(mock(JavaPlugin.class), mock(WeaponService.class), weapon, mock(WeaponRaytracer.class),
		                        player, item, triggerHeld, onCancel, mock(EffectRunner.class));
	}

	@Test
	@DisplayName("schedules with a one-tick delay and period, not the projectile cooldown")
	void schedulesWithOneTickDelay() {
		GunWeapon weapon = WeaponFixtures.gunWeapon(30, 1); // cooldown 4 in the fixture

		FullAutoTask task = task(weapon, () -> true, () -> {
		});

		assertEquals(1L, task.getDelay());
		assertEquals(1L, task.getPeriod());
	}

	@Test
	@DisplayName("the press's first round fires even when the trigger already reads released")
	void firstRound_neverChecksTheHold() {
		when(item.hasItemMeta()).thenReturn(true);
		AtomicInteger asked     = new AtomicInteger();
		AtomicInteger cancelled = new AtomicInteger();

		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
		     MockedConstruction<GunAction> rounds = mockConstruction(GunAction.class)) {
			bukkit.when(Bukkit::getScheduler).thenReturn(clock.scheduler());

			FullAutoTask task = task(WeaponFixtures.gunWeapon(30, 1), () -> {
				asked.incrementAndGet();
				return false;
			}, cancelled::incrementAndGet);
			task.start(false);
			task.fireFirstRound();

			assertEquals(1, rounds.constructed().size());
			assertEquals(0, asked.get(), "the press's own round must not ask the hold");

			// the first scheduled tick asks, sees a released trigger and ends the burst without firing
			clock.tick();
			clock.tick();

			assertEquals(1, rounds.constructed().size());
			assertEquals(1, asked.get());
			assertEquals(1, cancelled.get());
			assertEquals(0, clock.pending());
		}
	}

	@Test
	@DisplayName("the trigger is asked before the cadence: a release on a non-firing tick still ends the burst there")
	void trigger_isAskedEveryTickBeforeTheCadence() {
		when(item.hasItemMeta()).thenReturn(true);
		GunWeapon     weapon = WeaponFixtures.gunWeapon(30, 1); // cooldown 4: rounds on ticks 0, 4, 8, ...
		AtomicBoolean held   = new AtomicBoolean(true);
		List<Long>    rounds = new ArrayList<>();

		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
		     MockedConstruction<GunAction> ignored = mockConstruction(GunAction.class,
				     (shot, context) -> rounds.add(clock.now()))) {
			bukkit.when(Bukkit::getScheduler).thenReturn(clock.scheduler());

			FullAutoTask task = task(weapon, held::get, () -> {
			});
			task.start(false);
			task.fireFirstRound();

			for (int tick = 1; tick <= 6; tick++) clock.tick();
			held.set(false); // released after tick 6: tick 7 ends the burst, tick 8's round never comes
			for (int tick = 7; tick <= 12; tick++) clock.tick();
		}

		assertEquals(List.of(0L, 4L), rounds);
		assertEquals(0, clock.pending());
	}

}
