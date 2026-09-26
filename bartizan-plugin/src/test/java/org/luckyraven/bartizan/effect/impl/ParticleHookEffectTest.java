package org.luckyraven.bartizan.effect.impl;

import org.bukkit.Color;
import org.bukkit.Particle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.weapon.dto.EffectSpec;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * BZ-EF-07: on 1.21.9+, {@code Particle.FLASH}'s data type becomes {@link Color} (was {@code Void} on the 1.16.5
 * floor this compiles against) — {@link ParticleHookEffect#resolveData} is the shared data-selection helper `run()`
 * calls, kept pure and keyed on {@code Class<?>} so this covers the Color-data-type branch without needing a real
 * particle enum constant that only exists on newer servers.
 */
@DisplayName("ParticleHookEffect - data resolution")
class ParticleHookEffectTest {

	@Test
	@DisplayName("a Color data type is resolved from the Color arg")
	void resolveData_colorType_parsesColorArg() {
		EffectSpec spec = new EffectSpec("particle", Map.of("Color", "#00FF00"));

		Object data = new ParticleHookEffect().resolveData(Color.class, spec);

		assertEquals(Color.fromRGB(0x00FF00), data);
	}

	@Test
	@DisplayName("a DustOptions data type is still resolved as before")
	void resolveData_dustOptionsType_buildsDustOptions() {
		EffectSpec spec = new EffectSpec("particle", Map.of("Color", "#FF0000"));

		Object data = new ParticleHookEffect().resolveData(Particle.DustOptions.class, spec);

		assertEquals(Color.fromRGB(0xFF0000), ((Particle.DustOptions) data).getColor());
	}

	@Test
	@DisplayName("any other data type falls back to null, unchanged")
	void resolveData_otherType_null() {
		Object data = new ParticleHookEffect().resolveData(Void.class, new EffectSpec("particle", Map.of()));

		assertNull(data);
	}

}
