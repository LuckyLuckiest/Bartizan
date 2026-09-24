package org.luckyraven.bartizan.effect.impl;

import org.bukkit.Bukkit;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.weapon.dto.EffectSpec;
import org.luckyraven.bartizan.effect.EffectContext;
import org.luckyraven.bartizan.file.BartizanSettings;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link BossBarHookEffect} (BZ-EF-02): a bar still showing when the plugin disables is removed by
 * {@link BossBarHookEffect#onShutdown()}, since its scheduled removal task is cancelled with the plugin.
 */
@DisplayName("BossBarHookEffect")
class BossBarHookEffectTest {

	@BeforeAll
	static void primeMoneySymbol() throws ReflectiveOperationException {
		Field field = BartizanSettings.class.getDeclaredField("moneySymbol");
		field.setAccessible(true);
		field.set(null, "$");
	}

	@Test
	@DisplayName("onShutdown removes a bar whose scheduled removal has not run yet")
	void onShutdown_removesLiveBar() {
		BossBar           bar    = mock(BossBar.class);
		BossBarHookEffect effect = new BossBarHookEffect(mock(JavaPlugin.class));
		Player            viewer = mock(Player.class);
		when(viewer.getName()).thenReturn("Alice");

		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
			bukkit.when(() -> Bukkit.createBossBar(any(), any(), any())).thenReturn(bar);
			bukkit.when(Bukkit::getScheduler).thenReturn(mock(BukkitScheduler.class));

			effect.run(new EffectSpec("boss_bar", Map.of("Text", "hi")),
			           EffectContext.builder().source(viewer).build());
		}

		effect.onShutdown();

		verify(bar).removeAll();
	}

}
