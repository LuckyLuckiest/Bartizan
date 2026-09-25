package org.luckyraven.bartizan.status;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.Server;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicesManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.combat.CombatEligibility;
import org.luckyraven.bartizan.api.event.WeaponStatusExpireEvent;
import org.luckyraven.bartizan.api.event.WeaponStatusExpireEvent.Reason;
import org.luckyraven.bartizan.api.weapon.BiologicalWeapon;
import org.luckyraven.bartizan.api.weapon.WeaponType;
import org.luckyraven.bartizan.api.weapon.dto.BiologicalData;
import org.luckyraven.bartizan.api.weapon.dto.ChargeData;
import org.luckyraven.bartizan.api.weapon.dto.EffectHook;
import org.luckyraven.bartizan.api.weapon.dto.StatusData;
import org.luckyraven.bartizan.effect.EffectContext;
import org.luckyraven.bartizan.effect.EffectRunner;
import org.luckyraven.bartizan.file.BartizanSettings;
import org.luckyraven.bartizan.wearable.WearableService;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link StatusEffectService} — driven entirely off a manual tick clock, never a real scheduler
 * (weapons-roadmap.md gate {@code HB} §2.2), the same shape {@code ChargeControllerTest} uses for
 * {@code ChargeController#tick}. {@code Bukkit.createBossBar}/{@code getPluginManager} are statically mocked, same
 * pattern as {@code WeaponDeathListenerTest}.
 */
@DisplayName("StatusEffectService")
class StatusEffectServiceTest {

	@BeforeAll
	static void primeMoneySymbol() throws ReflectiveOperationException {
		// BartizanChatUtil.color() (used for the boss bar text and Message_Spread) substitutes %money_symbol%,
		// which is only ever set by BartizanSettings#init() — not available in a plain unit test.
		Field field = BartizanSettings.class.getDeclaredField("moneySymbol");
		field.setAccessible(true);
		field.set(null, "$");
	}

	private final long[]         clock = {1000L};
	private final EffectRunner   effectRunner    = mock(EffectRunner.class);
	private final WearableService wearableService = mock(WearableService.class);

	private StatusEffectService service(Random random) {
		return new StatusEffectService(mock(JavaPlugin.class), effectRunner, wearableService, () -> clock[0], random);
	}

	private StatusEffectService service() {
		return service(new Random());
	}

	private static Player player() {
		Player player = mock(Player.class);
		when(player.getUniqueId()).thenReturn(UUID.randomUUID());
		return player;
	}

	private static BiologicalWeapon weapon(StatusData.Stacking stacking, int durationPerLevel, int maxLevel) {
		return weapon(statusData(stacking, durationPerLevel, maxLevel, null));
	}

	private static BiologicalWeapon weapon(StatusData status) {
		BiologicalData data = new BiologicalData(new ChargeData(20, status.getMaxLevel(), 1, false),
		                                         List.of("POISON-60-1"), 30.0, 4.0, status, false);
		return new BiologicalWeapon(UUID.randomUUID(), "test_biogun", "&fInfected Syringe", WeaponType.BIOLOGICAL,
		                            Material.IRON_HOE, 0, (short) 100, List.of(), false, null, data, null, null);
	}

	private static StatusData statusData(StatusData.Stacking stacking, int durationPerLevel, int maxLevel,
	                                     StatusData.ContagionData contagion) {
		return new StatusData("Infected", "", durationPerLevel, stacking, maxLevel, 200, contagion,
		                      new StatusData.CureData(List.of("MILK_BUCKET"), "sealed"),
		                      new StatusData.BossBarData("%status% Lv %level%", "WHITE", "SOLID"), null, null, 20,
		                      "%victim% caught it from %carrier%");
	}

	private MockedStatic<Bukkit> mockBukkit() {
		MockedStatic<Bukkit> bukkit        = mockStatic(Bukkit.class);
		PluginManager        pluginManager = mock(PluginManager.class);
		bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);
		bukkit.when(() -> Bukkit.createBossBar(any(), any(), any())).thenAnswer(invocation -> mock(BossBar.class));
		return bukkit;
	}

	@Test
	@DisplayName("apply on a fresh victim creates a status with the expected level and expiry")
	void apply_freshVictim_createsStatusWithExpectedExpiry() {
		StatusEffectService service = service();
		Player               victim  = player();

		try (MockedStatic<Bukkit> bukkit = mockBukkit()) {
			service.apply(victim, player(), weapon(StatusData.Stacking.REFRESH, 200, 3), 2);
		}

		ActiveStatus status = service.activeOn(victim.getUniqueId()).orElseThrow();
		assertEquals(2, status.getLevel());
		assertEquals(1000L, status.getAppliedTick());
		assertEquals(1000L + 200L * 2, status.getExpiryTick());
	}

	@Test
	@DisplayName("REFRESH stacking keeps the level and resets the expiry off the new hit's tick")
	void apply_refreshStacking_resetsExpiry_levelUnchanged() {
		StatusEffectService service = service();
		Player               victim  = player();
		BiologicalWeapon      weapon  = weapon(StatusData.Stacking.REFRESH, 200, 3);

		try (MockedStatic<Bukkit> bukkit = mockBukkit()) {
			service.apply(victim, player(), weapon, 2);
			clock[0] = 1300L;
			service.apply(victim, player(), weapon, 1);
		}

		ActiveStatus status = service.activeOn(victim.getUniqueId()).orElseThrow();
		assertEquals(2, status.getLevel());
		assertEquals(1300L + 200L * 2, status.getExpiryTick());
	}

	@Test
	@DisplayName("EXTEND stacking keeps the level and adds duration to the existing expiry")
	void apply_extendStacking_addsDuration_levelUnchanged() {
		StatusEffectService service = service();
		Player               victim  = player();
		BiologicalWeapon      weapon  = weapon(StatusData.Stacking.EXTEND, 200, 3);

		try (MockedStatic<Bukkit> bukkit = mockBukkit()) {
			service.apply(victim, player(), weapon, 2); // expiry = 1000 + 400 = 1400
			clock[0] = 1300L;
			service.apply(victim, player(), weapon, 1); // expiry = 1400 + 200*1 = 1600
		}

		ActiveStatus status = service.activeOn(victim.getUniqueId()).orElseThrow();
		assertEquals(2, status.getLevel());
		assertEquals(1600L, status.getExpiryTick());
	}

	@Test
	@DisplayName("ESCALATE stacking bumps the level by one (capped at Max_Level) and resets the expiry")
	void apply_escalateStacking_bumpsLevel_capsAtMax() {
		StatusEffectService service = service();
		Player               victim  = player();
		BiologicalWeapon      weapon  = weapon(StatusData.Stacking.ESCALATE, 200, 2);

		try (MockedStatic<Bukkit> bukkit = mockBukkit()) {
			service.apply(victim, player(), weapon, 1); // level 1, expiry 1200
			clock[0] = 1100L;
			service.apply(victim, player(), weapon, 1); // escalate -> level 2, expiry 1100+400=1500
			service.apply(victim, player(), weapon, 1); // already at Max_Level(2) -> stays 2
		}

		ActiveStatus status = service.activeOn(victim.getUniqueId()).orElseThrow();
		assertEquals(2, status.getLevel());
	}

	@Test
	@DisplayName("IGNORE stacking leaves level and expiry untouched but still advances appliedTick")
	void apply_ignoreStacking_leavesLevelAndExpiry_stillUpdatesAppliedTick() {
		StatusEffectService service = service();
		Player               victim  = player();
		BiologicalWeapon      weapon  = weapon(StatusData.Stacking.IGNORE, 200, 3);

		try (MockedStatic<Bukkit> bukkit = mockBukkit()) {
			service.apply(victim, player(), weapon, 2); // level 2, expiry 1400
			clock[0] = 1300L;
			service.apply(victim, player(), weapon, 3); // ignored: level/expiry unchanged
		}

		ActiveStatus status = service.activeOn(victim.getUniqueId()).orElseThrow();
		assertEquals(2, status.getLevel());
		assertEquals(1400L, status.getExpiryTick());
		assertEquals(1300L, status.getAppliedTick());
	}

	@Test
	@DisplayName("tick past the expiry removes the status, runs On_Status_Expire once, fires the EXPIRED event")
	void tick_pastExpiry_removesStatus_runsHookOnce_firesExpiredEvent() {
		StatusEffectService service = service();
		Player               victim  = player();
		BiologicalWeapon      weapon  = weapon(StatusData.Stacking.REFRESH, 200, 1);

		try (MockedStatic<Bukkit> bukkit = mockBukkit()) {
			service.apply(victim, player(), weapon, 1); // expiry = 1200
		}

		clock[0] = 1200L;

		try (MockedStatic<Bukkit> bukkit = mockBukkit()) {
			bukkit.when(() -> Bukkit.getEntity(victim.getUniqueId())).thenReturn(victim);

			service.tick();
			service.tick(); // already removed -> no double fire

			ArgumentCaptor<WeaponStatusExpireEvent> captor = ArgumentCaptor.forClass(WeaponStatusExpireEvent.class);
			verify(Bukkit.getPluginManager()).callEvent(captor.capture());
			assertEquals(Reason.EXPIRED, captor.getValue().getReason());
		}

		verify(effectRunner, org.mockito.Mockito.times(1)).run(any(), eq(EffectHook.ON_STATUS_EXPIRE), any());
		assertTrue(service.activeOn(victim.getUniqueId()).isEmpty());
	}

	@Test
	@DisplayName("On_Status_Expire's context carries the online shooter as its source (BZ-EF-01)")
	void expire_contextSourceIsShooter() {
		StatusEffectService service = service();
		Player               victim  = player();
		Player               shooter = player();

		try (MockedStatic<Bukkit> bukkit = mockBukkit()) {
			service.apply(victim, shooter, weapon(StatusData.Stacking.REFRESH, 200, 3), 1);
		}

		try (MockedStatic<Bukkit> bukkit = mockBukkit()) {
			bukkit.when(() -> Bukkit.getEntity(victim.getUniqueId())).thenReturn(victim);
			bukkit.when(() -> Bukkit.getEntity(shooter.getUniqueId())).thenReturn(shooter);

			service.cure(victim.getUniqueId(), Reason.CURED);
		}

		ArgumentCaptor<EffectContext> captor = ArgumentCaptor.forClass(EffectContext.class);
		verify(effectRunner).run(any(), eq(EffectHook.ON_STATUS_EXPIRE), captor.capture());
		assertEquals(shooter, captor.getValue().getSource());
	}

	@Test
	@DisplayName("a re-stack by a different weapon restyles the boss bar to that weapon's Color/Style (BZ-EF-03)")
	void apply_restackByOtherWeapon_restylesBossBar() {
		StatusEffectService service = service();
		Player               victim  = player();
		BiologicalWeapon      red     = weapon(new StatusData("Infected", "", 200, StatusData.Stacking.REFRESH, 3, 200,
		                                                   null, new StatusData.CureData(List.of(), null),
		                                                   new StatusData.BossBarData("%status%", "RED",
		                                                                              "SEGMENTED_10"),
		                                                   null, null, 20, null));

		try (MockedStatic<Bukkit> bukkit = mockBukkit()) {
			service.apply(victim, player(), weapon(StatusData.Stacking.REFRESH, 200, 3), 1);
			service.apply(victim, player(), red, 1);
		}

		BossBar bar = service.activeOn(victim.getUniqueId()).orElseThrow().getBossBar();
		verify(bar).setColor(BarColor.RED);
		verify(bar).setStyle(BarStyle.SEGMENTED_10);
	}

	@Test
	@DisplayName("a status tick with an unchanged Color/Style resends no boss-bar style packets (BZ-EF-03 review)")
	void apply_sameStyle_doesNotResetColorOrStyle() {
		StatusEffectService service = service();
		Player               victim  = player();
		BiologicalWeapon      red     = weapon(new StatusData("Infected", "", 200, StatusData.Stacking.REFRESH, 3, 200,
		                                                   null, new StatusData.CureData(List.of(), null),
		                                                   new StatusData.BossBarData("%status%", "RED",
		                                                                              "SEGMENTED_10"),
		                                                   null, null, 20, null));
		BossBar bar = mock(BossBar.class);
		when(bar.getColor()).thenReturn(BarColor.RED);
		when(bar.getStyle()).thenReturn(BarStyle.SEGMENTED_10);

		try (MockedStatic<Bukkit> bukkit = mockBukkit()) {
			bukkit.when(() -> Bukkit.createBossBar(any(), any(), any())).thenReturn(bar);

			service.apply(victim, player(), red, 1);
			service.apply(victim, player(), red, 1);
		}

		verify(bar, never()).setColor(any());
		verify(bar, never()).setStyle(any());
	}

	/**
	 * BZ-RT-20: a contagion spread reaches {@code apply} without ever passing the raytrace's eligibility filter, so a
	 * player a consumer ruled un-hittable (downed) still caught the status and its DoT.
	 */
	@Test
	@DisplayName("a player CombatEligibility rules un-hittable catches no status")
	void apply_ineligibleVictim_appliesNothing() {
		StatusEffectService service = service();
		Player               victim  = player();

		CombatEligibility downed = player -> false;
		@SuppressWarnings("unchecked")
		RegisteredServiceProvider<CombatEligibility> registration = mock(RegisteredServiceProvider.class);
		when(registration.getProvider()).thenReturn(downed);
		ServicesManager servicesManager = mock(ServicesManager.class);
		when(servicesManager.getRegistration(CombatEligibility.class)).thenReturn(registration);

		boolean applied;
		try (MockedStatic<Bukkit> bukkit = mockBukkit()) {
			bukkit.when(Bukkit::getServer).thenReturn(mock(Server.class));
			bukkit.when(Bukkit::getServicesManager).thenReturn(servicesManager);

			applied = service.apply(victim, player(), weapon(StatusData.Stacking.REFRESH, 200, 3), 2);
		}

		assertFalse(applied);
		assertTrue(service.activeOn(victim.getUniqueId()).isEmpty());
		verifyNoInteractions(effectRunner);
	}

	@Test
	@DisplayName("cure removes the status and fires the CURED expire event")
	void cure_removesStatus_firesCuredEvent() {
		StatusEffectService service = service();
		Player               victim  = player();

		try (MockedStatic<Bukkit> bukkit = mockBukkit()) {
			service.apply(victim, player(), weapon(StatusData.Stacking.REFRESH, 200, 3), 1);
		}

		try (MockedStatic<Bukkit> bukkit = mockBukkit()) {
			bukkit.when(() -> Bukkit.getEntity(victim.getUniqueId())).thenReturn(victim);

			service.cure(victim.getUniqueId(), Reason.CURED);

			ArgumentCaptor<WeaponStatusExpireEvent> captor = ArgumentCaptor.forClass(WeaponStatusExpireEvent.class);
			verify(Bukkit.getPluginManager()).callEvent(captor.capture());
			assertEquals(Reason.CURED, captor.getValue().getReason());
		}

		assertTrue(service.activeOn(victim.getUniqueId()).isEmpty());
	}

	@Test
	@DisplayName("the sealed wearable trait reduces the incoming level to zero -> nothing applied, no event")
	void apply_sealedTraitReducesLevelToZero_appliesNothing() {
		when(wearableService.traitLevel(any(), eq("sealed"))).thenReturn(2);

		StatusEffectService service = service();
		Player               victim  = player();

		service.apply(victim, player(), weapon(StatusData.Stacking.REFRESH, 200, 3), 2);

		assertTrue(service.activeOn(victim.getUniqueId()).isEmpty());
		verifyNoInteractions(effectRunner);
	}

	@Test
	@DisplayName("the sealed wearable trait partially reduces the incoming level")
	void apply_sealedTraitPartiallyReducesLevel() {
		when(wearableService.traitLevel(any(), eq("sealed"))).thenReturn(1);

		StatusEffectService service = service();
		Player               victim  = player();

		try (MockedStatic<Bukkit> bukkit = mockBukkit()) {
			service.apply(victim, player(), weapon(StatusData.Stacking.REFRESH, 200, 3), 3);
		}

		assertEquals(2, service.activeOn(victim.getUniqueId()).orElseThrow().getLevel());
	}

	@Test
	@DisplayName("contagion respects the last-roll interval (not an exact multiple of it) and the List.copyOf "
			+ "snapshot survives a mid-tick insertion")
	void tick_contagionRollSucceeds_spreadsAtDroppedLevel() {
		Random random = mock(Random.class);
		when(random.nextDouble()).thenReturn(0.05); // < Chance (0.15) -> succeeds

		StatusEffectService service = service(random);

		StatusData.ContagionData contagionData = new StatusData.ContagionData(3.0, 0.15, 40, 1);
		BiologicalWeapon         weapon        = weapon(statusData(StatusData.Stacking.REFRESH, 200, 3, contagionData));

		World  world      = mock(World.class);
		Player carrier    = player();
		when(carrier.getName()).thenReturn("Carrier");
		when(carrier.getLocation()).thenReturn(new Location(world, 0, 0, 0));
		Player shooter = player();
		when(shooter.getName()).thenReturn("Shooter");
		Player target = player();
		when(target.getName()).thenReturn("Target");
		when(target.getLocation()).thenReturn(new Location(world, 1, 0, 0)); // 1 block away, well within Radius(3.0)
		when(carrier.getNearbyEntities(3.0, 3.0, 3.0)).thenReturn(List.<Entity>of(target));

		// A second, unrelated victim so tick()'s List.copyOf(active.values()) snapshot actually holds more than
		// one entry: with only the carrier active, iteration would already be over by the time the contagion roll
		// inserts `target` mid-loop, so that single-entry shape never proves the snapshot survives a live-map
		// mutation while still iterating (weapons-roadmap.md gate HB review item 12).
		Player other = player();

		try (MockedStatic<Bukkit> bukkit = mockBukkit()) {
			service.apply(carrier, shooter, weapon, 3); // level 3, appliedTick = lastContagionTick = 1000
			service.apply(other, null, weapon, 1);
		}

		clock[0] = 1030L; // 30 ticks since apply — under Interval(40) -> must not fire yet
		try (MockedStatic<Bukkit> bukkit = mockBukkit()) {
			bukkit.when(() -> Bukkit.getPlayer(carrier.getUniqueId())).thenReturn(carrier);
			service.tick();
		}
		assertTrue(service.activeOn(target.getUniqueId()).isEmpty());

		clock[0] = 1041L; // 41 ticks since apply — not an exact multiple of Interval(40), still fires
		try (MockedStatic<Bukkit> bukkit = mockBukkit()) {
			bukkit.when(() -> Bukkit.getPlayer(carrier.getUniqueId())).thenReturn(carrier);
			bukkit.when(() -> Bukkit.getPlayer(shooter.getUniqueId())).thenReturn(shooter);

			service.tick();
		}

		ActiveStatus targetStatus = service.activeOn(target.getUniqueId()).orElseThrow();
		assertEquals(2, targetStatus.getLevel()); // 3 - Level_Drop(1)
		assertEquals(shooter.getUniqueId(), targetStatus.getShooterId());

		verify(shooter).sendMessage(contains("Target"));
	}

}
