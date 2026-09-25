package org.luckyraven.bartizan.raytrace;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.raytrace.WeaponRaytracer;
import org.luckyraven.bartizan.api.testsupport.BukkitRegistryFixture;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.api.weapon.dto.ExplosionData;
import org.luckyraven.bartizan.api.weapon.modifiers.BlockDamageManager;
import org.luckyraven.bartizan.effect.EffectRunner;
import org.luckyraven.keystone.util.ParticleUtil;
import org.mockito.MockedStatic;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BZ-RT-01: a {@code Block_Damage} explosion must hand its shooter to {@link BlockDamageManager}, so the break fires
 * a {@code WeaponBlockBreakEvent} a protection plugin can veto. {@code BlockDamageManagerTest} only covers the
 * manager itself; reverting {@code explode} to the 2-arg {@code applyDamage(block, mod)} failed nothing.
 */
@DisplayName("ExplosionHandler.explode - block damage carries the shooter")
class ExplosionHandlerBlockDamageTest {

	@BeforeAll
	static void bootstrapBukkitRegistry() {
		// Material#isAir resolves through Registry on the test API - see the fixture javadoc
		BukkitRegistryFixture.install();
	}

	@Test
	@DisplayName("a Block_Damage blast passes the player shooter to every block it breaks")
	void blockDamage_passesPlayerShooter() {
		BlockDamageManager blockDamageManager = mock(BlockDamageManager.class);
		ExplosionHandler   handler            = new ExplosionHandler(mock(JavaPlugin.class),
		                                                             mock(WeaponRaytracer.class), blockDamageManager,
		                                                             mock(EffectRunner.class));

		World world = mock(World.class);
		Block stone = mock(Block.class);
		when(stone.getType()).thenReturn(Material.STONE);
		when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(stone);

		ExplosionData data = new ExplosionData();
		data.setRadius(1);
		data.setDamage(10);
		data.setBlockDamage(true);

		Player shooter = mock(Player.class);

		try (MockedStatic<ParticleUtil> particles = mockStatic(ParticleUtil.class)) {
			handler.explode(mock(Weapon.class), data, new Location(world, 0, 64, 0), shooter, 0);
		}

		verify(blockDamageManager, atLeastOnce()).applyDamage(eq(stone), any(), eq(shooter));
		verify(blockDamageManager, never()).applyDamage(any(), any());
	}

}
