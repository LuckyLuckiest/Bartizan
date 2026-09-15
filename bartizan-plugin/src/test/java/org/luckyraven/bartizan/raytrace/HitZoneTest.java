package org.luckyraven.bartizan.raytrace;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers {@link HitZone#of} against synthetic bounding boxes (weapons-roadmap.md gate {@code HF}, §2). The victim's
 * bounding box is a 2-block-tall, 1x1 box centred on the world origin: {@code x/z in [-0.5, 0.5]}, {@code y in
 * [0, 2]}, so {@code relHeight = impactY / 2}.
 */
@DisplayName("HitZone.of — head/body/arms/legs/feet + back detection")
class HitZoneTest {

	private static final BoundingBox BOX = new BoundingBox(-0.5, 0, -0.5, 0.5, 2, 0.5);

	/**
	 * A real-player-width (0.6-block) box: {@code x/z in [-0.3, 0.3]}, {@code y in [0, 2]} — used by the
	 * clamped-hitbox tests below, where {@code RaytraceRequest.hitboxExpansion} (0.3) puts the impact point
	 * outside this box entirely (gate HF review finding 1).
	 */
	private static final BoundingBox NARROW_BOX = new BoundingBox(-0.3, 0, -0.3, 0.3, 2, 0.3);

	private LivingEntity victim(Vector facing) {
		return victim(facing, BOX);
	}

	private LivingEntity victim(Vector facing, BoundingBox box) {
		LivingEntity victim = mock(LivingEntity.class);
		when(victim.getBoundingBox()).thenReturn(box);

		Location eyeLoc = mock(Location.class);
		when(eyeLoc.getDirection()).thenReturn(facing);
		when(victim.getLocation()).thenReturn(eyeLoc);

		return victim;
	}

	@Test
	@DisplayName("relHeight >= 0.75 -> HEAD")
	void headZone() {
		LivingEntity victim = victim(new Vector(1, 0, 0));
		HitZone zone = HitZone.of(new Vector(0, 1.6, 0), victim, new Vector(0, 0, 1));

		assertEquals(HitZone.Zone.HEAD, zone.zone());
	}

	@Test
	@DisplayName("relHeight <= 0.12 -> FEET")
	void feetZone() {
		LivingEntity victim = victim(new Vector(1, 0, 0));
		HitZone zone = HitZone.of(new Vector(0, 0.1, 0), victim, new Vector(0, 0, 1));

		assertEquals(HitZone.Zone.FEET, zone.zone());
	}

	@Test
	@DisplayName("relHeight <= 0.35 (and > 0.12) -> LEGS")
	void legsZone() {
		LivingEntity victim = victim(new Vector(1, 0, 0));
		HitZone zone = HitZone.of(new Vector(0, 0.5, 0), victim, new Vector(0, 0, 1));

		assertEquals(HitZone.Zone.LEGS, zone.zone());
	}

	@Test
	@DisplayName("mid-height, centred horizontally -> BODY")
	void bodyZone() {
		LivingEntity victim = victim(new Vector(1, 0, 0));
		HitZone zone = HitZone.of(new Vector(0, 1.0, 0), victim, new Vector(0, 0, 1));

		assertEquals(HitZone.Zone.BODY, zone.zone());
	}

	@Test
	@DisplayName("mid-height, far from the centre axis (>= 0.75 * half-width) -> ARMS")
	void armsZone() {
		// half-width = 0.5, so >= 0.375 off-centre counts as ARMS.
		LivingEntity victim = victim(new Vector(1, 0, 0));
		HitZone zone = HitZone.of(new Vector(0.4, 1.0, 0), victim, new Vector(0, 0, 1));

		assertEquals(HitZone.Zone.ARMS, zone.zone());
	}

	@Test
	@DisplayName("mid-height, just inside the arms threshold -> BODY (edge case)")
	void armsEdgeCase_justInside() {
		LivingEntity victim = victim(new Vector(1, 0, 0));
		HitZone zone = HitZone.of(new Vector(0.3, 1.0, 0), victim, new Vector(0, 0, 1));

		assertEquals(HitZone.Zone.BODY, zone.zone());
	}

	@Test
	@DisplayName("clamped hitbox: impact point on the inflated detection surface, dead-centre -> BODY")
	void clampedHitbox_faceCentre_isBody() {
		// NARROW_BOX half-width is 0.3; hitboxExpansion (0.3) puts the raw impact point at z = -0.5, outside
		// the real box entirely. Without clamping this reads as far-from-centre (the pre-fix bug) — clamped
		// into the box it lands dead-centre on the front face -> BODY.
		LivingEntity victim = victim(new Vector(1, 0, 0), NARROW_BOX);
		HitZone zone = HitZone.of(new Vector(0, 1.0, -0.5), victim, new Vector(0, 0, 1));

		assertEquals(HitZone.Zone.BODY, zone.zone());
	}

	@Test
	@DisplayName("clamped hitbox: impact point at the lateral edge -> ARMS")
	void clampedHitbox_lateralEdge_isArms() {
		// Same narrow box/threshold (0.75 * 0.3 = 0.225); x = 0.28 clears it once clamped.
		LivingEntity victim = victim(new Vector(1, 0, 0), NARROW_BOX);
		HitZone zone = HitZone.of(new Vector(0.28, 1.0, -0.5), victim, new Vector(0, 0, 1));

		assertEquals(HitZone.Zone.ARMS, zone.zone());
	}

	@Test
	@DisplayName("back: shot direction aligned with the victim's facing direction -> back hit")
	void backHit_whenShotAlignsWithFacing() {
		// victim faces +Z (away from a shooter behind them); the shot also travels +Z (into their back).
		LivingEntity victim = victim(new Vector(0, 0, 1));
		HitZone zone = HitZone.of(new Vector(0, 1.0, 0), victim, new Vector(0, 0, 1));

		assertTrue(zone.back());
	}

	@Test
	@DisplayName("back: shot direction opposes the victim's facing direction -> front hit, not back")
	void frontHit_whenShotOpposesFacing() {
		// victim faces +Z (looking at the shooter); the shot travels -Z (into their chest).
		LivingEntity victim = victim(new Vector(0, 0, 1));
		HitZone zone = HitZone.of(new Vector(0, 1.0, 0), victim, new Vector(0, 0, -1));

		assertFalse(zone.back());
	}

}
