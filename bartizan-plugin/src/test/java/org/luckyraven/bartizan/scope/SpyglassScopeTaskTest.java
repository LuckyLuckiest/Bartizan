package org.luckyraven.bartizan.scope;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.raytrace.WeaponRaytracer;
import org.luckyraven.bartizan.api.support.WeaponFixtures;
import org.luckyraven.bartizan.api.weapon.GunWeapon;
import org.luckyraven.bartizan.api.weapon.SelectiveFire;
import org.luckyraven.bartizan.api.weapon.WeaponType;
import org.luckyraven.bartizan.api.weapon.dto.AmmunitionData;
import org.luckyraven.bartizan.api.weapon.dto.DurabilityData;
import org.luckyraven.bartizan.api.weapon.dto.EffectHook;
import org.luckyraven.bartizan.api.weapon.dto.ProjectileData;
import org.luckyraven.bartizan.api.weapon.dto.ReloadData;
import org.luckyraven.bartizan.api.weapon.dto.ScopeData;
import org.luckyraven.bartizan.api.weapon.dto.ScopeType;
import org.luckyraven.bartizan.api.weapon.reload.ReloadType;
import org.luckyraven.bartizan.effect.EffectContext;
import org.luckyraven.bartizan.effect.EffectRunner;
import org.luckyraven.bartizan.weapon.WeaponService;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers {@link SpyglassScopeTask#tick()} (weapons-roadmap.md gate {@code HP}): Spigot has no "stopped using item"
 * event, so this poll over {@code HumanEntity#isHandRaised()} is the only thing that notices a spyglass-scoped
 * player letting go of right-click.
 */
@DisplayName("SpyglassScopeTask.tick (gate HP)")
class SpyglassScopeTaskTest {

	@Test
	@DisplayName("still using the item: stays scoped, no scope-out effect fires")
	void tick_stillUsingItem_staysScoped() {
		GunWeapon         weapon        = spyglassScopedGun();
		WeaponService     weaponService = mock(WeaponService.class);
		EffectRunner      effectRunner  = mock(EffectRunner.class);
		SpyglassScopeTask task          = new SpyglassScopeTask(mock(JavaPlugin.class), weaponService,
		                                                        mock(WeaponRaytracer.class), effectRunner);

		Player player = mock(Player.class);
		when(player.getUniqueId()).thenReturn(UUID.randomUUID());
		when(player.isHandRaised()).thenReturn(true);

		task.register(player, weapon);
		task.tick();

		assertTrue(weapon.getScopeData().isScoped());
		verify(effectRunner, never()).run(any(), eq(EffectHook.ON_SCOPE_OUT), any());
	}

	@Test
	@DisplayName("hand lowered: unscopes and fires ON_SCOPE_OUT through the shared ScopeToggle path")
	void tick_handLowered_unscopesAndFiresScopeOut() {
		GunWeapon         weapon        = spyglassScopedGun();
		WeaponService     weaponService = mock(WeaponService.class);
		EffectRunner      effectRunner  = mock(EffectRunner.class);
		SpyglassScopeTask task          = new SpyglassScopeTask(mock(JavaPlugin.class), weaponService,
		                                                        mock(WeaponRaytracer.class), effectRunner);

		Player player   = mock(Player.class);
		UUID   playerId = UUID.randomUUID();
		when(player.getUniqueId()).thenReturn(playerId);
		when(player.isHandRaised()).thenReturn(true);

		task.register(player, weapon);

		// the player released right-click - isHandRaised() flips, the poll notices on its next tick.
		when(player.isHandRaised()).thenReturn(false);
		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
			bukkit.when(() -> Bukkit.getPlayer(playerId)).thenReturn(player);
			task.tick();
		}

		assertFalse(weapon.getScopeData().isScoped());
		verify(effectRunner).run(eq(weapon), eq(EffectHook.ON_SCOPE_OUT), any(EffectContext.class));
	}

	@Test
	@DisplayName("a quit mid-scope (no live Player) is cleaned up without touching ScopeData again")
	void tick_playerGone_cleansUpWithoutTouchingScopeData() {
		GunWeapon         weapon        = spyglassScopedGun();
		WeaponService     weaponService = mock(WeaponService.class);
		EffectRunner      effectRunner  = mock(EffectRunner.class);
		SpyglassScopeTask task          = new SpyglassScopeTask(mock(JavaPlugin.class), weaponService,
		                                                        mock(WeaponRaytracer.class), effectRunner);

		Player player   = mock(Player.class);
		UUID   playerId = UUID.randomUUID();
		when(player.getUniqueId()).thenReturn(playerId);

		task.register(player, weapon);

		// the player quit mid-scope - Bukkit.getPlayer(uuid) now returns null. WeaponQuitCleanupListener's own
		// weapon.unScope(player, true) is what actually clears ScopeData in this case (the belt); this poll must
		// simply not blow up trying to run ScopeToggle against a Player that no longer exists.
		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
			bukkit.when(() -> Bukkit.getPlayer(playerId)).thenReturn(null);
			task.tick();
		}

		verify(effectRunner, never()).run(any(), eq(EffectHook.ON_SCOPE_OUT), any());
	}

	private static GunWeapon spyglassScopedGun() {
		ProjectileData projectile = ProjectileData.builder()
				.speed(3.0).damage(5.0).consumed(1).perShot(1).cooldown(4).distance(60).particle(false).gravity(0.0)
				.build();
		ReloadData     reloadData     = ReloadData.builder().cooldown(20).type(ReloadType.getType("instant")).build();
		AmmunitionData ammunitionData = new AmmunitionData(WeaponFixtures.ammo("50_bmg"), 5, 1, 5);

		GunWeapon weapon = new GunWeapon(UUID.randomUUID(), "test_scout", "&fTest Scout", WeaponType.GUN,
		                                 Material.IRON_HOE, 0, (short) 100, List.of(), false, null,
		                                 SelectiveFire.SINGLE, 0, projectile, reloadData, ammunitionData);
		weapon.setDurabilityData(new DurabilityData());

		ScopeData scopeData = new ScopeData();
		scopeData.setType(ScopeType.SPYGLASS);
		scopeData.setScoped(true);
		weapon.setScopeData(scopeData);

		return weapon;
	}

}
