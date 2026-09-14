package org.luckyraven.bartizan.hud;

import lombok.CustomLog;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.Nullable;
import org.luckyraven.bartizan.Bartizan;
import org.luckyraven.bartizan.weapon.WeaponService;

/**
 * Registers/unregisters {@link BartizanExpansion} with PlaceholderAPI (weapons-roadmap.md gate {@code HD}). Kept as
 * its own class, separate from {@code WiringConfig}, so {@code new BartizanExpansion(...)} — and therefore any
 * {@code me.clip.placeholderapi} class resolution — happens only when {@link #registerIfPresent} actually runs the
 * branch that needs it, never merely from {@code WiringConfig} being loaded/scanned on a server without
 * PlaceholderAPI installed.
 */
@CustomLog
public final class PlaceholderApiSupport {

	@Nullable
	private static BartizanExpansion expansion;

	private PlaceholderApiSupport() {
	}

	/**
	 * No-op unless PlaceholderAPI is enabled. {@code plugin.yml} already lists it as a {@code softdepend}, so a
	 * disabled/missing PlaceholderAPI is a supported configuration, not an error.
	 */
	public static void registerIfPresent(Bartizan bartizan, WeaponService weaponService) {
		if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) return;

		expansion = new BartizanExpansion(bartizan, weaponService);

		if (expansion.register()) {
			log.info("Registered the 'bartizan' PlaceholderAPI expansion.");
		} else {
			log.warn("Failed to register the 'bartizan' PlaceholderAPI expansion.");
		}
	}

	/**
	 * Symmetric teardown for {@link #registerIfPresent} — called from {@code Bartizan#onDisable()}. A no-op when
	 * nothing was ever registered.
	 */
	public static void unregisterIfPresent() {
		if (expansion == null) return;

		expansion.unregister();
		expansion = null;
	}

}
