package org.luckyraven.bartizan.listener.player;

import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.combat.CombatEligibility;
import org.luckyraven.bartizan.api.raytrace.WeaponRaytracer;
import org.luckyraven.bartizan.api.weapon.GunWeapon;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.api.weapon.modifiers.BlockDamageManager;
import org.luckyraven.bartizan.effect.EffectRunner;
import org.luckyraven.bartizan.fire.PluginFireRegistry;
import org.luckyraven.bartizan.hud.HudService;
import org.luckyraven.bartizan.listener.WeaponInteract;
import org.luckyraven.bartizan.scope.SpyglassScopeTask;
import org.luckyraven.bartizan.status.StatusEffectService;
import org.luckyraven.bartizan.weapon.WeaponManager;
import org.luckyraven.bartizan.weapon.WeaponService;
import org.luckyraven.bartizan.wearable.WearableEffectsService;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Bug docket BZ-EV-10: {@code onPlayerDeath} left an in-progress reload running - {@code isReloading()} only
 * cleared once the reload's own per-stage {@code isDead()} check happened to fire, up to a full reload stage after
 * respawn. {@code onPlayerQuit} already stopped the reload; this pins {@code onPlayerDeath} doing the same.
 *
 * <p>Also covers bug docket BZ-EV-01: {@code onPlayerQuit} must clear {@code WeaponInteract}'s per-weapon
 * tracking maps for the quitting player's held weapon, reached through {@link WeaponInteract#get()}.
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
		when(weapon.getUuid()).thenReturn(UUID.randomUUID()); // consulted by WeaponInteract#clearWeaponState too,
		                                                      // reached via WeaponInteract#get() if some other test
		                                                      // in this JVM already constructed one
		when(weaponManager.getHeldWeapon(player)).thenReturn(weapon);

		PlayerQuitEvent event = mock(PlayerQuitEvent.class);
		when(event.getPlayer()).thenReturn(player);

		listener.onPlayerQuit(event);

		verify(weapon).stopReloading();
		verify(weapon).unScope(player, true);
	}

	@Test
	@DisplayName("BZ-EV-01: onPlayerQuit clears WeaponInteract's per-weapon tracking state for the held weapon")
	void onPlayerQuit_clearsWeaponInteractTrackingState() throws Exception {
		WeaponManager   weaponManager = mock(WeaponManager.class);
		HudService      hudService    = mock(HudService.class);
		WearableEffectsService wearable = mock(WearableEffectsService.class);
		EffectRunner    effectRunner  = mock(EffectRunner.class);

		WeaponQuitCleanupListener listener =
				new WeaponQuitCleanupListener(weaponManager, effectRunner, hudService, wearable);

		// constructing WeaponInteract registers it as WeaponInteract.get()'s target, the same way it does in the
		// real bean graph — WeaponQuitCleanupListener has no other way to reach it (see WeaponInteract#get()).
		WeaponInteract interact = new WeaponInteract(mock(JavaPlugin.class), mock(WeaponService.class),
				mock(WeaponRaytracer.class), mock(PluginFireRegistry.class), CombatEligibility.DEFAULT,
				mock(EffectRunner.class), mock(BlockDamageManager.class), mock(StatusEffectService.class),
				mock(SpyglassScopeTask.class));

		Player player = mock(Player.class);
		when(player.getUniqueId()).thenReturn(UUID.randomUUID());

		UUID   weaponUuid = UUID.randomUUID();
		Weapon weapon     = mock(Weapon.class);
		when(weapon.getUuid()).thenReturn(weaponUuid);
		when(weaponManager.getHeldWeapon(player)).thenReturn(weapon);

		Field pressHoldState = WeaponInteract.class.getDeclaredField("pressHoldState");
		pressHoldState.setAccessible(true);
		@SuppressWarnings("unchecked")
		Map<UUID, Object> map = (Map<UUID, Object>) pressHoldState.get(interact);
		map.put(weaponUuid, new Object());

		PlayerQuitEvent event = mock(PlayerQuitEvent.class);
		when(event.getPlayer()).thenReturn(player);

		listener.onPlayerQuit(event);

		assertFalse(map.containsKey(weaponUuid),
		           "onPlayerQuit must reach WeaponInteract and clear its tracking state for the quitting player's "
		           + "weapon, or a mid-AUTO-fire/charge task keeps running against an offline Player");
	}

	/**
	 * BZ-WM-04: nothing pruned the weapon registry per player - a quit now forgets the quitter's weapons, in a MONITOR
	 * handler so it runs after the HIGHEST quit cleanup has stopped the reload/unscoped on the live instance.
	 */
	@Test
	@DisplayName("a quitting player's weapons are dropped from the registry (BZ-WM-04)")
	void onQuit_forgetsQuittersWeapons() {
		WeaponManager weaponManager = mock(WeaponManager.class);
		WeaponQuitCleanupListener listener = new WeaponQuitCleanupListener(weaponManager, mock(EffectRunner.class),
		                                                                   mock(HudService.class),
		                                                                   mock(WearableEffectsService.class));
		Player player = mock(Player.class);

		listener.forgetWeaponsOnQuit(new PlayerQuitEvent(player, "left"));

		verify(weaponManager).forgetWeapons(player);
	}

}
