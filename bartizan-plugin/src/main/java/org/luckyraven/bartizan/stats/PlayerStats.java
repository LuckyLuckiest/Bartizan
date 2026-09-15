package org.luckyraven.bartizan.stats;

import java.util.HashMap;
import java.util.Map;

/**
 * One player's persisted stats (weapons-roadmap.md gate {@code HK}) — Gson round-trips this verbatim to/from
 * {@code plugins/Bartizan/stats/<uuid>.json}. Plain mutable fields, same reasoning as {@link WeaponStat}.
 */
public class PlayerStats {

	public int deaths;
	public final Map<String, WeaponStat> weapons = new HashMap<>();

	public WeaponStat weapon(String name) {
		return weapons.computeIfAbsent(name, ignored -> new WeaponStat());
	}

}
