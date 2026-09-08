package org.luckyraven.bartizan.api.combat;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Replaces the Gangland-only {@code DownedPlayerRegistry} static gate (bartizan.md §1.6(5)). Gangland registers an
 * implementation on the {@code ServicesManager}; Bartizan pulls it, never caching the registration.
 *
 * <p>{@link #resolve()} performs that lookup and is the one place every call site in Bartizan goes through -
 * {@code InstantReload} and {@code NumberedReload} are plain domain objects constructed deep inside {@code Weapon}
 * (not beans), so a constructor-injected holder would have to ripple through {@code ReloadType.createInstance} and
 * every {@code Weapon} constructor call site across groups not yet ported in this stream. A static resolver keeps
 * the same "lazy at every use, never cached" contract (R8) without that ripple; a later group's
 * {@code WiringConfig} holder bean (for {@code WeaponInteract}, which is a real bean) may delegate to this same
 * lookup or perform its own - both are compatible with this interface's single abstract method.
 */
@FunctionalInterface
public interface CombatEligibility {

	/**
	 * @return {@code false} when the player is out of combat (downed, dead, otherwise protected). Callers gate with
	 * {@code if (!canBeHit(player))} - the INVERSE of the old {@code DownedPlayerRegistry.isDowned(uuid)}; an
	 * implementor porting that registry returns {@code !isDowned}, never {@code isDowned}.
	 */
	boolean canBeHit(Player player);

	CombatEligibility DEFAULT = player -> !player.isDead();

	static CombatEligibility resolve() {
		if (Bukkit.getServer() == null) {
			return DEFAULT; // no server installed (unit tests)
		}

		RegisteredServiceProvider<CombatEligibility> registration =
				Bukkit.getServicesManager().getRegistration(CombatEligibility.class);

		return registration == null ? DEFAULT : registration.getProvider();
	}

}
