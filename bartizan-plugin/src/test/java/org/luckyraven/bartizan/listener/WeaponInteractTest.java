package org.luckyraven.bartizan.listener;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.combat.CombatEligibility;
import org.luckyraven.bartizan.api.raytrace.WeaponRaytracer;
import org.luckyraven.bartizan.api.weapon.GunWeapon;
import org.luckyraven.bartizan.api.weapon.modifiers.BlockDamageManager;
import org.luckyraven.bartizan.effect.EffectRunner;
import org.luckyraven.bartizan.fire.PluginFireRegistry;
import org.luckyraven.bartizan.scope.SpyglassScopeTask;
import org.luckyraven.bartizan.status.StatusEffectService;
import org.luckyraven.bartizan.weapon.WeaponService;
import org.luckyraven.bartizan.weapon.action.FullAutoTask;
import org.luckyraven.keystone.timer.RepeatingTimer;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Bug docket BZ-EV-01: {@link WeaponInteract}'s eight per-weapon tracking maps (continuousFire, equipDelayUntil,
 * pressHoldState, releaseCallbacks, autoTasks, activeTasks, meleeCooldowns, lastMeleeSwingMs) were only ever
 * cleared from {@link WeaponInteract#onWeaponHeld} on a hotbar swap - never on quit, leaving a FullAutoTask or
 * RepeatingTimer running (and calling Bukkit Player APIs) against an offline Player. Covers the new
 * {@link WeaponInteract#clearWeaponState} entry point {@code WeaponQuitCleanupListener} calls on quit, reached
 * through the {@link WeaponInteract#get()} static accessor (mirrors {@code ExplosionHandler.get()} - neither class
 * is reachable through the bean graph from a sibling listener).
 */
@DisplayName("WeaponInteract - BZ-EV-01 per-weapon tracking state cleared on quit")
class WeaponInteractTest {

	private static final List<String> TRACKING_MAP_FIELDS = List.of("continuousFire", "equipDelayUntil",
			"pressHoldState", "releaseCallbacks", "autoTasks", "activeTasks", "meleeCooldowns", "lastMeleeSwingMs");

	@Test
	@DisplayName("clearWeaponState drops the weapon's entry from all eight maps and stops its live tasks")
	void clearWeaponState_removesEntryFromEveryMapAndStopsLiveTasks() throws Exception {
		WeaponInteract interact = newInteract();

		UUID      weaponUuid = UUID.randomUUID();
		GunWeapon weapon     = mock(GunWeapon.class);
		when(weapon.getUuid()).thenReturn(weaponUuid);

		FullAutoTask   autoTask   = mock(FullAutoTask.class);
		RepeatingTimer activeTask = mock(RepeatingTimer.class);

		putInto(interact, "continuousFire", weaponUuid, new AtomicReference<>());
		putInto(interact, "equipDelayUntil", weaponUuid, System.currentTimeMillis());
		putInto(interact, "pressHoldState", weaponUuid, new AtomicReference<>());
		putInto(interact, "releaseCallbacks", weaponUuid, (Runnable) () -> { });
		putInto(interact, "autoTasks", weaponUuid, autoTask);
		putInto(interact, "activeTasks", weaponUuid, activeTask);
		putInto(interact, "meleeCooldowns", weaponUuid, System.currentTimeMillis());
		putInto(interact, "lastMeleeSwingMs", weaponUuid, System.currentTimeMillis());

		interact.clearWeaponState(mock(Player.class), weapon);

		for (String fieldName : TRACKING_MAP_FIELDS) {
			assertFalse(mapField(interact, fieldName).containsKey(weaponUuid),
			           fieldName + " must no longer track the quitting player's weapon");
		}

		verify(autoTask).stop();
		verify(activeTask).stop();
	}

	@Test
	@DisplayName("clearWeaponState(null) is a no-op - WeaponQuitCleanupListener already guards this, but the public API must too")
	void clearWeaponState_nullWeapon_isNoOp() {
		WeaponInteract interact = newInteract();

		interact.clearWeaponState(mock(Player.class), null);
	}

	@Test
	@DisplayName("get() returns the constructed instance (ExplosionHandler-style static accessor)")
	void get_returnsConstructedInstance() {
		WeaponInteract interact = newInteract();

		assertSame(interact, WeaponInteract.get());
	}

	private static WeaponInteract newInteract() {
		return new WeaponInteract(mock(JavaPlugin.class), mock(WeaponService.class), mock(WeaponRaytracer.class),
		                          mock(PluginFireRegistry.class), CombatEligibility.DEFAULT, mock(EffectRunner.class),
		                          mock(BlockDamageManager.class), mock(StatusEffectService.class),
		                          mock(SpyglassScopeTask.class));
	}

	@SuppressWarnings("unchecked")
	private static <K, V> void putInto(WeaponInteract interact, String fieldName, K key, V value) throws Exception {
		((Map<K, V>) rawMapField(interact, fieldName)).put(key, value);
	}

	private static Map<?, ?> mapField(WeaponInteract interact, String fieldName) throws Exception {
		return rawMapField(interact, fieldName);
	}

	private static Map<?, ?> rawMapField(WeaponInteract interact, String fieldName) throws Exception {
		Field field = WeaponInteract.class.getDeclaredField(fieldName);
		field.setAccessible(true);
		return (Map<?, ?>) field.get(interact);
	}

}
