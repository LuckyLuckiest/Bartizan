package org.luckyraven.bartizan.stats;

import org.bukkit.Bukkit;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.luckyraven.bartizan.api.event.WeaponAssistEvent;
import org.luckyraven.bartizan.api.weapon.BodyZone;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.file.BartizanSettings;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link StatsService} — driven entirely off a manual tick clock, same shape as {@code StatusEffectServiceTest}
 * uses for {@code StatusEffectService} (weapons-roadmap.md gate {@code HK}).
 */
@DisplayName("StatsService")
class StatsServiceTest {

	private final long[] clock = {1000L};

	@BeforeEach
	void primeStatsSettings() throws ReflectiveOperationException {
		// BartizanSettings.Stats.* is only ever set by BartizanSettings#init(), which reads settings.yml through
		// Keystone's file pipeline - not available in a plain unit test. Priming it directly is the same shortcut
		// StatusEffectServiceTest uses for moneySymbol: fixed, known values regardless of test order.
		setStatic("statsEnabled", true);
		setStatic("statsAssistWindowTicks", 100);
		setStatic("statsAutosaveMinutes", 5);
	}

	private static void setStatic(String field, Object value) throws ReflectiveOperationException {
		Field f = BartizanSettings.class.getDeclaredField(field);
		f.setAccessible(true);
		f.set(null, value);
	}

	private StatsService service(File dataFolder) {
		JavaPlugin plugin = mock(JavaPlugin.class);
		when(plugin.getDataFolder()).thenReturn(dataFolder);
		return new StatsService(plugin, () -> clock[0]);
	}

	private static Player player() {
		Player player = mock(Player.class);
		when(player.getUniqueId()).thenReturn(UUID.randomUUID());
		return player;
	}

	private static Weapon weapon(String name) {
		Weapon weapon = mock(Weapon.class);
		when(weapon.getName()).thenReturn(name);
		return weapon;
	}

	@Test
	@DisplayName("recordShot increments the weapon's shot counter")
	void recordShot_incrementsShots(@TempDir File dataFolder) {
		StatsService service = service(dataFolder);
		Player       shooter = player();

		service.recordShot(shooter, "rifle");
		service.recordShot(shooter, "rifle");

		WeaponStat stat = service.lookup(shooter.getUniqueId()).weapon("rifle");
		assertEquals(2, stat.shots);
	}

	@Test
	@DisplayName("recordDamage increments hits/damage and buckets a headshot's zone")
	void recordDamage_incrementsHitsDamageAndZone(@TempDir File dataFolder) {
		StatsService service = service(dataFolder);
		Player       shooter = player();
		LivingEntity victim  = mock(LivingEntity.class);
		when(victim.getUniqueId()).thenReturn(UUID.randomUUID());

		service.recordDamage(shooter, victim, "rifle", 12.5, BodyZone.HEAD, 20.0);

		WeaponStat stat = service.lookup(shooter.getUniqueId()).weapon("rifle");
		assertEquals(1, stat.hits);
		assertEquals(1, stat.headshots);
		assertEquals(12.5, stat.damageDealt);
		assertEquals(1, stat.zoneHits.get(BodyZone.HEAD));
	}

	@Test
	@DisplayName("a null zone is not counted as a headshot or bucketed anywhere")
	void recordDamage_nullZone_notCountedAsHeadshot(@TempDir File dataFolder) {
		StatsService service = service(dataFolder);
		Player       shooter = player();
		LivingEntity victim  = mock(LivingEntity.class);
		when(victim.getUniqueId()).thenReturn(UUID.randomUUID());

		service.recordDamage(shooter, victim, "chainsaw", 5.0, null, 1.5);

		WeaponStat stat = service.lookup(shooter.getUniqueId()).weapon("chainsaw");
		assertEquals(1, stat.hits);
		assertEquals(0, stat.headshots);
		assertEquals(0, stat.zoneHits.size());
	}

	@Test
	@DisplayName("recordDamage on a mob (non-player) victim leaves no recentDamage entry, since recordKill never "
			+ "runs for a mob death to clean it up")
	void recordDamage_mobVictim_leavesNoRecentDamageEntry(@TempDir File dataFolder) throws ReflectiveOperationException {
		StatsService service = service(dataFolder);
		Player       shooter = player();
		LivingEntity mob     = mock(LivingEntity.class);
		UUID         mobId   = UUID.randomUUID();
		when(mob.getUniqueId()).thenReturn(mobId);

		service.recordDamage(shooter, mob, "rifle", 5.0, null, 3.0);

		Field field = StatsService.class.getDeclaredField("recentDamage");
		field.setAccessible(true);
		Map<?, ?> recentDamage = (Map<?, ?>) field.get(service);

		assertFalse(recentDamage.containsKey(mobId));
	}

	@Test
	@DisplayName("a kill credits the killer's weapon and sets longestKillDistance from the recorded hit — the "
			+ "victim's death count is BZ-HU-04's recordDeath's job now, not recordKill's")
	void recordKill_creditsKillerButNotDeath(@TempDir File dataFolder) {
		StatsService service = service(dataFolder);
		Player       killer  = player();
		Player       victim  = player();
		Weapon       rifle   = weapon("rifle");

		service.recordDamage(killer, victim, "rifle", 30.0, BodyZone.HEAD, 42.5);

		try (MockedStatic<Bukkit> bukkit = mockBukkit()) {
			service.recordKill(rifle, killer, victim);
		}

		WeaponStat killerStat = service.lookup(killer.getUniqueId()).weapon("rifle");
		assertEquals(1, killerStat.kills);
		assertEquals(42.5, killerStat.longestKillDistance);
		assertNull(service.lookup(victim.getUniqueId()), "recordKill alone never creates a stats entry for the victim");
	}

	@Test
	@DisplayName("BZ-HU-04: recordDeath increments the victim's death counter unconditionally — the only path that "
			+ "does, now that recordKill no longer touches it")
	void recordDeath_incrementsDeaths(@TempDir File dataFolder) {
		StatsService service = service(dataFolder);
		Player       victim  = player();

		service.recordDeath(victim);
		service.recordDeath(victim);

		assertEquals(2, service.lookup(victim.getUniqueId()).deaths);
	}

	@Test
	@DisplayName("BZ-HU-04: recordDeath clears the victim's recentDamage entry too, so an ordinary (non-weapon) "
			+ "death still cleans up a stray earlier hit instead of leaking it forever")
	void recordDeath_clearsRecentDamageEntry(@TempDir File dataFolder) throws ReflectiveOperationException {
		StatsService service  = service(dataFolder);
		Player       attacker = player();
		Player       victim   = player();

		service.recordDamage(attacker, victim, "rifle", 5.0, null, 10.0);
		service.recordDeath(victim);

		Field field = StatsService.class.getDeclaredField("recentDamage");
		field.setAccessible(true);
		Map<?, ?> recentDamage = (Map<?, ?>) field.get(service);

		assertFalse(recentDamage.containsKey(victim.getUniqueId()));
	}

	@Test
	@DisplayName("Stats.Enabled: false silently skips recordDeath too")
	void recordDeath_statsDisabled_recordsNothing(@TempDir File dataFolder) throws ReflectiveOperationException {
		setStatic("statsEnabled", false);

		StatsService service = service(dataFolder);
		Player       victim  = player();

		service.recordDeath(victim);

		assertNull(service.lookup(victim.getUniqueId()));
	}

	@Test
	@DisplayName("another attacker's hit inside the assist window credits an assist and fires WeaponAssistEvent")
	void recordKill_assistWithinWindow_creditsAssistAndFiresEvent(@TempDir File dataFolder)
			throws ReflectiveOperationException {
		setStatic("statsAssistWindowTicks", 100);

		StatsService service  = service(dataFolder);
		Player       assister = player();
		Player       killer   = player();
		Player       victim   = player();
		Weapon       rifle    = weapon("rifle");

		clock[0] = 1000L;
		service.recordDamage(assister, victim, "pistol", 5.0, null, 8.0);

		clock[0] = 1080L; // 80 ticks later - inside the 100-tick window
		service.recordDamage(killer, victim, "rifle", 30.0, BodyZone.HEAD, 42.5);

		try (MockedStatic<Bukkit> bukkit = mockBukkit()) {
			bukkit.when(() -> Bukkit.getPlayer(assister.getUniqueId())).thenReturn(assister);

			service.recordKill(rifle, killer, victim);

			ArgumentCaptor<WeaponAssistEvent> captor = ArgumentCaptor.forClass(WeaponAssistEvent.class);
			verify(Bukkit.getPluginManager()).callEvent(captor.capture());

			WeaponAssistEvent event = captor.getValue();
			assertEquals(assister, event.getAssister());
			assertEquals(victim, event.getVictim());
			assertEquals(killer, event.getKiller());
			assertEquals("pistol", event.getWeaponName());
		}

		assertEquals(1, service.lookup(assister.getUniqueId()).weapon("pistol").assists);
	}

	@Test
	@DisplayName("another attacker's hit outside the assist window does not credit an assist")
	void recordKill_assistOutsideWindow_noAssist(@TempDir File dataFolder) throws ReflectiveOperationException {
		setStatic("statsAssistWindowTicks", 100);

		StatsService service  = service(dataFolder);
		Player       assister = player();
		Player       killer   = player();
		Player       victim   = player();
		Weapon       rifle    = weapon("rifle");

		clock[0] = 1000L;
		service.recordDamage(assister, victim, "pistol", 5.0, null, 8.0);

		clock[0] = 1150L; // 150 ticks later - outside the 100-tick window
		service.recordDamage(killer, victim, "rifle", 30.0, BodyZone.HEAD, 42.5);

		try (MockedStatic<Bukkit> bukkit = mockBukkit()) {
			service.recordKill(rifle, killer, victim);
		}

		assertEquals(0, service.lookup(assister.getUniqueId()).weapon("pistol").assists);
	}

	@Test
	@DisplayName("the killer's own hit never double-counts itself as an assist")
	void recordKill_killerNeverAssistsThemselves(@TempDir File dataFolder) {
		StatsService service = service(dataFolder);
		Player       killer  = player();
		Player       victim  = player();
		Weapon       rifle   = weapon("rifle");

		clock[0] = 1000L;
		service.recordDamage(killer, victim, "rifle", 30.0, BodyZone.HEAD, 42.5);

		try (MockedStatic<Bukkit> bukkit = mockBukkit()) {
			service.recordKill(rifle, killer, victim);
		}

		assertEquals(0, service.lookup(killer.getUniqueId()).weapon("rifle").assists);
		assertEquals(1, service.lookup(killer.getUniqueId()).weapon("rifle").kills);
	}

	@Test
	@DisplayName("recordKill with a null weapon (throwable claim whose template no longer resolves) skips kill "
			+ "credit but still runs the assist loop")
	void recordKill_nullWeapon_skipsKillCreditKeepsAssists(@TempDir File dataFolder) {
		StatsService service  = service(dataFolder);
		Player       assister = player();
		Player       killer   = player();
		Player       victim   = player();

		clock[0] = 1000L;
		service.recordDamage(assister, victim, "pistol", 5.0, null, 8.0);

		try (MockedStatic<Bukkit> bukkit = mockBukkit()) {
			bukkit.when(() -> Bukkit.getPlayer(assister.getUniqueId())).thenReturn(assister);

			service.recordKill(null, killer, victim);
		}

		assertNull(service.lookup(killer.getUniqueId()), "no kill-credit block ran, so the killer never got a stats entry");
		assertEquals(1, service.lookup(assister.getUniqueId()).weapon("pistol").assists);
	}

	@Test
	@DisplayName("Stats.Enabled: false silently skips every counter")
	void statsDisabled_recordsNothing(@TempDir File dataFolder) throws ReflectiveOperationException {
		setStatic("statsEnabled", false);

		StatsService service = service(dataFolder);
		Player       shooter = player();

		service.recordShot(shooter, "rifle");

		assertNull(service.lookup(shooter.getUniqueId()));
	}

	@Test
	@DisplayName("onQuit saves to plugins/Bartizan/stats/<uuid>.json, and a fresh service instance reloads the "
			+ "same counts via Gson")
	void onQuit_savesAndReloadsViaGson(@TempDir File dataFolder) {
		StatsService first   = service(dataFolder);
		Player       shooter = player();
		LivingEntity victim  = mock(LivingEntity.class);
		when(victim.getUniqueId()).thenReturn(UUID.randomUUID());

		first.recordShot(shooter, "rifle");
		first.recordShot(shooter, "rifle");
		first.recordDamage(shooter, victim, "rifle", 7.5, BodyZone.LEGS, 9.0);
		first.onQuit(shooter.getUniqueId());

		File saved = new File(new File(dataFolder, "stats"), shooter.getUniqueId() + ".json");
		assertEquals(true, saved.isFile());

		StatsService second     = service(dataFolder);
		PlayerStats  reloaded   = second.lookup(shooter.getUniqueId());
		WeaponStat   rifleStats = reloaded.weapon("rifle");

		assertEquals(2, rifleStats.shots);
		assertEquals(1, rifleStats.hits);
		assertEquals(7.5, rifleStats.damageDealt);
		assertEquals(1, rifleStats.zoneHits.get(BodyZone.LEGS));
	}

	@Test
	@DisplayName("a corrupt stats file is quarantined to <uuid>.json.broken and lookup returns null instead of "
			+ "throwing")
	void lookup_corruptJson_quarantinesFileAndReturnsNull(@TempDir File dataFolder) throws Exception {
		StatsService service  = service(dataFolder);
		UUID         playerId = UUID.randomUUID();

		File statsDir = new File(dataFolder, "stats");
		statsDir.mkdirs();
		File corrupt = new File(statsDir, playerId + ".json");
		Files.writeString(corrupt.toPath(), "{not json", StandardCharsets.UTF_8);

		PlayerStats result = service.lookup(playerId);

		assertNull(result);
		assertFalse(corrupt.exists(), "the corrupt file is renamed away, not left in place");
		assertTrue(new File(statsDir, playerId + ".json.broken").isFile());
	}

	@Test
	@DisplayName("BZ-HU-01: a failed save at quit time (the 'stats' path is blocked by a colliding file) keeps the "
			+ "in-memory stats reachable and leaves the dirty flag set, instead of silently discarding the session")
	void onQuit_saveFails_keepsInMemoryStatsAndDirtyFlag(@TempDir File dataFolder) throws Exception {
		// Block statsDir entirely: a plain file sits where the "stats" directory needs to be created.
		Files.writeString(new File(dataFolder, "stats").toPath(), "not a directory", StandardCharsets.UTF_8);

		StatsService service = service(dataFolder);
		Player       shooter = player();

		service.recordShot(shooter, "rifle");
		service.onQuit(shooter.getUniqueId());

		// The in-memory copy survives the failed save — lookup() prefers the (still accurate) cached entry over
		// disk, and it is only removed from the cache once a save actually succeeds.
		PlayerStats stats = service.lookup(shooter.getUniqueId());
		assertEquals(1, stats.weapon("rifle").shots);

		Field dirtyField = StatsService.class.getDeclaredField("dirty");
		dirtyField.setAccessible(true);
		Set<?> dirty = (Set<?>) dirtyField.get(service);
		assertTrue(dirty.contains(shooter.getUniqueId()), "still dirty so a later autosave/shutdown retries the write");
	}

	@Test
	@DisplayName("BZ-HU-01: autosave (driven here via onShutdown) evicts a now-offline player's cache entry after "
			+ "a successful save, but keeps an online player's entry cached")
	void onShutdown_autosave_evictsOfflinePlayerKeepsOnlinePlayer(@TempDir File dataFolder) throws Exception {
		StatsService service        = service(dataFolder);
		Player       offlineShooter = player();
		Player       onlineShooter  = player();

		service.recordShot(offlineShooter, "rifle");
		service.recordShot(onlineShooter, "pistol");

		try (MockedStatic<Bukkit> bukkit = mockBukkit()) {
			bukkit.when(() -> Bukkit.getPlayer(offlineShooter.getUniqueId())).thenReturn(null);
			bukkit.when(() -> Bukkit.getPlayer(onlineShooter.getUniqueId())).thenReturn(onlineShooter);

			service.onShutdown();
		}

		Field loadedField = StatsService.class.getDeclaredField("loaded");
		loadedField.setAccessible(true);
		Map<?, ?> loaded = (Map<?, ?>) loadedField.get(service);

		assertFalse(loaded.containsKey(offlineShooter.getUniqueId()), "offline after a successful save - evicted");
		assertTrue(loaded.containsKey(onlineShooter.getUniqueId()), "online - stays cached for gameplay reads");
	}

	private MockedStatic<Bukkit> mockBukkit() {
		MockedStatic<Bukkit> bukkit        = mockStatic(Bukkit.class);
		PluginManager        pluginManager = mock(PluginManager.class);
		bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);
		return bukkit;
	}

}
