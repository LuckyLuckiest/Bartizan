package org.luckyraven.bartizan.raytrace;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins {@link WeaponRaytracerImpl#pointToSegmentDistance}/{@link WeaponRaytracerImpl#distanceToPolyline} — the
 * pure math behind {@code playFlybySounds} (weapons-roadmap.md gate {@code HE} part b: fly-by sound). Both are
 * package-private static helpers with no Bukkit world access, so they're exercised directly without mocking
 * Bukkit.
 */
@DisplayName("WeaponRaytracerImpl — fly-by point-to-segment math")
class WeaponRaytracerFlybyMathTest {

	@Test
	@DisplayName("point directly on the segment is distance 0")
	void pointOnSegment_isZero() {
		double distance = WeaponRaytracerImpl.pointToSegmentDistance(
				new Vector(5, 0, 0), new Vector(0, 0, 0), new Vector(10, 0, 0));

		assertEquals(0.0, distance, 0.0001);
	}

	@Test
	@DisplayName("point abeam the segment's middle uses the perpendicular distance")
	void pointBesideSegmentMiddle_usesPerpendicularDistance() {
		double distance = WeaponRaytracerImpl.pointToSegmentDistance(
				new Vector(5, 3, 0), new Vector(0, 0, 0), new Vector(10, 0, 0));

		assertEquals(3.0, distance, 0.0001);
	}

	@Test
	@DisplayName("point past either endpoint clamps to that endpoint, not the infinite line")
	void pointPastEndpoint_clampsToEndpoint() {
		double pastEnd = WeaponRaytracerImpl.pointToSegmentDistance(
				new Vector(15, 0, 0), new Vector(0, 0, 0), new Vector(10, 0, 0));
		assertEquals(5.0, pastEnd, 0.0001);

		double beforeStart = WeaponRaytracerImpl.pointToSegmentDistance(
				new Vector(-4, 0, 0), new Vector(0, 0, 0), new Vector(10, 0, 0));
		assertEquals(4.0, beforeStart, 0.0001);
	}

	@Test
	@DisplayName("a degenerate zero-length segment falls back to point-to-point distance")
	void zeroLengthSegment_fallsBackToPointDistance() {
		double distance = WeaponRaytracerImpl.pointToSegmentDistance(
				new Vector(3, 4, 0), new Vector(0, 0, 0), new Vector(0, 0, 0));

		assertEquals(5.0, distance, 0.0001);
	}

	@Test
	@DisplayName("distanceToPolyline picks the closest of several legs, not just the first")
	void distanceToPolyline_picksClosestLeg() {
		Vector origin = new Vector(0, 0, 0);
		List<Vector> segments = List.of(new Vector(10, 0, 0), new Vector(10, 0, 10));

		// Far from leg 1 (origin -> (10,0,0), distance 10 at x=5) but only 1 block from leg 2
		// ((10,0,0) -> (10,0,10)) at (11, 0, 5).
		double distance = WeaponRaytracerImpl.distanceToPolyline(new Vector(11, 0, 5), origin, segments);

		assertEquals(1.0, distance, 0.0001);
	}

}
