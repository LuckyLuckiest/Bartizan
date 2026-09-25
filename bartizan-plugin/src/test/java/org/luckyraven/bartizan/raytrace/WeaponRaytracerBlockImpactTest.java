package org.luckyraven.bartizan.raytrace;

import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.event.WeaponRaytraceImpactEvent;
import org.luckyraven.bartizan.api.raytrace.RaytraceContext;
import org.luckyraven.bartizan.api.raytrace.RaytraceRequest;
import org.luckyraven.bartizan.api.testsupport.BukkitRegistryFixture;
import org.luckyraven.bartizan.api.weapon.ProjectileState;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.api.weapon.dto.ModifiersData;
import org.luckyraven.bartizan.api.weapon.modifiers.BreakMode;
import org.luckyraven.bartizan.api.weapon.modifiers.action.BlockBreakModifier;
import org.luckyraven.bartizan.api.weapon.modifiers.BlockDamageManager;
import org.luckyraven.bartizan.api.raytrace.WeaponVisualSpawner;
import org.luckyraven.bartizan.effect.EffectRunner;
import org.luckyraven.bartizan.wearable.WearableService;
import org.mockito.MockedStatic;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BZ-RT-14: cancelling {@link WeaponRaytraceImpactEvent} did not stop a block from being cracked/broken —
 * {@code advanceRay} ran {@code applyBlockBreak} before {@code handleBlockImpact} even fired the event, so by the
 * time a listener could cancel it, {@link BlockDamageManager} had already run. Pins that
 * {@code WeaponRaytracerImpl#handleBlockImpact} now reports whether the event went through, which is what gates the
 * caller's {@code applyBlockBreak} call (see {@code advanceRay}'s block-hit branch).
 */
@DisplayName("WeaponRaytracerImpl.handleBlockImpact — cancel contract")
class WeaponRaytracerBlockImpactTest {

	@BeforeAll
	static void bootstrapBukkitRegistry() {
		// The uncancelled path spawns a cosmetic block-crack particle through XParticle — see the fixture javadoc.
		BukkitRegistryFixture.install();
	}

	private WeaponRaytracerImpl raytracer() {
		return new WeaponRaytracerImpl(mock(WearableService.class), mock(BlockDamageManager.class),
		                               mock(WeaponVisualSpawner.class), mock(EffectRunner.class));
	}

	private RaytraceContext context(World world) {
		Weapon       weapon  = mock(Weapon.class);
		LivingEntity shooter = mock(LivingEntity.class);
		RaytraceRequest request = RaytraceRequest.builder()
		                                         .shooter(shooter)
		                                         .weapon(weapon)
		                                         .origin(new Location(world, 0, 0, 0))
		                                         .direction(new Vector(1, 0, 0))
		                                         .build();
		return new RaytraceContext(request, new ProjectileState(weapon, 10.0));
	}

	@Test
	@DisplayName("a cancelled WeaponRaytraceImpactEvent returns false — the caller must not run applyBlockBreak")
	void cancelledEvent_returnsFalse() {
		WeaponRaytracerImpl raytracer = raytracer();
		World                 world   = mock(World.class);
		RaytraceContext     ctx       = context(world);
		Location             impactPt = new Location(world, 1, 0, 0);
		Block                 block   = mock(Block.class);

		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
			PluginManager pluginManager = mock(PluginManager.class);
			bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);
			doAnswer(invocation -> {
				WeaponRaytraceImpactEvent event = invocation.getArgument(0);
				event.setCancelled(true);
				return null;
			}).when(pluginManager).callEvent(any());

			boolean wentThrough = raytracer.handleBlockImpact(impactPt, block, BlockFace.UP, ctx);

			assertFalse(wentThrough, "a cancelled impact event must not report as having gone through");
		}
	}

	@Test
	@DisplayName("an uncancelled WeaponRaytraceImpactEvent returns true — the caller may run applyBlockBreak")
	void uncancelledEvent_returnsTrue() {
		WeaponRaytracerImpl raytracer = raytracer();
		World                 world   = mock(World.class);
		RaytraceContext     ctx       = context(world);
		Location             impactPt = new Location(world, 1, 0, 0);
		Block                 block   = mock(Block.class);

		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
			PluginManager pluginManager = mock(PluginManager.class);
			bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);

			boolean wentThrough = raytracer.handleBlockImpact(impactPt, block, BlockFace.UP, ctx);

			assertTrue(wentThrough);
			verify(pluginManager).callEvent(any(WeaponRaytraceImpactEvent.class));
		}
	}

	// advanceRay's wiring (BZ-RT-14 order, BZ-RT-01 shooter), driven through advanceSegment with a mocked World

	/**
	 * One ray segment from x=0 to x=3 whose block scan hits a GLASS block at x=1, fired by {@code shooter} with a
	 * weapon that breaks GLASS in one hit; {@code cancelImpact} decides whether a listener cancels the
	 * {@link WeaponRaytraceImpactEvent}.
	 */
	private static void shootGlass(BlockDamageManager blockDamageManager, Block glass, Player shooter,
	                               boolean cancelImpact) {
		World world = mock(World.class);
		when(glass.getType()).thenReturn(Material.GLASS);
		when(world.rayTraceBlocks(any(Location.class), any(Vector.class), anyDouble(), any(FluidCollisionMode.class),
		                          anyBoolean()))
				.thenReturn(new RayTraceResult(new Vector(1, 0.5, 0.5), glass, BlockFace.WEST));

		ModifiersData modifiers = new ModifiersData();
		modifiers.addBreakBlock(new BlockBreakModifier(Set.of(Material.GLASS), 1, BreakMode.DESTROY));
		Weapon weapon = mock(Weapon.class);
		when(weapon.getModifiersData()).thenReturn(modifiers);

		RaytraceRequest request = RaytraceRequest.builder()
		                                         .shooter(shooter)
		                                         .weapon(weapon)
		                                         .origin(new Location(world, 0, 0.5, 0.5))
		                                         .direction(new Vector(1, 0, 0))
		                                         .build();
		RaytraceContext ctx = new RaytraceContext(request, new ProjectileState(weapon, 10.0));

		WeaponRaytracerImpl raytracer = new WeaponRaytracerImpl(mock(WearableService.class), blockDamageManager,
		                                                        mock(WeaponVisualSpawner.class),
		                                                        mock(EffectRunner.class));

		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
			PluginManager pluginManager = mock(PluginManager.class);
			bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);
			doAnswer(invocation -> {
				if (cancelImpact && invocation.getArgument(0) instanceof WeaponRaytraceImpactEvent event) {
					event.setCancelled(true);
				}
				return null;
			}).when(pluginManager).callEvent(any());

			raytracer.advanceSegment(ctx, new Location(world, 0, 0.5, 0.5), new Location(world, 3, 0.5, 0.5));
		}
	}

	@Test
	@DisplayName("a cancelled impact event on a block hit leaves the block undamaged (BZ-RT-14)")
	void advanceRay_cancelledBlockImpact_noBlockDamage() {
		BlockDamageManager blockDamageManager = mock(BlockDamageManager.class);

		shootGlass(blockDamageManager, mock(Block.class), mock(Player.class), true);

		verify(blockDamageManager, never()).applyDamage(any(), any(), any());
		verify(blockDamageManager, never()).applyDamage(any(), any());
	}

	@Test
	@DisplayName("an uncancelled block hit breaks the block with the shooter attributed (BZ-RT-01)")
	void advanceRay_blockHit_breaksWithShooter() {
		BlockDamageManager blockDamageManager = mock(BlockDamageManager.class);
		Block              glass              = mock(Block.class);
		Player             shooter            = mock(Player.class);

		shootGlass(blockDamageManager, glass, shooter, false);

		verify(blockDamageManager).applyDamage(eq(glass), any(), eq(shooter));
	}

}
