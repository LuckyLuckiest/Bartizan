package org.luckyraven.bartizan.api.weapon;

/**
 * Where a raytrace impact landed on a victim's body (weapons-roadmap.md gate {@code HF}, §2). Computed by
 * {@code bartizan-plugin}'s {@code raytrace.HitZone} record and, since gate {@code HK}, carried on
 * {@link org.luckyraven.bartizan.api.event.WeaponEntityDamageEvent} so an api consumer (or
 * {@code stats.StatsService}) can read the zone without depending on the plugin-only {@code HitZone} type.
 */
public enum BodyZone {
	HEAD, BODY, ARMS, LEGS, FEET
}
