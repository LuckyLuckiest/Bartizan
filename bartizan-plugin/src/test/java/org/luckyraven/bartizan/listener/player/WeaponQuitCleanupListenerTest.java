package org.luckyraven.bartizan.listener.player;

import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.weapon.GunWeapon;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.effect.EffectRunner;
import org.luckyraven.bartizan.hud.HudService;
import org.luckyraven.bartizan.weapon.WeaponManager;
import org.luckyraven.bartizan.wearable.WearableEffectsService;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Bug docket BZ-EV-10: {@code onPlayerDeath} left an in-progress reload running - {@code isReloading()} only
 * cleared once the reload's own per-stage {@code isDead()} check happened to fire, up to a full reload stage after
 * respawn. {@code onPlayerQuit} already stopped the reload; this pins {@code onPlayerDeath} doing the same.
 */
@DisplayName("WeaponQuitCleanupListener - BZ-EV-10 reload stopped on death too")
class WeaponQuitCleanupListenerTest {

	@Test
	@DisplayName("onPlayerDeath stops an in-progress reload, mirroring onPlayerQuit")
	void onPlayerDeath_reloading_stopsReload() {
		WeaponManager   weaponManager = mock(WeaponManager.class);
		HudService      hudService    = mock(HudService.class);
		WearableEffectsService wearable = mock(WearableEffectsService.class);
		EffectRunner    effectRunner  = mock(EffectRunner.class);

		WeaponQuitCleanupListener listener =
				new WeaponQuitCleanupListener(weaponManager, effectRunner, hudService, wearable);

		Player     player = mock(Player.class);
		when(player.getUniqueId()).thenReturn(UUID.randomUUID());

		GunWeapon weapon = mock(GunWeapon.class);
		when(weapon.isReloading()).thenReturn(true);
		when(weaponManager.getHeldWeapon(player)).thenReturn(weapon);

		PlayerDeathEvent event = mock(PlayerDeathEvent.class);
		when(event.getEntity()).thenReturn(player);

		listener.onPlayerDeath(event);

		verify(weapon).stopReloading();
		verify(weapon).unScope(player, true);
	}

	@Test
	@DisplayName("onPlayerDeath not reloading never calls stopReloading")
	void onPlayerDeath_notReloading_neverStopsReload() {
		WeaponManager   weaponManager = mock(WeaponManager.class);
		HudService      hudService    = mock(HudService.class);
		WearableEffectsService wearable = mock(WearableEffectsService.class);
		EffectRunner    effectRunner  = mock(EffectRunner.class);

		WeaponQuitCleanupListener listener =
				new WeaponQuitCleanupListener(weaponManager, effectRunner, hudService, wearable);

		Player player = mock(Player.class);
		when(player.getUniqueId()).thenReturn(UUID.randomUUID());

		Weapon weapon = mock(Weapon.class);
		when(weapon.isReloading()).thenReturn(false);
		when(weaponManager.getHeldWeapon(player)).thenReturn(weapon);

		PlayerDeathEvent event = mock(PlayerDeathEvent.class);
		when(event.getEntity()).thenReturn(player);

		listener.onPlayerDeath(event);

		verify(weapon, never()).stopReloading();
		verify(weapon).unScope(player, true);
	}

	@Test
	@DisplayName("onPlayerQuit still stops an in-progress reload (unchanged behaviour)")
	void onPlayerQuit_reloading_stopsReload() {
		WeaponManager   weaponManager = mock(WeaponManager.class);
		HudService      hudService    = mock(HudService.class);
		WearableEffectsService wearable = mock(WearableEffectsService.class);
		EffectRunner    effectRunner  = mock(EffectRunner.class);

		WeaponQuitCleanupListener listener =
				new WeaponQuitCleanupListener(weaponManager, effectRunner, hudService, wearable);

		Player player = mock(Player.class);
		when(player.getUniqueId()).thenReturn(UUID.randomUUID());

		GunWeapon weapon = mock(GunWeapon.class);
		when(weapon.isReloading()).thenReturn(true);
		when(weaponManager.getHeldWeapon(player)).thenReturn(weapon);

		PlayerQuitEvent event = mock(PlayerQuitEvent.class);
		when(event.getPlayer()).thenReturn(player);

		listener.onPlayerQuit(event);

		verify(weapon).stopReloading();
		verify(weapon).unScope(player, true);
	}

}
