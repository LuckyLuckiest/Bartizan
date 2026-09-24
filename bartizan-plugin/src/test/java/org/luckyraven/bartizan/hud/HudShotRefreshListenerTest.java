package org.luckyraven.bartizan.hud;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.event.WeaponShootEvent;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BZ-HU-02: {@link HudShotRefreshListener} defers {@link HudService#refresh} by one tick. Covers the guard that
 * keeps a shot fired in the same tick as a disconnect (the last round of a burst/full-auto sequence, say) from
 * re-creating a boss bar for a player who has already quit by the time the deferred task actually runs.
 */
@DisplayName("HudShotRefreshListener")
class HudShotRefreshListenerTest {

	@Test
	@DisplayName("BZ-HU-02: isOnline() is checked at run time, not schedule time, so a player who disconnects "
			+ "between the shot and the deferred task running is never re-added to a boss bar")
	void onShoot_playerOfflineByRunTime_skipsRefresh() {
		JavaPlugin              plugin     = mock(JavaPlugin.class);
		HudService               hudService = mock(HudService.class);
		HudShotRefreshListener  listener   = new HudShotRefreshListener(plugin, hudService);

		Player           player = mock(Player.class);
		Weapon           weapon = mock(Weapon.class);
		WeaponShootEvent event  = new WeaponShootEvent(weapon, player);

		Runnable deferredTask;
		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
			BukkitScheduler scheduler = mock(BukkitScheduler.class);
			bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);

			listener.onShoot(event);

			ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
			verify(scheduler).runTask(eq(plugin), captor.capture());
			deferredTask = captor.getValue();
		}

		// The player disconnected sometime between scheduling and the deferred task actually running.
		when(player.isOnline()).thenReturn(false);
		deferredTask.run();

		verify(hudService, never()).refresh(any());
	}

	@Test
	@DisplayName("BZ-HU-02: a player still online when the deferred task runs is refreshed as before")
	void onShoot_playerOnline_refreshesHud() {
		JavaPlugin              plugin     = mock(JavaPlugin.class);
		HudService               hudService = mock(HudService.class);
		HudShotRefreshListener  listener   = new HudShotRefreshListener(plugin, hudService);

		Player player = mock(Player.class);
		when(player.isOnline()).thenReturn(true);
		Weapon           weapon = mock(Weapon.class);
		WeaponShootEvent event  = new WeaponShootEvent(weapon, player);

		Runnable deferredTask;
		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
			BukkitScheduler scheduler = mock(BukkitScheduler.class);
			bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);

			listener.onShoot(event);

			ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
			verify(scheduler).runTask(eq(plugin), captor.capture());
			deferredTask = captor.getValue();
		}

		deferredTask.run();

		verify(hudService).refresh(player);
	}

}
