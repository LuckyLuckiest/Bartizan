package org.luckyraven.bartizan.api.event;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.weapon.BodyZone;
import org.luckyraven.bartizan.api.weapon.Weapon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

/**
 * {@link WeaponEntityDamageEvent} — gate {@code HK} added {@code zone}/{@code distance} to the constructor;
 * covers that the pre-{@code HK} six-argument constructor still compiles and delegates {@code null}/{@code 0} so
 * an existing caller (e.g. {@code IncendiaryAction}/{@code MeleeAction}/{@code BiologicalAction}) is unaffected.
 */
@DisplayName("WeaponEntityDamageEvent")
class WeaponEntityDamageEventTest {

	@Test
	@DisplayName("the old six-argument constructor delegates zone=null, distance=0")
	void legacyConstructor_delegatesNullZoneAndZeroDistance() {
		Weapon weapon  = mock(Weapon.class);
		Entity entity  = mock(Entity.class);
		Player shooter = mock(Player.class);

		WeaponEntityDamageEvent event = new WeaponEntityDamageEvent(weapon, entity, 5.0, shooter, "rifle",
				WeaponEntityDamageEvent.DamageKind.DIRECT);

		assertNull(event.getZone());
		assertEquals(0, event.getDistance());
		assertEquals("rifle", event.weaponName());
		assertEquals(WeaponEntityDamageEvent.DamageKind.DIRECT, event.kind());
	}

	@Test
	@DisplayName("the full eight-argument constructor carries the zone and distance through")
	void fullConstructor_carriesZoneAndDistance() {
		Weapon weapon  = mock(Weapon.class);
		Entity entity  = mock(Entity.class);
		Player shooter = mock(Player.class);

		WeaponEntityDamageEvent event = new WeaponEntityDamageEvent(weapon, entity, 5.0, shooter, "rifle",
				WeaponEntityDamageEvent.DamageKind.DIRECT, BodyZone.HEAD, 12.5);

		assertEquals(BodyZone.HEAD, event.getZone());
		assertEquals(12.5, event.getDistance());
	}

}
