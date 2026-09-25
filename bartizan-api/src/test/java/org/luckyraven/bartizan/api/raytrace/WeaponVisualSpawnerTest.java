package org.luckyraven.bartizan.api.raytrace;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.weapon.dto.VisualData;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BZ-RT-18 review: a cosmetic visual saved with its chunk came back under a fresh entity id the spawner no longer
 * tracked - a DROPPED_ITEM visual a hopper could collect, an ARMOR_STAND/PRIMED_TNT orphaned for good.
 */
@DisplayName("WeaponVisualSpawner - cosmetic visuals are never saved")
class WeaponVisualSpawnerTest {

	@Test
	@DisplayName("a spawned visual is non-persistent")
	void spawnVisual_isNotPersistent() {
		World     world    = mock(World.class);
		Location  location = new Location(world, 0, 64, 0);
		TNTPrimed tnt      = mock(TNTPrimed.class);
		when(world.spawn(location, TNTPrimed.class)).thenReturn(tnt);

		new WeaponVisualSpawner().spawnVisual(new VisualData(VisualData.VisualType.PRIMED_TNT, null, 0, null),
		                                      location, new Vector(1, 0, 0), mock(LivingEntity.class));

		verify(tnt).setPersistent(false);
	}

}
