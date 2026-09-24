package org.luckyraven.bartizan.fire;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Bug docket BZ-FA-04: {@code onShutdown()} used to just {@code tracked.clear()} - fire blocks still burning at
 * server stop / {@code /reload} were left in the world as real, un-reverted blocks, and because the bookkeeping
 * was wiped too, {@code PluginFireProtectionListener} could no longer recognise them as plugin-placed on the next
 * start, so leftover fire behaved as vanilla fire and could spread and burn player structures.
 */
@DisplayName("PluginFireRegistry.onShutdown() - BZ-FA-04 reverts tracked fire before clearing")
class PluginFireRegistryTest {

	@Test
	@DisplayName("a still-FIRE tracked block is reverted to AIR")
	void onShutdown_revertsStillFireBlockToAir() {
		PluginFireRegistry registry = new PluginFireRegistry();

		UUID  worldUuid     = UUID.randomUUID();
		World trackingWorld = mock(World.class);
		when(trackingWorld.getUID()).thenReturn(worldUuid);

		Block placedBlock = mock(Block.class);
		when(placedBlock.getWorld()).thenReturn(trackingWorld);
		when(placedBlock.getX()).thenReturn(10);
		when(placedBlock.getY()).thenReturn(64);
		when(placedBlock.getZ()).thenReturn(-5);

		registry.track(placedBlock);

		World shutdownWorld = mock(World.class);
		Block liveBlock      = mock(Block.class);
		when(shutdownWorld.getBlockAt(10, 64, -5)).thenReturn(liveBlock);
		when(liveBlock.getType()).thenReturn(Material.FIRE);

		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
			bukkit.when(() -> Bukkit.getWorld(worldUuid)).thenReturn(shutdownWorld);

			registry.onShutdown();
		}

		verify(liveBlock).setType(Material.AIR);
		assertFalse(registry.isTracked(placedBlock), "tracked bookkeeping must still be cleared after reverting");
	}

	@Test
	@DisplayName("a tracked block that already burned out / changed is left alone - only FIRE is reverted")
	void onShutdown_blockNoLongerFire_neverTouched() {
		PluginFireRegistry registry = new PluginFireRegistry();

		UUID  worldUuid     = UUID.randomUUID();
		World trackingWorld = mock(World.class);
		when(trackingWorld.getUID()).thenReturn(worldUuid);

		Block placedBlock = mock(Block.class);
		when(placedBlock.getWorld()).thenReturn(trackingWorld);
		when(placedBlock.getX()).thenReturn(1);
		when(placedBlock.getY()).thenReturn(2);
		when(placedBlock.getZ()).thenReturn(3);

		registry.track(placedBlock);

		World shutdownWorld = mock(World.class);
		Block liveBlock      = mock(Block.class);
		when(shutdownWorld.getBlockAt(1, 2, 3)).thenReturn(liveBlock);
		when(liveBlock.getType()).thenReturn(Material.AIR); // already burned out on its own

		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
			bukkit.when(() -> Bukkit.getWorld(worldUuid)).thenReturn(shutdownWorld);

			registry.onShutdown();
		}

		verify(liveBlock, never()).setType(org.mockito.ArgumentMatchers.any());
	}

	@Test
	@DisplayName("a world no longer loaded is skipped without throwing, and bookkeeping is still cleared")
	void onShutdown_worldNoLongerLoaded_skipsWithoutThrowing() {
		PluginFireRegistry registry = new PluginFireRegistry();

		UUID  worldUuid     = UUID.randomUUID();
		World trackingWorld = mock(World.class);
		when(trackingWorld.getUID()).thenReturn(worldUuid);

		Block placedBlock = mock(Block.class);
		when(placedBlock.getWorld()).thenReturn(trackingWorld);
		when(placedBlock.getX()).thenReturn(0);
		when(placedBlock.getY()).thenReturn(0);
		when(placedBlock.getZ()).thenReturn(0);

		registry.track(placedBlock);

		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
			bukkit.when(() -> Bukkit.getWorld(worldUuid)).thenReturn(null); // world already unloaded

			registry.onShutdown();
		}

		assertFalse(registry.isTracked(placedBlock));
	}

}
