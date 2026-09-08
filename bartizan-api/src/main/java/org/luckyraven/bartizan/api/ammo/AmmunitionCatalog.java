package org.luckyraven.bartizan.api.ammo;

import java.util.Set;

/**
 * The lookup surface {@code AmmunitionManager} exposes across the api/plugin split (bartizan.md §2 B6). Named here
 * so api-side code (such as {@link Ammunition#getHeldAmmunition}) can resolve ammunition without depending on the
 * plugin-side manager directly.
 */
public interface AmmunitionCatalog {

	Set<String> getAmmunitionKeys();

	Ammunition getAmmunition(String name);

}
