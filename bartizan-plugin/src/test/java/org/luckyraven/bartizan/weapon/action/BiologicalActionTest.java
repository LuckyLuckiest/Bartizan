package org.luckyraven.bartizan.weapon.action;

import org.bukkit.potion.PotionEffect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.weapon.dto.BiologicalData;
import org.luckyraven.bartizan.util.PotionEffectParser;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

/**
 * {@link BiologicalAction#effectsForLevel(BiologicalData, int)} — HA §0.1 task 1: a charge level beyond
 * {@code Effects_Per_Level}'s size used to throw {@code IndexOutOfBoundsException} instead of clamping to the last
 * configured entry. {@link PotionEffectParser} is stubbed statically so this test covers only the index-selection
 * logic, not token parsing — {@code PotionEffectParser.parseSingle} resolves effect names through XSeries'
 * {@code XPotion}, whose static init touches Bukkit's live potion registry and is not available in a plain unit
 * test (confirmed: even a {@code BukkitRegistryFixture}-backed proxy registry fails inside
 * {@code PotionEffectType}'s own {@code <clinit>}).
 */
@DisplayName("BiologicalAction.effectsForLevel")
class BiologicalActionTest {

	private static BiologicalData dataWith(List<String> effectsPerLevel) {
		BiologicalData data = new BiologicalData();
		data.setEffectsPerLevel(effectsPerLevel);
		return data;
	}

	@Test
	@DisplayName("exact index: level N reads Effects_Per_Level[N-1]")
	void exactIndex() {
		BiologicalData data = dataWith(List.of("POISON-100-1", "WITHER-200-2"));

		try (MockedStatic<PotionEffectParser> parser = mockStatic(PotionEffectParser.class)) {
			PotionEffect stub = mock(PotionEffect.class);
			parser.when(() -> PotionEffectParser.parseList(List.of("WITHER-200-2"))).thenReturn(List.of(stub));

			List<PotionEffect> effects = BiologicalAction.effectsForLevel(data, 2);

			assertEquals(List.of(stub), effects);
		}
	}

	@Test
	@DisplayName("short list: a level beyond the list's size clamps to the last entry instead of throwing")
	void shortList() {
		BiologicalData data = dataWith(List.of("POISON-100-1"));

		try (MockedStatic<PotionEffectParser> parser = mockStatic(PotionEffectParser.class)) {
			PotionEffect stub = mock(PotionEffect.class);
			parser.when(() -> PotionEffectParser.parseList(List.of("POISON-100-1"))).thenReturn(List.of(stub));

			List<PotionEffect> effects = BiologicalAction.effectsForLevel(data, 5);

			assertEquals(List.of(stub), effects);
		}
	}

	@Test
	@DisplayName("empty list: no effects, no exception")
	void emptyList() {
		BiologicalData data = dataWith(List.of());

		assertTrue(BiologicalAction.effectsForLevel(data, 1).isEmpty());
	}

	@Test
	@DisplayName("level 0: no effects")
	void levelZero() {
		BiologicalData data = dataWith(List.of("POISON-100-1"));

		assertTrue(BiologicalAction.effectsForLevel(data, 0).isEmpty());
	}

}
