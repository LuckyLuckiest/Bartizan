package org.luckyraven.bartizan.listener.death;

import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.event.WeaponEntityDamageEvent;
import org.luckyraven.bartizan.weapon.WeaponManager;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link WeaponDeathListener} — R-B-FINAL review findings M1 and m1.
 */
@DisplayName("WeaponDeathListener")
class WeaponDeathListenerTest {

	@Test
	@DisplayName("M1: a null death message (an earlier LOWEST-priority listener already suppressed it) is left "
			+ "untouched — the weapon path never even inspects the killer")
	void onPlayerDeath_nullDeathMessage_neverOverridden() {
		WeaponManager        weaponManager = mock(WeaponManager.class);
		WeaponDeathListener  listener      = new WeaponDeathListener(weaponManager);

		Player          victim = mock(Player.class);
		PlayerDeathEvent event = mock(PlayerDeathEvent.class);
		when(event.getEntity()).thenReturn(victim);
		when(event.getDeathMessage()).thenReturn(null);

		listener.onPlayerDeath(event);

		verify(victim, never()).getKiller();
		verify(event, never()).setDeathMessage(any());
	}

	@Test
	@DisplayName("m1: a recorded throwable kill is cleared even when the death has no attributable killer, so it "
			+ "cannot resurface and misattribute a later, unrelated death for the same player")
	void onPlayerDeath_noKiller_stillClearsRecordedThrowableKillForLaterDeath() {
		WeaponManager       weaponManager = mock(WeaponManager.class);
		WeaponDeathListener listener      = new WeaponDeathListener(weaponManager);

		Player victim = mock(Player.class);
		when(victim.getUniqueId()).thenReturn(UUID.randomUUID());

		// A throwable claims a hit on the victim.
		WeaponEntityDamageEvent damageEvent = mock(WeaponEntityDamageEvent.class);
		when(damageEvent.getEntity()).thenReturn(victim);
		when(damageEvent.weaponName()).thenReturn("grenade");
		listener.onWeaponEntityDamage(damageEvent);

		// First death for that player: no attributable killer (environmental finish / self-detonation).
		PlayerDeathEvent unattributed = mock(PlayerDeathEvent.class);
		when(unattributed.getEntity()).thenReturn(victim);
		when(unattributed.getDeathMessage()).thenReturn("Victim died");
		when(victim.getKiller()).thenReturn(null);
		listener.onPlayerDeath(unattributed);

		// A later, unrelated death for the SAME uuid (players are long-lived; this is a second, real death), now
		// with a killer whose held item resolves to no weapon at all.
		Player          killer    = mock(Player.class);
		PlayerInventory inventory = mock(PlayerInventory.class);
		when(killer.getInventory()).thenReturn(inventory);
		when(inventory.getItemInMainHand()).thenReturn(mock(ItemStack.class));
		when(weaponManager.validateAndGetWeapon(eq(killer), any())).thenReturn(null);
		when(victim.getKiller()).thenReturn(killer);

		PlayerDeathEvent later = mock(PlayerDeathEvent.class);
		when(later.getEntity()).thenReturn(victim);
		when(later.getDeathMessage()).thenReturn("Victim died");

		listener.onPlayerDeath(later);

		// Pre-fix, the remove() sat after the killer == null early return, so the first (unattributed) death never
		// cleared the "grenade" entry — it survived to wrongly claim this second, unrelated death.
		verify(later, never()).setDeathMessage(any());
	}

}
