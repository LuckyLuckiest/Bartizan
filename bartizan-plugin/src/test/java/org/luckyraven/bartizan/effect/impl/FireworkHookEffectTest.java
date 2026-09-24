package org.luckyraven.bartizan.effect.impl;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Firework;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.weapon.dto.EffectSpec;
import org.luckyraven.bartizan.effect.EffectContext;
import org.mockito.InOrder;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link FireworkHookEffect} is an effect-only burst: the firework it spawns must be tagged cosmetic before it
 * detonates, so {@code ProjectileDamageListener} cancels its vanilla splash damage.
 */
@DisplayName("FireworkHookEffect")
class FireworkHookEffectTest {

	@Test
	@DisplayName("the spawned firework is tagged cosmetic before detonate()")
	void run_tagsFireworkBeforeDetonating() {
		World                   world    = mock(World.class);
		Location                location = new Location(world, 0, 64, 0);
		Firework                firework = mock(Firework.class);
		PersistentDataContainer pdc      = mock(PersistentDataContainer.class);
		when(world.spawn(location, Firework.class)).thenReturn(firework);
		when(firework.getFireworkMeta()).thenReturn(mock(FireworkMeta.class));
		when(firework.getPersistentDataContainer()).thenReturn(pdc);

		new FireworkHookEffect().run(new EffectSpec("firework", Map.of()),
		                             EffectContext.builder().impact(location).build());

		InOrder order = inOrder(pdc, firework);
		order.verify(pdc).set(any(), eq(PersistentDataType.BYTE), eq((byte) 1));
		order.verify(firework).detonate();
	}

}
