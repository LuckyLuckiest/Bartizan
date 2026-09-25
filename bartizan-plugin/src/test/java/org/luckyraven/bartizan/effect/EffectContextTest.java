package org.luckyraven.bartizan.effect;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Team;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.bukkit.Server;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicesManager;
import org.luckyraven.bartizan.api.combat.CombatEligibility;
import org.luckyraven.bartizan.api.weapon.GunWeapon;
import org.luckyraven.bartizan.api.weapon.MeleeWeapon;
import org.luckyraven.bartizan.api.weapon.ThrowableWeapon;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.api.weapon.dto.DamageData;
import org.luckyraven.bartizan.api.weapon.dto.ThrowableData;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * {@link EffectContext} placeholder and target resolution.
 */
@DisplayName("EffectContext")
class EffectContextTest {

	@Test
	@DisplayName("a mob's custom name cannot inject & colour codes into %victim% (BZ-EF-04)")
	void mobName_ampersandStripped() {
		Zombie mob = mock(Zombie.class);
		when(mob.getName()).thenReturn("&4[Server] &fhello");

		EffectContext ctx = EffectContext.builder().victim(mob).build();

		assertEquals("4[Server] fhello", ctx.placeholders().get("%victim%"));
	}

	private static Player player(String name) {
		Player player = mock(Player.class);
		when(player.getName()).thenReturn(name);
		return player;
	}

	private static GunWeapon gun(boolean ownerImmunity, boolean ignoreTeams) {
		DamageData data = new DamageData();
		data.setOwnerImmunity(ownerImmunity);
		data.setIgnoreTeams(ignoreTeams);

		GunWeapon gun = mock(GunWeapon.class);
		when(gun.getDamageData()).thenReturn(data);
		return gun;
	}

	/**
	 * Resolves {@code Target: nearby} for a context around a mocked world holding {@code shooter}, {@code teammate}
	 * and {@code stranger}, with {@code shooter} and {@code teammate} on one scoreboard team.
	 */
	private static List<LivingEntity> nearby(@Nullable Weapon weapon, Player shooter, Player teammate,
	                                         Zombie stranger) {
		return nearby(weapon, shooter, teammate, stranger, null);
	}

	/**
	 * As above, with {@code eligibility} registered as the consumer's {@link CombatEligibility} when non-null.
	 */
	private static List<LivingEntity> nearby(@Nullable Weapon weapon, Player shooter, Player teammate,
	                                         Zombie stranger, @Nullable CombatEligibility eligibility) {
		World    world  = mock(World.class);
		Location impact = new Location(world, 0, 64, 0);
		when(world.getNearbyEntities(impact, 4.0, 4.0, 4.0)).thenReturn(List.<Entity>of(shooter, teammate, stranger));

		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
			if (eligibility != null) {
				@SuppressWarnings("unchecked")
				RegisteredServiceProvider<CombatEligibility> registration = mock(RegisteredServiceProvider.class);
				when(registration.getProvider()).thenReturn(eligibility);
				ServicesManager servicesManager = mock(ServicesManager.class);
				when(servicesManager.getRegistration(CombatEligibility.class)).thenReturn(registration);
				bukkit.when(Bukkit::getServer).thenReturn(mock(Server.class));
				bukkit.when(Bukkit::getServicesManager).thenReturn(servicesManager);
			}
			ScoreboardManager manager    = mock(ScoreboardManager.class);
			Scoreboard        scoreboard = mock(Scoreboard.class);
			Team              team       = mock(Team.class);
			bukkit.when(Bukkit::getScoreboardManager).thenReturn(manager);
			when(manager.getMainScoreboard()).thenReturn(scoreboard);
			when(scoreboard.getEntryTeam("Alice")).thenReturn(team);
			when(scoreboard.getEntryTeam("Bob")).thenReturn(team);

			EffectContext ctx = EffectContext.builder().weapon(weapon).source(shooter).impact(impact).build();
			return ctx.targets("nearby", 4.0);
		}
	}

	@Test
	@DisplayName("Target: nearby skips the shooter under Owner_Immunity and teammates under Ignore_Teams (BZ-EF-06)")
	void nearby_filtersThroughDamageRules() {
		Player shooter  = player("Alice");
		Player teammate = player("Bob");
		Zombie stranger = mock(Zombie.class);
		when(stranger.getUniqueId()).thenReturn(UUID.randomUUID());

		assertEquals(List.of(stranger), nearby(gun(true, true), shooter, teammate, stranger));
		assertEquals(List.of(teammate, stranger), nearby(gun(true, false), shooter, teammate, stranger));
	}

	@Test
	@DisplayName("Target: nearby stays unfiltered with no weapon rules (a wearable context, or rules off)")
	void nearby_noRules_unfiltered() {
		Player shooter  = player("Alice");
		Player teammate = player("Bob");
		Zombie stranger = mock(Zombie.class);
		when(stranger.getUniqueId()).thenReturn(UUID.randomUUID());

		List<LivingEntity> all = List.of(shooter, teammate, stranger);
		assertEquals(all, nearby(null, shooter, teammate, stranger));
		assertEquals(all, nearby(gun(false, false), shooter, teammate, stranger));
	}

	@Test
	@DisplayName("Target: nearby applies a throwable's Owner_Immunity/Ignore_Teams too (BZ-EF-06)")
	void nearby_throwable_filtersThroughDamageRules() {
		Player shooter  = player("Alice");
		Player teammate = player("Bob");
		Zombie stranger = mock(Zombie.class);
		when(stranger.getUniqueId()).thenReturn(UUID.randomUUID());

		ThrowableData data = new ThrowableData();
		data.setOwnerImmunity(true);
		data.setIgnoreTeams(true);
		ThrowableWeapon grenade = mock(ThrowableWeapon.class);
		when(grenade.getThrowableData()).thenReturn(data);

		assertEquals(List.of(stranger), nearby(grenade, shooter, teammate, stranger));
	}

	@Test
	@DisplayName("Target: nearby skips a player CombatEligibility rules un-hittable, for every weapon type (BZ-EF-06)")
	void nearby_meleeWeapon_skipsIneligiblePlayer() {
		Player shooter  = player("Alice");
		Player downed   = player("Bob");
		Zombie stranger = mock(Zombie.class);
		when(stranger.getUniqueId()).thenReturn(UUID.randomUUID());

		CombatEligibility notBob = player -> player != downed;

		assertEquals(List.of(shooter, stranger),
		             nearby(mock(MeleeWeapon.class), shooter, downed, stranger, notBob));
	}

}
