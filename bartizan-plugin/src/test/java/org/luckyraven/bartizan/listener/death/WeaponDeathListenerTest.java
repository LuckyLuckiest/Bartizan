package org.luckyraven.bartizan.listener.death;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.event.WeaponEntityDamageEvent;
import org.luckyraven.bartizan.api.event.WeaponKillEntityEvent;
import org.luckyraven.bartizan.api.weapon.BiologicalWeapon;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.api.weapon.dto.BiologicalData;
import org.luckyraven.bartizan.api.weapon.dto.EffectHook;
import org.luckyraven.bartizan.api.weapon.dto.StatusData;
import org.luckyraven.bartizan.effect.EffectContext;
import org.luckyraven.bartizan.effect.EffectRunner;
import org.luckyraven.bartizan.file.BartizanSettings;
import org.luckyraven.bartizan.status.ActiveStatus;
import org.luckyraven.bartizan.status.StatusEffectService;
import org.luckyraven.bartizan.weapon.WeaponManager;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
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
		WeaponDeathListener  listener      = new WeaponDeathListener(weaponManager, mock(EffectRunner.class), mock(StatusEffectService.class));

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
		WeaponDeathListener listener      = new WeaponDeathListener(weaponManager, mock(EffectRunner.class), mock(StatusEffectService.class));

		Player victim = mock(Player.class);
		when(victim.getUniqueId()).thenReturn(UUID.randomUUID());

		// A throwable claims a hit on the victim.
		WeaponEntityDamageEvent damageEvent = mock(WeaponEntityDamageEvent.class);
		when(damageEvent.getEntity()).thenReturn(victim);
		when(damageEvent.weaponName()).thenReturn("grenade");
		when(damageEvent.kind()).thenReturn(WeaponEntityDamageEvent.DamageKind.EXPLOSION);
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

	@Test
	@DisplayName("HA §0.1: a credited kill fires WeaponKillEntityEvent with the killer/victim/weapon before the "
			+ "death message is applied")
	void onPlayerDeath_creditedKill_firesWeaponKillEntityEventBeforeMessage() throws ReflectiveOperationException {
		primeMoneySymbol();

		WeaponManager       weaponManager = mock(WeaponManager.class);
		EffectRunner        effectRunner  = mock(EffectRunner.class);
		WeaponDeathListener listener      = new WeaponDeathListener(weaponManager, effectRunner, mock(StatusEffectService.class));

		Player victim = mock(Player.class);
		when(victim.getUniqueId()).thenReturn(UUID.randomUUID());
		when(victim.getName()).thenReturn("Victim");

		Player          killer    = mock(Player.class);
		PlayerInventory inventory = mock(PlayerInventory.class);
		when(killer.getInventory()).thenReturn(inventory);
		when(inventory.getItemInMainHand()).thenReturn(mock(ItemStack.class));
		when(killer.getName()).thenReturn("Killer");
		when(victim.getKiller()).thenReturn(killer);

		Weapon weapon = mock(Weapon.class);
		when(weapon.getDisplayName()).thenReturn("Big Gun");
		when(weapon.pickDeathMessage()).thenReturn(Optional.of("%killer% killed %victim% with %item%"));
		when(weaponManager.validateAndGetWeapon(eq(killer), any())).thenReturn(weapon);

		PlayerDeathEvent event = mock(PlayerDeathEvent.class);
		when(event.getEntity()).thenReturn(victim);
		when(event.getDeathMessage()).thenReturn("Victim died");

		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
			PluginManager pluginManager = mock(PluginManager.class);
			bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);

			listener.onPlayerDeath(event);

			ArgumentCaptor<WeaponKillEntityEvent> captor = ArgumentCaptor.forClass(WeaponKillEntityEvent.class);
			verify(pluginManager).callEvent(captor.capture());

			WeaponKillEntityEvent killEvent = captor.getValue();
			assertEquals(weapon, killEvent.getWeapon());
			assertEquals(killer, killEvent.getKiller());
			assertEquals(victim, killEvent.getKilled());
		}

		verify(event).setDeathMessage("Killer killed Victim with Big Gun");

		ArgumentCaptor<EffectHook> hookCaptor = ArgumentCaptor.forClass(EffectHook.class);
		verify(effectRunner).run(eq(weapon), hookCaptor.capture(), any(EffectContext.class));
		assertEquals(EffectHook.ON_KILL, hookCaptor.getValue());
	}

	@Test
	@DisplayName("a cancelled WeaponKillEntityEvent leaves the vanilla death message untouched and never runs ON_KILL")
	void onPlayerDeath_cancelledKillEvent_leavesVanillaMessage() {
		WeaponManager       weaponManager = mock(WeaponManager.class);
		EffectRunner        effectRunner  = mock(EffectRunner.class);
		WeaponDeathListener listener      = new WeaponDeathListener(weaponManager, effectRunner, mock(StatusEffectService.class));

		Player victim = mock(Player.class);
		when(victim.getUniqueId()).thenReturn(UUID.randomUUID());

		Player          killer    = mock(Player.class);
		PlayerInventory inventory = mock(PlayerInventory.class);
		when(killer.getInventory()).thenReturn(inventory);
		when(inventory.getItemInMainHand()).thenReturn(mock(ItemStack.class));
		when(victim.getKiller()).thenReturn(killer);

		Weapon weapon = mock(Weapon.class);
		when(weapon.pickDeathMessage()).thenReturn(Optional.of("%killer% killed %victim% with %item%"));
		when(weaponManager.validateAndGetWeapon(eq(killer), any())).thenReturn(weapon);

		PlayerDeathEvent event = mock(PlayerDeathEvent.class);
		when(event.getEntity()).thenReturn(victim);
		when(event.getDeathMessage()).thenReturn("Victim died");

		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
			PluginManager pluginManager = mock(PluginManager.class);
			bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);
			doAnswer(invocation -> {
				WeaponKillEntityEvent killEvent = invocation.getArgument(0);
				killEvent.setCancelled(true);
				return null;
			}).when(pluginManager).callEvent(any());

			listener.onPlayerDeath(event);
		}

		verify(event, never()).setDeathMessage(any());
		verify(effectRunner, never()).run(any(), any(), any());
	}

	@Test
	@DisplayName("gate HA: a DIRECT WeaponEntityDamageEvent (fired on every gun hit since HA) does not record a "
			+ "claim, so the death message comes from the killer's actually-held weapon")
	void onWeaponEntityDamage_directKind_doesNotRecordClaim() {
		WeaponManager       weaponManager = mock(WeaponManager.class);
		WeaponDeathListener listener      = new WeaponDeathListener(weaponManager, mock(EffectRunner.class), mock(StatusEffectService.class));

		Player victim = mock(Player.class);
		when(victim.getUniqueId()).thenReturn(UUID.randomUUID());
		when(victim.getName()).thenReturn("Victim");

		// A gun hit fires WeaponEntityDamageEvent with DamageKind.DIRECT via the raytracer's default pipeline.
		WeaponEntityDamageEvent damageEvent = mock(WeaponEntityDamageEvent.class);
		when(damageEvent.getEntity()).thenReturn(victim);
		when(damageEvent.weaponName()).thenReturn("rifle");
		when(damageEvent.kind()).thenReturn(WeaponEntityDamageEvent.DamageKind.DIRECT);
		listener.onWeaponEntityDamage(damageEvent);

		Player          killer    = mock(Player.class);
		PlayerInventory inventory = mock(PlayerInventory.class);
		ItemStack       heldItem  = mock(ItemStack.class);
		when(killer.getInventory()).thenReturn(inventory);
		when(inventory.getItemInMainHand()).thenReturn(heldItem);
		when(killer.getName()).thenReturn("Killer");
		when(victim.getKiller()).thenReturn(killer);

		Weapon heldWeapon = mock(Weapon.class);
		when(heldWeapon.getDisplayName()).thenReturn("Pistol");
		when(heldWeapon.pickDeathMessage()).thenReturn(Optional.of("%killer% killed %victim% with %item%"));
		when(weaponManager.validateAndGetWeapon(eq(killer), eq(heldItem))).thenReturn(heldWeapon);

		PlayerDeathEvent event = mock(PlayerDeathEvent.class);
		when(event.getEntity()).thenReturn(victim);
		when(event.getDeathMessage()).thenReturn("Victim died");

		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
			PluginManager pluginManager = mock(PluginManager.class);
			bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);

			listener.onPlayerDeath(event);
		}

		// Never even consulted the "grenade" throwable template — the DIRECT hit left no claim to resolve.
		verify(weaponManager, never()).getWeaponTemplate(any());
		verify(event).setDeathMessage("Killer killed Victim with Pistol");
	}

	@Test
	@DisplayName("gate HB: a killer-less death with an active status inside the kill-credit window credits the "
			+ "shooter — the weapon's Death_Messages, ON_KILL, and WeaponKillEntityEvent")
	void onPlayerDeath_statusCreditWithinWindow_creditsShooter() {
		WeaponManager       weaponManager = mock(WeaponManager.class);
		EffectRunner        effectRunner  = mock(EffectRunner.class);
		StatusEffectService statusService = mock(StatusEffectService.class);
		WeaponDeathListener listener      = new WeaponDeathListener(weaponManager, effectRunner, statusService);

		Player victim   = mock(Player.class);
		UUID   victimId = UUID.randomUUID();
		when(victim.getUniqueId()).thenReturn(victimId);
		when(victim.getName()).thenReturn("Victim");
		when(victim.getKiller()).thenReturn(null);

		Player shooter   = mock(Player.class);
		UUID   shooterId = UUID.randomUUID();
		when(shooter.getUniqueId()).thenReturn(shooterId);
		when(shooter.getName()).thenReturn("Shooter");
		when(shooter.isOnline()).thenReturn(true);

		BiologicalWeapon weapon = mock(BiologicalWeapon.class);
		when(weapon.getDisplayName()).thenReturn("Syringe Gun");
		when(weapon.pickDeathMessage()).thenReturn(Optional.of("%killer% infected %victim% with %item%"));

		StatusData statusData = mock(StatusData.class);
		when(statusData.getKillCreditWindow()).thenReturn(200);

		BiologicalData biologicalData = mock(BiologicalData.class);
		when(biologicalData.getStatus()).thenReturn(statusData);
		when(weapon.getBiologicalData()).thenReturn(biologicalData);

		ActiveStatus status = new ActiveStatus(victimId, shooterId, weapon, 2, 1000L, 1400L);
		when(statusService.activeOn(victimId)).thenReturn(Optional.of(status));
		when(statusService.currentTick()).thenReturn(1150L); // 150 ticks since appliedTick(1000) <= window(200)

		PlayerDeathEvent event = mock(PlayerDeathEvent.class);
		when(event.getEntity()).thenReturn(victim);
		when(event.getDeathMessage()).thenReturn("Victim died");

		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
			PluginManager pluginManager = mock(PluginManager.class);
			bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);
			bukkit.when(() -> Bukkit.getPlayer(shooterId)).thenReturn(shooter);

			listener.onPlayerDeath(event);

			ArgumentCaptor<WeaponKillEntityEvent> captor = ArgumentCaptor.forClass(WeaponKillEntityEvent.class);
			verify(pluginManager).callEvent(captor.capture());
			assertEquals(shooter, captor.getValue().getKiller());
			assertEquals(victim, captor.getValue().getKilled());
		}

		verify(event).setDeathMessage("Shooter infected Victim with Syringe Gun");

		ArgumentCaptor<EffectHook> hookCaptor = ArgumentCaptor.forClass(EffectHook.class);
		verify(effectRunner).run(eq(weapon), hookCaptor.capture(), any(EffectContext.class));
		assertEquals(EffectHook.ON_KILL, hookCaptor.getValue());
	}

	@Test
	@DisplayName("gate HB: an active status outside the kill-credit window is not credited")
	void onPlayerDeath_statusOutsideWindow_noCredit() {
		WeaponManager       weaponManager = mock(WeaponManager.class);
		EffectRunner        effectRunner  = mock(EffectRunner.class);
		StatusEffectService statusService = mock(StatusEffectService.class);
		WeaponDeathListener listener      = new WeaponDeathListener(weaponManager, effectRunner, statusService);

		Player victim   = mock(Player.class);
		UUID   victimId = UUID.randomUUID();
		when(victim.getUniqueId()).thenReturn(victimId);
		when(victim.getKiller()).thenReturn(null);

		UUID             shooterId = UUID.randomUUID();
		BiologicalWeapon weapon    = mock(BiologicalWeapon.class);

		StatusData statusData = mock(StatusData.class);
		when(statusData.getKillCreditWindow()).thenReturn(200);

		BiologicalData biologicalData = mock(BiologicalData.class);
		when(biologicalData.getStatus()).thenReturn(statusData);
		when(weapon.getBiologicalData()).thenReturn(biologicalData);

		ActiveStatus status = new ActiveStatus(victimId, shooterId, weapon, 2, 1000L, 1400L);
		when(statusService.activeOn(victimId)).thenReturn(Optional.of(status));
		when(statusService.currentTick()).thenReturn(1300L); // 300 ticks since appliedTick(1000) > window(200)

		PlayerDeathEvent event = mock(PlayerDeathEvent.class);
		when(event.getEntity()).thenReturn(victim);
		when(event.getDeathMessage()).thenReturn("Victim died");

		listener.onPlayerDeath(event);

		verify(event, never()).setDeathMessage(any());
		verify(effectRunner, never()).run(any(), any(), any());
	}

	/**
	 * {@code BartizanSettings.moneySymbol} is only ever set by {@code BartizanSettings#init()}, which reads
	 * {@code settings.yml} through Keystone's file pipeline — not available in a plain unit test. Priming it
	 * directly is the same shortcut {@code BartizanChatUtil.color()}'s only other unit-test caller would need: the
	 * static field is package-private-by-convention config state, not something worth standing up a fake
	 * {@code FileManager} for.
	 */
	private static void primeMoneySymbol() throws ReflectiveOperationException {
		Field field = BartizanSettings.class.getDeclaredField("moneySymbol");
		field.setAccessible(true);
		field.set(null, "$");
	}

}
