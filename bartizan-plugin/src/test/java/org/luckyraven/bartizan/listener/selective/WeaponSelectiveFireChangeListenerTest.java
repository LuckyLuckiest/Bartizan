package org.luckyraven.bartizan.listener.selective;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.PluginManager;
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
import org.luckyraven.bartizan.api.weapon.dto.ProjectileData;
import org.luckyraven.bartizan.api.weapon.dto.ReloadData;
import org.luckyraven.bartizan.api.weapon.dto.ScopeData;
import org.luckyraven.bartizan.api.weapon.dto.ScopeType;
import org.luckyraven.bartizan.api.weapon.reload.ReloadType;
import org.luckyraven.bartizan.effect.EffectRunner;
import org.luckyraven.bartizan.raytrace.WeaponShooting;
import org.luckyraven.bartizan.scope.SpyglassScopeTask;
import org.luckyraven.bartizan.weapon.WeaponService;
import org.luckyraven.keystone.item.ItemBuilder;
import org.luckyraven.keystone.util.ActionBarManager;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Covers {@link WeaponSelectiveFireChangeListener}'s {@code Scope.Type: spyglass} F-fire routing (weapons-roadmap.md
 * gate {@code HP}): {@code F} fires while spyglass-scoped, does nothing once {@link SpyglassScopeTask}'s poll has
 * dropped the scope, and sneak + {@code F} keeps cycling {@code Selective_Fire} regardless. {@code Bukkit} and
 * {@link WeaponShooting} are statically mocked so the real {@link org.luckyraven.bartizan.weapon.action.GunAction}
 * fire path (reached through {@link org.luckyraven.bartizan.weapon.action.GunFireDispatcher}) runs for real -
 * {@code GunActionHandlingTest} uses the same technique.
 */
@DisplayName("WeaponSelectiveFireChangeListener - scoped F fire (gate HP)")
class WeaponSelectiveFireChangeListenerTest {

	@Test
	@DisplayName("F fires while spyglass-scoped")
	void fires_whileSpyglassScoped() {
		GunWeapon weapon = spyglassScopedGun(5);

		WeaponService weaponService = mock(WeaponService.class);
		JavaPlugin    plugin        = mock(JavaPlugin.class);
		Player        player        = mockShooter(weaponService, weapon, false);

		SpyglassScopeTask task = new SpyglassScopeTask(plugin, weaponService, mock(WeaponRaytracer.class),
		                                               mock(EffectRunner.class));
		WeaponSelectiveFireChangeListener listener = new WeaponSelectiveFireChangeListener(
				plugin, weaponService, mock(WeaponRaytracer.class), mock(EffectRunner.class), task);

		PlayerSwapHandItemsEvent event = new PlayerSwapHandItemsEvent(player, mock(ItemStack.class),
		                                                              mock(ItemStack.class));

		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
		     MockedStatic<WeaponShooting> shooting = mockStatic(WeaponShooting.class)) {
			bukkit.when(Bukkit::getPluginManager).thenReturn(mock(PluginManager.class));
			shooting.when(() -> WeaponShooting.fire(any(), any(), any(), any(), any())).thenReturn(true);
			shooting.when(() -> WeaponShooting.isHitscan(any())).thenReturn(true);

			listener.onSwapHand(event);
			listener.onSwapHandScopeCleanup(event);
		}

		assertTrue(event.isCancelled(), "F must not open the offhand swap while it fires the weapon");
		assertEquals(4, weapon.getCurrentMagCapacity(), "the shot must have consumed one round");
		assertTrue(weapon.getScopeData().isScoped(),
		          "BZ-EV-09 guard must not unscope - the weapon never actually leaves the main hand here");
	}

	@Test
	@DisplayName("two F presses on the same tick fire once - GunFireDispatcher's shared Projectile.Cooldown gate blocks the second")
	void twoPressesSameTick_fireOnce() {
		GunWeapon weapon = spyglassScopedGun(5);

		WeaponService weaponService = mock(WeaponService.class);
		JavaPlugin    plugin        = mock(JavaPlugin.class);
		Player        player        = mockShooter(weaponService, weapon, false);

		SpyglassScopeTask task = new SpyglassScopeTask(plugin, weaponService, mock(WeaponRaytracer.class),
		                                               mock(EffectRunner.class));
		WeaponSelectiveFireChangeListener listener = new WeaponSelectiveFireChangeListener(
				plugin, weaponService, mock(WeaponRaytracer.class), mock(EffectRunner.class), task);

		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
		     MockedStatic<WeaponShooting> shooting = mockStatic(WeaponShooting.class)) {
			bukkit.when(Bukkit::getPluginManager).thenReturn(mock(PluginManager.class));
			shooting.when(() -> WeaponShooting.fire(any(), any(), any(), any(), any())).thenReturn(true);
			shooting.when(() -> WeaponShooting.isHitscan(any())).thenReturn(true);

			listener.onSwapHand(new PlayerSwapHandItemsEvent(player, mock(ItemStack.class), mock(ItemStack.class)));
			// second F press lands on (effectively) the same tick - Projectile.Cooldown(4) hasn't elapsed yet.
			listener.onSwapHand(new PlayerSwapHandItemsEvent(player, mock(ItemStack.class), mock(ItemStack.class)));
		}

		assertEquals(4, weapon.getCurrentMagCapacity(), "the second press must be gated - mashing F must not fire twice");
	}

	@Test
	@DisplayName("F does nothing once the scope has dropped (SpyglassScopeTaskTest covers the isHandRaised() poll itself)")
	void doesNotFire_onceUnscoped() {
		GunWeapon weapon = spyglassScopedGun(5);
		weapon.getScopeData().setScoped(false); // e.g. SpyglassScopeTask's poll already noticed isHandRaised() drop

		WeaponService weaponService = mock(WeaponService.class);
		JavaPlugin    plugin        = mock(JavaPlugin.class);
		Player        player        = mockShooter(weaponService, weapon, false);

		SpyglassScopeTask task = new SpyglassScopeTask(plugin, weaponService, mock(WeaponRaytracer.class),
		                                               mock(EffectRunner.class));
		WeaponSelectiveFireChangeListener listener = new WeaponSelectiveFireChangeListener(
				plugin, weaponService, mock(WeaponRaytracer.class), mock(EffectRunner.class), task);

		PlayerSwapHandItemsEvent event = new PlayerSwapHandItemsEvent(player, mock(ItemStack.class),
		                                                              mock(ItemStack.class));

		listener.onSwapHand(event);

		assertFalse(event.isCancelled(), "not scoped and not sneaking - F is a plain offhand swap again");
		assertEquals(5, weapon.getCurrentMagCapacity(), "nothing should have fired");
	}

	@Test
	@DisplayName("sneak + F still cycles Selective_Fire while spyglass-scoped")
	void sneakPlusF_stillChangesSelectiveFire() {
		GunWeapon weapon = spyglassScopedGun(5);

		WeaponService weaponService = mock(WeaponService.class);
		JavaPlugin    plugin        = mock(JavaPlugin.class);
		Player        player        = mockShooter(weaponService, weapon, true);
		when(weaponService.getHeldWeaponItem(player)).thenReturn(mock(ItemBuilder.class));

		SpyglassScopeTask task = new SpyglassScopeTask(plugin, weaponService, mock(WeaponRaytracer.class),
		                                               mock(EffectRunner.class));
		WeaponSelectiveFireChangeListener listener = new WeaponSelectiveFireChangeListener(
				plugin, weaponService, mock(WeaponRaytracer.class), mock(EffectRunner.class), task);

		PlayerSwapHandItemsEvent event = new PlayerSwapHandItemsEvent(player, mock(ItemStack.class),
		                                                              mock(ItemStack.class));

		// ActionBarManager is statically mocked too - the selective-fire-cycle feedback message routes through
		// XSeries' ActionBar/XReflection, which needs a well-formed Bukkit.getVersion() this test never provides
		// (mirrors ChargeControllerTest#tick).
		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
		     MockedStatic<ActionBarManager> actionBar = mockStatic(ActionBarManager.class)) {
			bukkit.when(Bukkit::getPluginManager).thenReturn(mock(PluginManager.class));

			listener.onSwapHand(event);
			listener.onSwapHandScopeCleanup(event);
		}

		assertTrue(event.isCancelled());
		assertNotEquals(SelectiveFire.SINGLE, weapon.getCurrentSelectiveFire(), "Selective_Fire must have cycled");
		assertEquals(5, weapon.getCurrentMagCapacity(), "sneak + F cycles fire mode, it never fires a shot");
		assertTrue(weapon.getScopeData().isScoped(),
		          "BZ-EV-09 guard must not unscope - the weapon never actually leaves the main hand here");
	}

	@Test
	@DisplayName("BZ-EV-09: F swap while not sneaking unscopes a SLOWNESS-scoped weapon before it leaves the main hand")
	void notSneaking_slownessScoped_unscopesBeforeSwapToOffHand() {
		GunWeapon weapon = slownessScopedGun(5);

		WeaponService weaponService = mock(WeaponService.class);
		JavaPlugin    plugin        = mock(JavaPlugin.class);
		Player        player        = mockShooter(weaponService, weapon, false);

		SpyglassScopeTask task = new SpyglassScopeTask(plugin, weaponService, mock(WeaponRaytracer.class),
		                                               mock(EffectRunner.class));
		WeaponSelectiveFireChangeListener listener = new WeaponSelectiveFireChangeListener(
				plugin, weaponService, mock(WeaponRaytracer.class), mock(EffectRunner.class), task);

		PlayerSwapHandItemsEvent event = new PlayerSwapHandItemsEvent(player, mock(ItemStack.class),
		                                                              mock(ItemStack.class));

		listener.onSwapHand(event);
		listener.onSwapHandScopeCleanup(event);

		assertFalse(event.isCancelled(), "a plain SLOWNESS-scoped weapon has no Cancel.Swap_Hands - the swap "
		                                 + "itself still proceeds, only F's own selective-fire cycling is gated");
		assertFalse(weapon.getScopeData().isScoped(),
		           "the weapon must be unscoped before it moves off-hand, or SLOWNESS is stuck applied with no "
		           + "cleanup path (BZ-EV-09)");
	}

	/**
	 * A {@code Scope.Type: spyglass} {@link GunWeapon}, already scoped in, with {@code maxMag} rounds loaded.
	 */
	private static GunWeapon spyglassScopedGun(int maxMag) {
		ProjectileData projectile = ProjectileData.builder()
				.speed(3.0).damage(5.0).consumed(1).perShot(1).cooldown(4).distance(60).particle(false).gravity(0.0)
				.build();
		ReloadData     reloadData     = ReloadData.builder().cooldown(20).type(ReloadType.getType("instant")).build();
		AmmunitionData ammunitionData = new AmmunitionData(WeaponFixtures.ammo("50_bmg"), maxMag, 1, maxMag);

		// Material is irrelevant here - WeaponSelectiveFireChangeListener/SpyglassScopeTask only ever look at
		// ScopeData.getType(); the Material == SPYGLASS requirement is enforced once, at parse time, by
		// WeaponAddon (see ScopeConfigTest). Material.SPYGLASS doesn't exist on the 1.16.5 compile floor anyway.
		GunWeapon weapon = new GunWeapon(UUID.randomUUID(), "test_scout", "&fTest Scout", WeaponType.GUN,
		                                 Material.IRON_HOE, 0, (short) 100, List.of(), false, null,
		                                 SelectiveFire.SINGLE, 0, projectile, reloadData, ammunitionData);
		DurabilityData durabilityData = new DurabilityData();
		durabilityData.setConsumeOnTime(-1); // -1 = disabled; 0 would arm GunFireDispatcher's CountdownTimer and
		                                     // need a scheduler this test never stubs (Weapon_Consumed.Time: -1
		                                     // in every bundled weapon file means the same thing).
		weapon.setDurabilityData(durabilityData);

		ScopeData scopeData = new ScopeData();
		scopeData.setType(ScopeType.SPYGLASS);
		scopeData.setScoped(true);
		weapon.setScopeData(scopeData);

		return weapon;
	}

	/**
	 * A default {@code Scope.Type: SLOWNESS} {@link GunWeapon} (BZ-EV-09) - the shipped {@code awp.yml} shape, no
	 * {@code Scope.Type: spyglass} key - already scoped in, with {@code maxMag} rounds loaded.
	 */
	private static GunWeapon slownessScopedGun(int maxMag) {
		ProjectileData projectile = ProjectileData.builder()
				.speed(3.0).damage(5.0).consumed(1).perShot(1).cooldown(4).distance(60).particle(false).gravity(0.0)
				.build();
		ReloadData     reloadData     = ReloadData.builder().cooldown(20).type(ReloadType.getType("instant")).build();
		AmmunitionData ammunitionData = new AmmunitionData(WeaponFixtures.ammo("50_bmg"), maxMag, 1, maxMag);

		GunWeapon weapon = new GunWeapon(UUID.randomUUID(), "test_awp", "&fTest AWP", WeaponType.GUN,
		                                 Material.IRON_HOE, 0, (short) 100, List.of(), false, null,
		                                 SelectiveFire.SINGLE, 0, projectile, reloadData, ammunitionData);
		DurabilityData durabilityData = new DurabilityData();
		durabilityData.setConsumeOnTime(-1);
		weapon.setDurabilityData(durabilityData);

		ScopeData scopeData = new ScopeData(); // type defaults to ScopeType.SLOWNESS
		scopeData.setScoped(true);
		weapon.setScopeData(scopeData);

		return weapon;
	}

	/**
	 * A {@code Player} mock resolving {@code weapon} as the main-hand weapon, with a real {@link Location} for
	 * {@code getEyeLocation()} - needed because a successful shot always reaches {@code EffectContext.shot}/
	 * {@code WeaponMuzzle.compute}, which do real {@code Location}/{@code Vector} arithmetic (mirrors
	 * {@code GunActionHandlingTest#mockShooter}).
	 */
	private static Player mockShooter(WeaponService weaponService, GunWeapon weapon, boolean sneaking) {
		ItemStack       item      = mock(ItemStack.class);
		PlayerInventory inventory = mock(PlayerInventory.class);
		when(inventory.getItemInMainHand()).thenReturn(item);
		when(inventory.getHeldItemSlot()).thenReturn(0);

		Player player = mock(Player.class);
		when(player.getUniqueId()).thenReturn(UUID.randomUUID());
		when(player.getInventory()).thenReturn(inventory);
		when(player.isSneaking()).thenReturn(sneaking);
		when(player.isHandRaised()).thenReturn(true);
		when(player.getEyeLocation()).thenReturn(new Location(null, 0, 64, 0, 0f, 0f));

		when(weaponService.validateAndGetWeapon(player, item)).thenReturn(weapon);
		when(weaponService.getHeldWeaponItem(player)).thenReturn(mock(ItemBuilder.class));
		// GunAction resolves the firing weapon's own item by uuid (BZ-EV-12)
		when(weaponService.getHeldWeaponItem(player, weapon)).thenReturn(mock(ItemBuilder.class));

		return player;
	}

}
