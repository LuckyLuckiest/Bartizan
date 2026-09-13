package org.luckyraven.bartizan.raytrace;

import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Covers {@link BeamRenderer#points(Location, Location, double)} — the pure geometry helper both the fired-beam
 * render loop and {@code BeamAction}'s charge preview draw along (weapons-roadmap.md gate {@code HC}, §3.2).
 */
@DisplayName("BeamRenderer.points")
class BeamRendererTest {

	private final World world = Mockito.mock(World.class);

	@Test
	@DisplayName("evenly-divisible distance: both endpoints included, spaced by step")
	void points_evenDistance() {
		Location from = new Location(world, 0, 64, 0);
		Location to   = new Location(world, 0, 64, 10);

		List<Location> points = BeamRenderer.points(from, to, 2.0);

		assertEquals(6, points.size());
		assertEquals(from, points.get(0));
		assertEquals(to, points.get(points.size() - 1));
	}

	@Test
	@DisplayName("distance not evenly divisible by step still ends exactly at 'to'")
	void points_unevenDistance() {
		Location from = new Location(world, 0, 64, 0);
		Location to   = new Location(world, 0, 64, 10);

		List<Location> points = BeamRenderer.points(from, to, 3.0);

		assertEquals(5, points.size());
		assertEquals(from, points.get(0));
		assertEquals(to, points.get(points.size() - 1));
	}

	@Test
	@DisplayName("from == to yields a single point")
	void points_zeroDistance() {
		Location point = new Location(world, 5, 64, 5);

		List<Location> points = BeamRenderer.points(point, point, 1.0);

		assertEquals(1, points.size());
		assertEquals(point, points.get(0));
	}

}
