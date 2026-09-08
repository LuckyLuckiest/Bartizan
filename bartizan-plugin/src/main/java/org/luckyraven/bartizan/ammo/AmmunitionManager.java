package org.luckyraven.bartizan.ammo;

import org.luckyraven.bartizan.api.ammo.Ammunition;
import org.luckyraven.bartizan.api.ammo.AmmunitionCatalog;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class AmmunitionManager implements Comparator<Ammunition>, AmmunitionCatalog {

	private final Map<String, Ammunition> ammunition = new HashMap<>();

	public void register(String key, Ammunition ammo) {
		ammunition.put(key, ammo);
	}

	@Override
	public Ammunition getAmmunition(String key) {
		return ammunition.get(key);
	}

	@Override
	public Set<String> getAmmunitionKeys() {
		return ammunition.keySet();
	}

	public void clear() {
		ammunition.clear();
	}

	@Override
	public int compare(Ammunition a, Ammunition b) {
		return a.compareTo(b);
	}

}
