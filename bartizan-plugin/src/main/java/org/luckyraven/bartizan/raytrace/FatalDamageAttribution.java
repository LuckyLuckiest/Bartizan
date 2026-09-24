package org.luckyraven.bartizan.raytrace;

import org.jetbrains.annotations.Nullable;

/**
 * Bug docket BZ-EV-19: names the weapon whose {@code LivingEntity#damage} call is on this thread's stack right now,
 * so a {@code PlayerDeathEvent} that Bukkit fires synchronously nested inside a fatal call (before
 * {@code WeaponRaytracerImpl}'s own {@code finally} block runs) can credit the weapon that actually delivered the
 * blow, even when it was fired ticks earlier by a slow projectile (rocket/flare) and the shooter has since switched
 * weapons — {@code WeaponDeathListener}'s "killer's currently-held item" fallback is otherwise wrong for that case.
 * Mirrors {@code WeaponRaytracer#isRaytraceDamageInProgress}'s ThreadLocal pattern (bartizan-api) but stays in
 * bartizan-plugin — no consumer needs to read it.
 */
public final class FatalDamageAttribution {

	private static final ThreadLocal<String> WEAPON_NAME = new ThreadLocal<>();

	private FatalDamageAttribution() {
	}

	public static void set(@Nullable String weaponName) {
		WEAPON_NAME.set(weaponName);
	}

	@Nullable
	public static String get() {
		return WEAPON_NAME.get();
	}

	public static void clear() {
		WEAPON_NAME.remove();
	}

}
