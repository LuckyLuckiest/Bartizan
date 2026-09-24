package org.luckyraven.bartizan.effect;

import org.bukkit.entity.Zombie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link EffectContext} placeholder and target resolution.
 */
@DisplayName("EffectContext")
class EffectContextTest {

	@Test
	@DisplayName("a mob's custom name cannot inject & colour codes into %victim% (BZ-EF-04)")
	void mobName_ampersandStripped() {
		Zombie mob = mock(Zombie.class);
		when(mob.getName()).thenReturn("&4[Server] &fhello");

		EffectContext ctx = EffectContext.builder().victim(mob).build();

		assertEquals("4[Server] fhello", ctx.placeholders().get("%victim%"));
	}

}
