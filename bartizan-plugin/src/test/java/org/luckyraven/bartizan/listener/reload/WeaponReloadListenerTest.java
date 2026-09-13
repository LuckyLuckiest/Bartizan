package org.luckyraven.bartizan.listener.reload;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.event.WeaponReloadCompleteEvent;
import org.luckyraven.bartizan.api.event.WeaponReloadStartEvent;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.api.weapon.dto.EffectHook;
import org.luckyraven.bartizan.effect.EffectContext;
import org.luckyraven.bartizan.effect.EffectRunner;
import org.luckyraven.bartizan.weapon.WeaponService;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers gate {@code HA}'s wiring into {@link WeaponReloadListener}: the start/complete events run
 * {@code ON_RELOAD_START}/{@code ON_RELOAD_END} on the injected {@link EffectRunner}.
 */
@DisplayName("WeaponReloadListener")
class WeaponReloadListenerTest {

	@Test
	@DisplayName("onReloadStart runs ON_RELOAD_START on the effect runner")
	void onReloadStart_runsOnReloadStartHook() {
		EffectRunner          effectRunner = mock(EffectRunner.class);
		WeaponReloadListener  listener     = new WeaponReloadListener(mock(WeaponService.class), effectRunner);

		Weapon weapon = mock(Weapon.class);
		Player player = mock(Player.class);
		when(player.getUniqueId()).thenReturn(UUID.randomUUID());

		WeaponReloadStartEvent event = mock(WeaponReloadStartEvent.class);
		when(event.getWeapon()).thenReturn(weapon);
		when(event.getPlayer()).thenReturn(player);

		listener.onReloadStart(event);

		verify(effectRunner).run(eq(weapon), eq(EffectHook.ON_RELOAD_START), any(EffectContext.class));
	}

	@Test
	@DisplayName("onReloadEnd runs ON_RELOAD_END on the effect runner")
	void onReloadEnd_runsOnReloadEndHook() {
		EffectRunner          effectRunner = mock(EffectRunner.class);
		WeaponReloadListener  listener     = new WeaponReloadListener(mock(WeaponService.class), effectRunner);

		Weapon weapon = mock(Weapon.class);
		Player player = mock(Player.class);
		when(player.getUniqueId()).thenReturn(UUID.randomUUID());

		WeaponReloadCompleteEvent event = mock(WeaponReloadCompleteEvent.class);
		when(event.getWeapon()).thenReturn(weapon);
		when(event.getPlayer()).thenReturn(player);

		listener.onReloadEnd(event);

		verify(effectRunner).run(eq(weapon), eq(EffectHook.ON_RELOAD_END), any(EffectContext.class));
	}

}
