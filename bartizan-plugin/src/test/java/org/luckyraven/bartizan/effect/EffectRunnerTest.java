package org.luckyraven.bartizan.effect;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.api.weapon.dto.EffectHook;
import org.luckyraven.bartizan.api.weapon.dto.EffectSpec;
import org.luckyraven.bartizan.api.weapon.dto.EffectsData;
import org.luckyraven.bartizan.effect.impl.BossBarHookEffect;
import org.luckyraven.bartizan.file.BartizanSettings;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers {@link EffectRunner#run}: weapon lists run in the declared order, an empty weapon list falls back to
 * {@code settings.yml}'s {@code Default_Effects} (never merged), a throwing effect never aborts the rest of the
 * list, and an unknown runtime type is skipped rather than propagating.
 */
@DisplayName("EffectRunner")
class EffectRunnerTest {

	private List<EffectSpec> invocations;

	@BeforeEach
	void setUp() {
		invocations = new ArrayList<>();
	}

	@AfterEach
	void resetDefaultEffects() throws Exception {
		setStaticDefaultEffects(EffectsData.empty());
	}

	@Test
	@DisplayName("a weapon's own list runs every spec in order")
	void weaponList_runsInOrder() {
		EffectRunner runner = newRunner(countingEffect());

		EffectSpec first  = new EffectSpec("sound", Map.of("Sound", "A"));
		EffectSpec second = new EffectSpec("sound", Map.of("Sound", "B"));

		EffectsData weaponEffects = EffectsData.empty();
		weaponEffects.put(EffectHook.ON_SHOOT, List.of(first, second));

		Weapon weapon = weaponWithEffects(weaponEffects);
		EffectContext ctx = EffectContext.builder().weapon(weapon).build();

		runner.run(weapon, EffectHook.ON_SHOOT, ctx);

		assertEquals(List.of(first, second), invocations);
	}

	@Test
	@DisplayName("an empty weapon list falls back to settings' Default_Effects for the same hook")
	void emptyWeaponList_fallsBackToDefaults() throws Exception {
		EffectRunner runner = newRunner(countingEffect());

		EffectSpec fallback = new EffectSpec("sound", Map.of("Sound", "DEFAULT"));
		EffectsData defaults = EffectsData.empty();
		defaults.put(EffectHook.ON_SHOOT, List.of(fallback));
		setStaticDefaultEffects(defaults);

		Weapon weapon = weaponWithEffects(EffectsData.empty());
		EffectContext ctx = EffectContext.builder().weapon(weapon).build();

		runner.run(weapon, EffectHook.ON_SHOOT, ctx);

		assertEquals(List.of(fallback), invocations);
	}

	@Test
	@DisplayName("a throwing effect does not stop the next spec from running")
	void throwingEffect_doesNotStopTheRest() {
		Map<String, Effect> registry = new HashMap<>();
		registry.put("boom", (spec, ctx) -> {
			throw new RuntimeException("boom");
		});
		registry.put("sound", countingEffect());

		EffectRunner runner = new EffectRunner(registry);

		EffectSpec boom  = new EffectSpec("boom", Map.of());
		EffectSpec sound = new EffectSpec("sound", Map.of());

		EffectsData weaponEffects = EffectsData.empty();
		weaponEffects.put(EffectHook.ON_SHOOT, List.of(boom, sound));

		Weapon weapon = weaponWithEffects(weaponEffects);
		EffectContext ctx = EffectContext.builder().weapon(weapon).build();

		assertDoesNotThrow(() -> runner.run(weapon, EffectHook.ON_SHOOT, ctx));
		assertEquals(List.of(sound), invocations);
	}

	@Test
	@DisplayName("an unknown runtime effect type is skipped, not thrown")
	void unknownRuntimeType_isSkipped() {
		EffectRunner runner = newRunner(countingEffect());

		EffectSpec unknown = new EffectSpec("nope", Map.of());
		EffectsData weaponEffects = EffectsData.empty();
		weaponEffects.put(EffectHook.ON_SHOOT, List.of(unknown));

		Weapon weapon = weaponWithEffects(weaponEffects);
		EffectContext ctx = EffectContext.builder().weapon(weapon).build();

		assertDoesNotThrow(() -> runner.run(weapon, EffectHook.ON_SHOOT, ctx));
		assertTrue(invocations.isEmpty());
	}

	@Test
	@DisplayName("onShutdown forwards to every lifecycle-managed effect (BZ-EF-02)")
	void onShutdown_forwardsToLifecycleEffects() {
		BossBarHookEffect bossBar = mock(BossBarHookEffect.class);
		Map<String, Effect> registry = new HashMap<>();
		registry.put("boss_bar", bossBar);
		registry.put("sound", countingEffect());

		new EffectRunner(registry).onShutdown();

		verify(bossBar).onShutdown();
	}

	private EffectRunner newRunner(Effect soundEffect) {
		Map<String, Effect> registry = new HashMap<>();
		registry.put("sound", soundEffect);
		return new EffectRunner(registry);
	}

	private Effect countingEffect() {
		return (spec, ctx) -> invocations.add(spec);
	}

	private Weapon weaponWithEffects(EffectsData effects) {
		Weapon weapon = mock(Weapon.class);
		when(weapon.getEffects()).thenReturn(effects);
		return weapon;
	}

	private static void setStaticDefaultEffects(EffectsData data) throws Exception {
		Field field = BartizanSettings.class.getDeclaredField("defaultEffects");
		field.setAccessible(true);
		field.set(null, data);
	}

}
