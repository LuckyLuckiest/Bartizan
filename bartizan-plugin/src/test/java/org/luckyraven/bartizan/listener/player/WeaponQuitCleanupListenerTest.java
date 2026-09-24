package org.luckyraven.bartizan.listener.player;

import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.effect.EffectRunner;
import org.luckyraven.bartizan.hud.HudService;
import org.luckyraven.bartizan.weapon.WeaponManager;
import org.luckyraven.bartizan.wearable.WearableEffectsService;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * BZ-WM-04: nothing pruned the weapon registry per player - a quit now forgets the quitter's weapons, in a MONITOR
 * handler so it runs after the HIGHEST quit cleanup has stopped the reload/unscoped on the live instance.
 */
@DisplayName("WeaponQuitCleanupListener - registry eviction on quit (BZ-WM-04)")
class WeaponQuitCleanupListenerTest {

	@Test
	@DisplayName("a quitting player's weapons are dropped from the registry")
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
