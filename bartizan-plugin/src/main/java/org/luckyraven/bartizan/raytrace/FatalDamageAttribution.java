package org.luckyraven.bartizan.raytrace;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Bug docket BZ-EV-19: names the weapon whose {@code LivingEntity#damage} call is on this thread's stack right now,
 * so a {@code PlayerDeathEvent} that Bukkit fires synchronously nested inside a fatal call (before the caller's own
 * {@code finally} block runs) can credit the weapon that actually delivered the blow, even when it was fired ticks
 * earlier by a slow projectile (rocket/flare) or thrown grenade and the shooter has since switched weapons —
 * {@code WeaponDeathListener}'s "killer's currently-held item" fallback is otherwise wrong for that case. Set by
 * {@code WeaponRaytracerImpl} and {@code ExplosionHandler} around their damage calls.
 * <p>
 * Carries the shooter too: {@code Player#getKiller()} names the last player to land any hit, so an attribution is
 * only credited to a killer who actually fired it — an NPC's fatal shot never credits a player who merely grazed
 * the victim earlier with the NPC's gun. Mirrors {@code WeaponRaytracer#isRaytraceDamageInProgress}'s ThreadLocal
 * pattern (bartizan-api) but stays in bartizan-plugin — no consumer needs to read it.
 */
public final class FatalDamageAttribution {

	private static final ThreadLocal<Attribution> CURRENT = new ThreadLocal<>();

	private FatalDamageAttribution() {
	}

	public static void set(String weaponName, @Nullable Entity shooter) {
		CURRENT.set(new Attribution(weaponName, shooter != null ? shooter.getUniqueId() : null));
	}

	/**
	 * The weapon dealing the damage call on this thread's stack, or {@code null} when none is set or {@code killer}
	 * did not fire it.
	 */
	@Nullable
	public static String weaponFiredBy(Player killer) {
		Attribution attribution = CURRENT.get();
		if (attribution == null || attribution.shooter() == null) return null;
		if (!attribution.shooter().equals(killer.getUniqueId())) return null;
		return attribution.weaponName();
	}

	public static void clear() {
		CURRENT.remove();
	}

	private record Attribution(String weaponName, @Nullable UUID shooter) {
	}

}
