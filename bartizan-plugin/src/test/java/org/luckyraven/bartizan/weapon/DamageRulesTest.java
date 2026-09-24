package org.luckyraven.bartizan.weapon;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicesManager;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Team;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.combat.CombatEligibility;
import org.luckyraven.bartizan.api.weapon.dto.DamageData;
import org.luckyraven.bartizan.api.weapon.dto.ThrowableData;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Covers {@link DamageRules#isProtected} — {@code Owner_Immunity} (self) and {@code Ignore_Teams} (same scoreboard
 * team) for both the gun ({@link DamageData}) and throwable ({@link ThrowableData}) overloads (weapons-roadmap.md
 * gate {@code HF}, §4).
 */
@DisplayName("DamageRules — owner immunity and same-team skip")
class DamageRulesTest {

	private MockedStatic<Bukkit> bukkit;
	private Scoreboard           scoreboard;

	@BeforeEach
	void setUp() {
		bukkit = mockStatic(Bukkit.class);
		ScoreboardManager manager = mock(ScoreboardManager.class);
		scoreboard = mock(Scoreboard.class);
		bukkit.when(Bukkit::getScoreboardManager).thenReturn(manager);
		when(manager.getMainScoreboard()).thenReturn(scoreboard);
	}

	@AfterEach
	void tearDown() {
		bukkit.close();
	}

	private Player player(String name) {
		Player player = mock(Player.class);
		when(player.getName()).thenReturn(name);
		return player;
	}

	@Test
	@DisplayName("Owner_Immunity false (default): shooter takes their own damage")
	void ownerImmunityFalse_shooterNotProtected() {
		DamageData data    = new DamageData();
		Player     shooter = player("Alice");

		assertFalse(DamageRules.isProtected(data, shooter, shooter));
	}

	@Test
	@DisplayName("Owner_Immunity true: shooter is protected from their own hit")
	void ownerImmunityTrue_shooterProtected() {
		DamageData data = new DamageData();
		data.setOwnerImmunity(true);
		Player shooter = player("Alice");

		assertTrue(DamageRules.isProtected(data, shooter, shooter));
	}

	@Test
	@DisplayName("Ignore_Teams false (default): a teammate still takes damage")
	void ignoreTeamsFalse_teammateNotProtected() {
		DamageData data    = new DamageData();
		Player     shooter = player("Alice");
		Player     victim  = player("Bob");

		Team team = mock(Team.class);
		when(scoreboard.getEntryTeam("Alice")).thenReturn(team);
		when(scoreboard.getEntryTeam("Bob")).thenReturn(team);

		assertFalse(DamageRules.isProtected(data, shooter, victim));
	}

	@Test
	@DisplayName("Ignore_Teams true + same scoreboard team: victim is protected")
	void ignoreTeamsTrue_sameTeam_isProtected() {
		DamageData data = new DamageData();
		data.setIgnoreTeams(true);
		Player shooter = player("Alice");
		Player victim  = player("Bob");

		Team team = mock(Team.class);
		when(scoreboard.getEntryTeam("Alice")).thenReturn(team);
		when(scoreboard.getEntryTeam("Bob")).thenReturn(team);

		assertTrue(DamageRules.isProtected(data, shooter, victim));
	}

	@Test
	@DisplayName("Ignore_Teams true but different teams: victim is not protected")
	void ignoreTeamsTrue_differentTeam_notProtected() {
		DamageData data = new DamageData();
		data.setIgnoreTeams(true);
		Player shooter = player("Alice");
		Player victim  = player("Bob");

		when(scoreboard.getEntryTeam("Alice")).thenReturn(mock(Team.class));
		when(scoreboard.getEntryTeam("Bob")).thenReturn(mock(Team.class));

		assertFalse(DamageRules.isProtected(data, shooter, victim));
	}

	@Test
	@DisplayName("Ignore_Teams true, no scoreboard team assigned: victim is not protected")
	void ignoreTeamsTrue_noTeam_notProtected() {
		DamageData data = new DamageData();
		data.setIgnoreTeams(true);
		Player shooter = player("Alice");
		Player victim  = player("Bob");

		assertFalse(DamageRules.isProtected(data, shooter, victim));
	}

	@Test
	@DisplayName("ThrowableData overload resolves Owner_Immunity the same way")
	void throwableDataOverload_ownerImmunity() {
		ThrowableData data = new ThrowableData();
		data.setOwnerImmunity(true);
		Player shooter = player("Alice");

		assertTrue(DamageRules.isProtected(data, shooter, shooter));
	}

	@Test
	@DisplayName("null shooter (e.g. environmental damage) is never protected")
	void nullShooter_notProtected() {
		DamageData data = new DamageData();
		data.setOwnerImmunity(true);
		data.setIgnoreTeams(true);
		Player victim = player("Bob");

		assertFalse(DamageRules.isProtected(data, null, victim));
	}

	@Test
	@DisplayName("BZ-RT-03/BZ-RT-20: CombatEligibility.canBeHit false for the victim is protected, even with "
			+ "Owner_Immunity/Ignore_Teams both false")
	void combatEligibilityFalse_victimProtected() {
		registerCombatEligibility(player -> false);

		DamageData data    = new DamageData();
		Player     shooter = player("Alice");
		Player     victim  = player("Bob");

		assertTrue(DamageRules.isProtected(data, shooter, victim));
	}

	@Test
	@DisplayName("CombatEligibility.canBeHit true for the victim: falls through to the ordinary Owner_Immunity/"
			+ "Ignore_Teams checks unaffected")
	void combatEligibilityTrue_fallsThroughToOrdinaryChecks() {
		registerCombatEligibility(player -> true);

		DamageData data    = new DamageData();
		Player     shooter = player("Alice");
		Player     victim  = player("Bob");

		assertFalse(DamageRules.isProtected(data, shooter, victim));
	}

	@Test
	@DisplayName("CombatEligibility guard only gates a Player victim, never a non-player LivingEntity")
	void combatEligibility_nonPlayerVictim_notGated() {
		registerCombatEligibility(player -> false);

		DamageData   data    = new DamageData();
		Player       shooter = player("Alice");
		LivingEntity victim  = mock(LivingEntity.class);

		assertFalse(DamageRules.isProtected(data, shooter, victim));
	}

	/** Stubs {@code CombatEligibility.resolve()} to return {@code provider} via the {@code ServicesManager}. */
	private void registerCombatEligibility(CombatEligibility provider) {
		Server server = mock(Server.class);
		bukkit.when(Bukkit::getServer).thenReturn(server);

		ServicesManager servicesManager = mock(ServicesManager.class);
		bukkit.when(Bukkit::getServicesManager).thenReturn(servicesManager);

		@SuppressWarnings("unchecked")
		RegisteredServiceProvider<CombatEligibility> registration = mock(RegisteredServiceProvider.class);
		when(registration.getProvider()).thenReturn(provider);
		when(servicesManager.getRegistration(CombatEligibility.class)).thenReturn(registration);
	}

}
