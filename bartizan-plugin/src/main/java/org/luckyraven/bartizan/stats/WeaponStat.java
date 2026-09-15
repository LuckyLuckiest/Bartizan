package org.luckyraven.bartizan.stats;

import org.luckyraven.bartizan.api.weapon.BodyZone;

import java.util.EnumMap;

/**
 * One player's counters for a single weapon (weapons-roadmap.md gate {@code HK}). Plain mutable public fields —
 * {@link StatsService} increments them directly and Gson (de)serialises them straight into
 * {@code plugins/Bartizan/stats/<uuid>.json} without needing getters/setters.
 */
public class WeaponStat {

	public int shots;
	public int hits;
	public int headshots;
	public final EnumMap<BodyZone, Integer> zoneHits = new EnumMap<>(BodyZone.class);
	public int kills;
	public int assists;
	public double damageDealt;
	public double longestKillDistance;

	public void addZoneHit(BodyZone zone) {
		zoneHits.merge(zone, 1, Integer::sum);
	}

}
