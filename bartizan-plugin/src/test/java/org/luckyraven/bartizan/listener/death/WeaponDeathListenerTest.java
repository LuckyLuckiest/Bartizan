package org.luckyraven.bartizan.listener.death;

import org.bukkit.Bukkit;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import org.luckyraven.bartizan.raytrace.FatalDamageAttribution;
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

	@BeforeEach
	void primeMoneySymbol() throws ReflectiveOperationException {
		// Pre-existing fragility fixed in passing: BartizanChatUtil.color() always substitutes %money_symbol%, so
		// every test that reaches event.setDeathMessage(...) needs BartizanSettings.moneySymbol non-null — it was
		// previously primed by only one @Test method, so passing depended on JUnit's (undocumented, hash-based)
		// method ordering happening to run that test first. See the field javadoc below for why reflection is used.
		Field field = BartizanSettings.class.getDeclaredField("moneySymbol");
		field.setAccessible(true);
		field.set(null, "$");
	}

	@AfterEach
	void clearFatalDamageAttribution() {
		// Belt-and-suspenders: a test that throws between set() and clear() must never leak the ThreadLocal into a
		// later test on the same (pooled) JUnit worker thread.
		FatalDamageAttribution.clear();
	}

	@Test
	@DisplayName("BZ-EV-19: a fatal-weapon attribution set synchronously around the killing living.damage() call "
			+ "(e.g. a slow rocket/flare landing after the shooter swapped weapons) is credited over the killer's "
			+ "currently-held item")
	void onPlayerDeath_fatalDamageAttributionSet_creditsThatWeaponOverHeldItem() {
		WeaponManager       weaponManager = mock(WeaponManager.class);
		WeaponDeathListener listener      = new WeaponDeathListener(weaponManager, mock(EffectRunner.class), mock(StatusEffectService.class));

		Player victim = mock(Player.class);
		when(victim.getUniqueId()).thenReturn(UUID.randomUUID());
		when(victim.getName()).thenReturn("Victim");

		Player          killer    = mock(Player.class);
		PlayerInventory inventory = mock(PlayerInventory.class);
		when(killer.getInventory()).thenReturn(inventory);
		when(inventory.getItemInMainHand()).thenReturn(mock(ItemStack.class));
		when(killer.getName()).thenReturn("Killer");
		when(victim.getKiller()).thenReturn(killer);

		Weapon rocket = mock(Weapon.class);
		when(rocket.getDisplayName()).thenReturn("Rocket Launcher");
		when(rocket.pickDeathMessage()).thenReturn(Optional.of("%killer% killed %victim% with %item%"));
		when(weaponManager.getWeaponTemplate("rocket")).thenReturn(rocket);

		PlayerDeathEvent event = mock(PlayerDeathEvent.class);
		when(event.getEntity()).thenReturn(victim);
		when(event.getDeathMessage()).thenReturn("Victim died");

		FatalDamageAttribution.set("rocket");
		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
			PluginManager pluginManager = mock(PluginManager.class);
			bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);

			listener.onPlayerDeath(event);
		} finally {
			FatalDamageAttribution.clear();
		}

		// Never even consulted the killer's held item — the fatal-hit attribution won outright.
		verify(weaponManager, never()).validateAndGetWeapon(any(), any());
		verify(event).setDeathMessage("Killer killed Victim with Rocket Launcher");
	}

	@Test
	@DisplayName("BZ-EV-19: a recorded FIRE claim is only consulted when the victim's last damage cause is the "
			+ "ongoing burn, so an unrelated later finish still credits the killer's held weapon")
	void onPlayerDeath_fireClaim_onlyConsultedWhenLastDamageCauseIsFire() {
		WeaponManager       weaponManager = mock(WeaponManager.class);
		WeaponDeathListener listener      = new WeaponDeathListener(weaponManager, mock(EffectRunner.class), mock(StatusEffectService.class));

		Player victim = mock(Player.class);
		when(victim.getUniqueId()).thenReturn(UUID.randomUUID());
		when(victim.getName()).thenReturn("Victim");

		WeaponEntityDamageEvent fireHit = mock(WeaponEntityDamageEvent.class);
		when(fireHit.getEntity()).thenReturn(victim);
		when(fireHit.weaponName()).thenReturn("flamethrower");
		when(fireHit.kind()).thenReturn(WeaponEntityDamageEvent.DamageKind.FIRE);
		listener.onWeaponEntityDamage(fireHit);

		Player          killer    = mock(Player.class);
		PlayerInventory inventory = mock(PlayerInventory.class);
		ItemStack       heldItem  = mock(ItemStack.class);
		when(killer.getInventory()).thenReturn(inventory);
		when(inventory.getItemInMainHand()).thenReturn(heldItem);
		when(killer.getName()).thenReturn("Killer");
		when(victim.getKiller()).thenReturn(killer);

		Weapon heldWeapon = mock(Weapon.class);
		when(heldWeapon.getDisplayName()).thenReturn("Knife");
		when(heldWeapon.pickDeathMessage()).thenReturn(Optional.of("%killer% killed %victim% with %item%"));
		when(weaponManager.validateAndGetWeapon(eq(killer), eq(heldItem))).thenReturn(heldWeapon);

		// The finishing blow was a knife swing, not the earlier fire — the last damage cause reflects that.
		EntityDamageEvent lastDamage = mock(EntityDamageEvent.class);
		when(lastDamage.getCause()).thenReturn(EntityDamageEvent.DamageCause.ENTITY_ATTACK);
		when(victim.getLastDamageCause()).thenReturn(lastDamage);

		PlayerDeathEvent event = mock(PlayerDeathEvent.class);
		when(event.getEntity()).thenReturn(victim);
		when(event.getDeathMessage()).thenReturn("Victim died");

		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
			PluginManager pluginManager = mock(PluginManager.class);
			bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);

			listener.onPlayerDeath(event);
		}

		verify(weaponManager, never()).getWeaponTemplate("flamethrower");
		verify(event).setDeathMessage("Killer killed Victim with Knife");
	}

	@Test
	@DisplayName("BZ-EV-19: a delayed death whose last damage cause is the ongoing burn credits the incendiary "
			+ "weapon that set the victim alight, even though the killer now holds something else")
	void onPlayerDeath_fireClaim_lastDamageCauseIsFireTick_creditsIncendiaryWeapon() {
		WeaponManager       weaponManager = mock(WeaponManager.class);
		WeaponDeathListener listener      = new WeaponDeathListener(weaponManager, mock(EffectRunner.class), mock(StatusEffectService.class));

		Player victim = mock(Player.class);
		when(victim.getUniqueId()).thenReturn(UUID.randomUUID());
		when(victim.getName()).thenReturn("Victim");

		WeaponEntityDamageEvent fireHit = mock(WeaponEntityDamageEvent.class);
		when(fireHit.getEntity()).thenReturn(victim);
		when(fireHit.weaponName()).thenReturn("flamethrower");
		when(fireHit.kind()).thenReturn(WeaponEntityDamageEvent.DamageKind.FIRE);
		listener.onWeaponEntityDamage(fireHit);

		Player killer = mock(Player.class);
		when(killer.getName()).thenReturn("Killer");
		when(victim.getKiller()).thenReturn(killer);

		Weapon flamethrower = mock(Weapon.class);
		when(flamethrower.getDisplayName()).thenReturn("Flamethrower");
		when(flamethrower.pickDeathMessage()).thenReturn(Optional.of("%killer% killed %victim% with %item%"));
		when(weaponManager.getWeaponTemplate("flamethrower")).thenReturn(flamethrower);

		EntityDamageEvent lastDamage = mock(EntityDamageEvent.class);
		when(lastDamage.getCause()).thenReturn(EntityDamageEvent.DamageCause.FIRE_TICK);
		when(victim.getLastDamageCause()).thenReturn(lastDamage);

		PlayerDeathEvent event = mock(PlayerDeathEvent.class);
		when(event.getEntity()).thenReturn(victim);
		when(event.getDeathMessage()).thenReturn("Victim died");

		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
			PluginManager pluginManager = mock(PluginManager.class);
			bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);

			listener.onPlayerDeath(event);
		}

		verify(weaponManager, never()).validateAndGetWeapon(any(), any());
		verify(event).setDeathMessage("Killer killed Victim with Flamethrower");
	}

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
	void onPlayerDeath_creditedKill_firesWeaponKillEntityEventBeforeMessage() {
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

	@Test
	@DisplayName("BZ-EV-21: a graze from an unrelated player inside Bukkit's own combat-tracker window does not "
			+ "steal a poison kill's credit — a last damage cause of POISON routes to the status shooter first")
	void onPlayerDeath_lastDamageCausePoison_creditsStatusShooterOverGrazingKiller() {
		WeaponManager       weaponManager = mock(WeaponManager.class);
		EffectRunner        effectRunner  = mock(EffectRunner.class);
		StatusEffectService statusService = mock(StatusEffectService.class);
		WeaponDeathListener listener      = new WeaponDeathListener(weaponManager, effectRunner, statusService);

		Player victim   = mock(Player.class);
		UUID   victimId = UUID.randomUUID();
		when(victim.getUniqueId()).thenReturn(victimId);
		when(victim.getName()).thenReturn("Victim");

		// Bukkit's getKiller() names the player who last grazed the victim, not who actually killed them.
		Player grazer = mock(Player.class);
		when(victim.getKiller()).thenReturn(grazer);

		EntityDamageEvent lastDamage = mock(EntityDamageEvent.class);
		when(lastDamage.getCause()).thenReturn(EntityDamageEvent.DamageCause.POISON);
		when(victim.getLastDamageCause()).thenReturn(lastDamage);

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
		when(statusService.currentTick()).thenReturn(1150L);

		PlayerDeathEvent event = mock(PlayerDeathEvent.class);
		when(event.getEntity()).thenReturn(victim);
		when(event.getDeathMessage()).thenReturn("Victim died");

		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
			PluginManager pluginManager = mock(PluginManager.class);
			bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);
			bukkit.when(() -> Bukkit.getPlayer(shooterId)).thenReturn(shooter);

			listener.onPlayerDeath(event);
		}

		// The grazing "killer" is never consulted at all — the status claim wins outright.
		verify(weaponManager, never()).validateAndGetWeapon(any(), any());
		verify(event).setDeathMessage("Shooter infected Victim with Syringe Gun");
	}

	@Test
	@DisplayName("BZ-EV-21: a killer whose hit actually was the fatal blow (last damage cause is not the status "
			+ "DoT) is still credited normally even with an unrelated active status in its window")
	void onPlayerDeath_lastDamageCauseNotStatus_fallsThroughToKillerDespiteActiveStatus() {
		WeaponManager       weaponManager = mock(WeaponManager.class);
		EffectRunner        effectRunner  = mock(EffectRunner.class);
		StatusEffectService statusService = mock(StatusEffectService.class);
		WeaponDeathListener listener      = new WeaponDeathListener(weaponManager, effectRunner, statusService);

		Player victim = mock(Player.class);
		when(victim.getUniqueId()).thenReturn(UUID.randomUUID());
		when(victim.getName()).thenReturn("Victim");

		EntityDamageEvent lastDamage = mock(EntityDamageEvent.class);
		when(lastDamage.getCause()).thenReturn(EntityDamageEvent.DamageCause.ENTITY_ATTACK);
		when(victim.getLastDamageCause()).thenReturn(lastDamage);

		Player          killer    = mock(Player.class);
		PlayerInventory inventory = mock(PlayerInventory.class);
		ItemStack       heldItem  = mock(ItemStack.class);
		when(killer.getInventory()).thenReturn(inventory);
		when(inventory.getItemInMainHand()).thenReturn(heldItem);
		when(killer.getName()).thenReturn("Killer");
		when(victim.getKiller()).thenReturn(killer);

		Weapon heldWeapon = mock(Weapon.class);
		when(heldWeapon.getDisplayName()).thenReturn("Knife");
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

		// The status service is never even consulted — isStatusDamageCause gated it out before creditStatusKill.
		verify(statusService, never()).activeOn(any());
		verify(event).setDeathMessage("Killer killed Victim with Knife");
	}

	@Test
	@DisplayName("BZ-EV-20: a mob killed by a player's held weapon fires WeaponKillEntityEvent and runs ON_KILL — "
			+ "PlayerDeathEvent never fires for a non-player victim, so nothing reached this before")
	void onEntityDeath_mobKilledByHeldWeapon_firesKillEventAndOnKill() {
		WeaponManager       weaponManager = mock(WeaponManager.class);
		EffectRunner        effectRunner  = mock(EffectRunner.class);
		WeaponDeathListener listener      = new WeaponDeathListener(weaponManager, effectRunner, mock(StatusEffectService.class));

		LivingEntity mob = mock(LivingEntity.class);
		when(mob.getUniqueId()).thenReturn(UUID.randomUUID());

		Player          killer    = mock(Player.class);
		PlayerInventory inventory = mock(PlayerInventory.class);
		ItemStack       heldItem  = mock(ItemStack.class);
		when(killer.getInventory()).thenReturn(inventory);
		when(inventory.getItemInMainHand()).thenReturn(heldItem);
		when(mob.getKiller()).thenReturn(killer);

		Weapon weapon = mock(Weapon.class);
		when(weaponManager.validateAndGetWeapon(eq(killer), eq(heldItem))).thenReturn(weapon);

		EntityDeathEvent event = mock(EntityDeathEvent.class);
		when(event.getEntity()).thenReturn(mob);

		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
			PluginManager pluginManager = mock(PluginManager.class);
			bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);

			listener.onEntityDeath(event);

			ArgumentCaptor<WeaponKillEntityEvent> captor = ArgumentCaptor.forClass(WeaponKillEntityEvent.class);
			verify(pluginManager).callEvent(captor.capture());
			assertEquals(weapon, captor.getValue().getWeapon());
			assertEquals(killer, captor.getValue().getKiller());
			assertEquals(mob, captor.getValue().getKilled());
		}

		ArgumentCaptor<EffectHook> hookCaptor = ArgumentCaptor.forClass(EffectHook.class);
		verify(effectRunner).run(eq(weapon), hookCaptor.capture(), any(EffectContext.class));
		assertEquals(EffectHook.ON_KILL, hookCaptor.getValue());
	}

	@Test
	@DisplayName("BZ-EV-20: a player victim is skipped entirely — onPlayerDeath already handles that death")
	void onEntityDeath_playerVictim_skipped() {
		WeaponManager       weaponManager = mock(WeaponManager.class);
		WeaponDeathListener listener      = new WeaponDeathListener(weaponManager, mock(EffectRunner.class), mock(StatusEffectService.class));

		Player playerVictim = mock(Player.class);
		EntityDeathEvent event = mock(EntityDeathEvent.class);
		when(event.getEntity()).thenReturn(playerVictim);

		listener.onEntityDeath(event);

		verify(playerVictim, never()).getKiller();
	}

	@Test
	@DisplayName("BZ-EV-20: a mob death with no attributable killer (fall damage, another mob) fires nothing")
	void onEntityDeath_noKiller_firesNothing() {
		WeaponManager       weaponManager = mock(WeaponManager.class);
		WeaponDeathListener listener      = new WeaponDeathListener(weaponManager, mock(EffectRunner.class), mock(StatusEffectService.class));

		LivingEntity mob = mock(LivingEntity.class);
		when(mob.getUniqueId()).thenReturn(UUID.randomUUID());
		when(mob.getKiller()).thenReturn(null);

		EntityDeathEvent event = mock(EntityDeathEvent.class);
		when(event.getEntity()).thenReturn(mob);

		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
			PluginManager pluginManager = mock(PluginManager.class);
			bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);

			listener.onEntityDeath(event);

			verify(pluginManager, never()).callEvent(any());
		}
	}

	@Test
	@DisplayName("BZ-EV-20: a recorded EXPLOSION claim on a mob (widened from Player-only) still credits the "
			+ "throwable over whatever the killer now holds")
	void onEntityDeath_recordedExplosionClaim_creditsThrowableWeapon() {
		WeaponManager       weaponManager = mock(WeaponManager.class);
		EffectRunner        effectRunner  = mock(EffectRunner.class);
		WeaponDeathListener listener      = new WeaponDeathListener(weaponManager, effectRunner, mock(StatusEffectService.class));

		LivingEntity mob = mock(LivingEntity.class);
		UUID         mobId = UUID.randomUUID();
		when(mob.getUniqueId()).thenReturn(mobId);

		WeaponEntityDamageEvent explosionHit = mock(WeaponEntityDamageEvent.class);
		when(explosionHit.getEntity()).thenReturn(mob);
		when(explosionHit.weaponName()).thenReturn("grenade");
		when(explosionHit.kind()).thenReturn(WeaponEntityDamageEvent.DamageKind.EXPLOSION);
		listener.onWeaponEntityDamage(explosionHit);

		Player killer = mock(Player.class);
		when(mob.getKiller()).thenReturn(killer);

		Weapon grenade = mock(Weapon.class);
		when(weaponManager.getWeaponTemplate("grenade")).thenReturn(grenade);

		EntityDeathEvent event = mock(EntityDeathEvent.class);
		when(event.getEntity()).thenReturn(mob);

		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
			PluginManager pluginManager = mock(PluginManager.class);
			bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);

			listener.onEntityDeath(event);

			ArgumentCaptor<WeaponKillEntityEvent> captor = ArgumentCaptor.forClass(WeaponKillEntityEvent.class);
			verify(pluginManager).callEvent(captor.capture());
			assertEquals(grenade, captor.getValue().getWeapon());
		}

		// Never even consulted the killer's held item — the recorded claim won outright.
		verify(weaponManager, never()).validateAndGetWeapon(any(), any());
	}

}
