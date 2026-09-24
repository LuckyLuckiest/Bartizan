package org.luckyraven.bartizan.raytrace;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicesManager;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.combat.CombatEligibility;
import org.luckyraven.bartizan.api.raytrace.RaytraceRequest;
import org.luckyraven.bartizan.api.support.WeaponFixtures;
import org.luckyraven.bartizan.api.weapon.GunWeapon;
import org.luckyraven.bartizan.api.weapon.MeleeWeapon;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Covers {@link WeaponRaytracerImpl#isEligibleTarget} — the {@code advanceRay} entity-scan predicate (BZ-RT-03,
 * fix round 1). Package-private static with no {@code World} access, so it's exercised directly with mocked
 * entities, same style as {@code DamageRulesTest}'s {@code CombatEligibility} stubbing.
 */
@DisplayName("WeaponRaytracerImpl.isEligibleTarget — CombatEligibility gates every weapon type, not just guns")
class WeaponRaytracerImplEligibleTargetTest {

	private MockedStatic<Bukkit> bukkit;

	@BeforeEach
	void setUp() {
		bukkit = mockStatic(Bukkit.class);
	}

	@AfterEach
	void tearDown() {
		bukkit.close();
	}

	private RaytraceRequest request(LivingEntity shooter, org.luckyraven.bartizan.api.weapon.Weapon weapon) {
		return RaytraceRequest.builder()
		                      .shooter(shooter)
		                      .weapon(weapon)
		                      .origin(new Location(null, 0, 0, 0))
		                      .direction(new Vector(0, 0, 1))
		                      .build();
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

	@Test
	@DisplayName("BZ-RT-03: a non-GunWeapon (beam/incendiary/melee/biological all share this predicate) still "
			+ "excludes a Player CombatEligibility has ruled un-hittable")
	void nonGunWeapon_combatEligibilityFalse_excludesPlayer() {
		registerCombatEligibility(player -> false);

		LivingEntity shooter = mock(LivingEntity.class);
		Player       victim  = mock(Player.class);
		MeleeWeapon  weapon  = WeaponFixtures.meleeWeapon(1);

		assertFalse(WeaponRaytracerImpl.isEligibleTarget(victim, shooter, request(shooter, weapon)));
	}

	@Test
	@DisplayName("non-GunWeapon, CombatEligibility true: candidate passes through to the request's entityFilter")
	void nonGunWeapon_combatEligibilityTrue_passesThrough() {
		registerCombatEligibility(player -> true);

		LivingEntity shooter = mock(LivingEntity.class);
		Player       victim  = mock(Player.class);
		MeleeWeapon  weapon  = WeaponFixtures.meleeWeapon(1);

		assertTrue(WeaponRaytracerImpl.isEligibleTarget(victim, shooter, request(shooter, weapon)));
	}

	@Test
	@DisplayName("GunWeapon path still excludes a CombatEligibility-ineligible Player (unchanged by this fix)")
	void gunWeapon_combatEligibilityFalse_excludesPlayer() {
		registerCombatEligibility(player -> false);

		LivingEntity shooter = mock(LivingEntity.class);
		Player       victim  = mock(Player.class);
		GunWeapon    weapon  = WeaponFixtures.gunWeapon(30, 1);

		assertFalse(WeaponRaytracerImpl.isEligibleTarget(victim, shooter, request(shooter, weapon)));
	}

	@Test
	@DisplayName("CombatEligibility only gates a Player candidate, never a non-player LivingEntity (a mob)")
	void nonPlayerCandidate_notGatedByCombatEligibility() {
		registerCombatEligibility(player -> false);

		LivingEntity shooter = mock(LivingEntity.class);
		LivingEntity mobVictim = mock(LivingEntity.class);
		MeleeWeapon  weapon  = WeaponFixtures.meleeWeapon(1);

		assertTrue(WeaponRaytracerImpl.isEligibleTarget(mobVictim, shooter, request(shooter, weapon)));
	}

	@Test
	@DisplayName("the shooter itself is never an eligible target")
	void shooterExcluded() {
		LivingEntity shooter = mock(LivingEntity.class);
		MeleeWeapon  weapon  = WeaponFixtures.meleeWeapon(1);

		assertFalse(WeaponRaytracerImpl.isEligibleTarget(shooter, shooter, request(shooter, weapon)));
	}

	@Test
	@DisplayName("ItemFrame and ArmorStand are never eligible targets")
	void itemFrameAndArmorStand_excluded() {
		LivingEntity shooter = mock(LivingEntity.class);
		MeleeWeapon  weapon  = WeaponFixtures.meleeWeapon(1);
		ItemFrame    frame   = mock(ItemFrame.class);
		ArmorStand   stand   = mock(ArmorStand.class);

		assertFalse(WeaponRaytracerImpl.isEligibleTarget(frame, shooter, request(shooter, weapon)));
		assertFalse(WeaponRaytracerImpl.isEligibleTarget(stand, shooter, request(shooter, weapon)));
	}

	@Test
	@DisplayName("an otherwise-eligible candidate still respects the request's own entityFilter")
	void entityFilter_stillApplied() {
		LivingEntity shooter = mock(LivingEntity.class);
		LivingEntity mobVictim = mock(LivingEntity.class);
		MeleeWeapon  weapon  = WeaponFixtures.meleeWeapon(1);

		RaytraceRequest request = RaytraceRequest.builder()
		                                         .shooter(shooter)
		                                         .weapon(weapon)
		                                         .origin(new Location(null, 0, 0, 0))
		                                         .direction(new Vector(0, 0, 1))
		                                         .entityFilter(e -> false)
		                                         .build();

		assertFalse(WeaponRaytracerImpl.isEligibleTarget(mobVictim, shooter, request));
	}

}
