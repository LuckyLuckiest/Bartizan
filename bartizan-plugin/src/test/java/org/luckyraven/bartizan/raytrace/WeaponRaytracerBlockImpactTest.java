package org.luckyraven.bartizan.raytrace;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.PluginManager;
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
import org.luckyraven.bartizan.api.weapon.modifiers.BlockDamageManager;
import org.luckyraven.bartizan.api.raytrace.WeaponVisualSpawner;
import org.luckyraven.bartizan.effect.EffectRunner;
import org.luckyraven.bartizan.wearable.WearableService;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;

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

}
