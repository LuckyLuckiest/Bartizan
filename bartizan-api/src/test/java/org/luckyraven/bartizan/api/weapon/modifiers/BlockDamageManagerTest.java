package org.luckyraven.bartizan.api.weapon.modifiers;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.testsupport.BukkitRegistryFixture;
import org.luckyraven.bartizan.api.weapon.modifiers.action.BlockBreakModifier;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.Collections;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BZ-RT-01: {@code destroyBlock}/{@code breakAndScheduleRestore} used to set the block to {@code AIR} without ever
 * firing a vanilla {@link BlockBreakEvent}, so a protection plugin (WorldGuard, GriefPrevention, ...) had no chance
 * to veto a weapon-caused break the way it vetoes a hand-mined one. Pins that a cancelled {@link BlockBreakEvent}
 * now leaves the block untouched, for both {@link BreakMode#DESTROY} and {@link BreakMode#RESTORE}.
 * <p>
 * BZ-RT-06: {@code sendBlockDamage}/{@code clearBlockDamage} used to {@code Objects.requireNonNull(location
 * .getWorld())}, so a regen/restore task that fired after the block's world unloaded threw an uncaught NPE inside
 * the Bukkit scheduler callback. Pins that a hit on a block whose world is already {@code null} no longer throws.
 */
@DisplayName("BlockDamageManager — vanilla BlockBreakEvent on a weapon-caused break")
class BlockDamageManagerTest {

	@BeforeAll
	static void bootstrapBukkitRegistry() {
		BukkitRegistryFixture.install();
	}

	private BlockDamageManager manager() {
		JavaPlugin plugin = mock(JavaPlugin.class);
		BlockRegenerationSettings settings = mock(BlockRegenerationSettings.class);
		when(settings.getRegenerationDelayTicks()).thenReturn(100);
		when(settings.getRegenerationStepTicks()).thenReturn(5);
		when(settings.getRestoreDelayTicks()).thenReturn(100);
		return new BlockDamageManager(plugin, settings);
	}

	private Block block(Material material) {
		Block     block     = mock(Block.class);
		Location  location  = mock(Location.class);
		World     world     = mock(World.class);
		BlockData blockData = mock(BlockData.class);

		when(block.getLocation()).thenReturn(location);
		when(block.getType()).thenReturn(material);
		when(block.getBlockData()).thenReturn(blockData);
		when(blockData.clone()).thenReturn(blockData);
		when(location.getWorld()).thenReturn(world);
		when(location.isWorldLoaded()).thenReturn(true);
		when(block.getWorld()).thenReturn(world);
		when(location.clone()).thenReturn(location);
		when(location.add(org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.anyDouble(),
		                  org.mockito.ArgumentMatchers.anyDouble())).thenReturn(location);
		when(world.getPlayers()).thenReturn(Collections.emptyList());

		return block;
	}

	@Test
	@DisplayName("DESTROY: a cancelled BlockBreakEvent leaves the block unbroken and falls back to regeneration")
	void destroyMode_cancelledEvent_blockNotBroken() {
		BlockDamageManager manager  = manager();
		Block              block    = block(Material.GLASS);
		Player             shooter  = mock(Player.class);
		BlockBreakModifier modifier = new BlockBreakModifier(Set.of(Material.GLASS), 1, BreakMode.DESTROY);

		try (MockedStatic<org.bukkit.Bukkit> bukkit = mockStatic(org.bukkit.Bukkit.class)) {
			PluginManager pluginManager = mock(PluginManager.class);
			bukkit.when(org.bukkit.Bukkit::getPluginManager).thenReturn(pluginManager);
			doAnswer(invocation -> {
				BlockBreakEvent event = invocation.getArgument(0);
				event.setCancelled(true);
				return null;
			}).when(pluginManager).callEvent(any());
			BukkitScheduler scheduler = mock(BukkitScheduler.class);
			bukkit.when(org.bukkit.Bukkit::getScheduler).thenReturn(scheduler);
			when(scheduler.runTaskLater(any(), any(Runnable.class), anyLong())).thenReturn(mock(BukkitTask.class));

			boolean broken = manager.applyDamage(block, modifier, shooter);

			assertFalse(broken, "a cancelled BlockBreakEvent must not report the block as broken");
			verify(block, never()).setType(any());
			// BZ-RT-01 follow-up: the cancelled path must not leave a permanent max-stage crack / leaked
			// damagedBlocks entry - it falls back to scheduleRegeneration just like the uncancelled below-threshold
			// path does.
			verify(scheduler).runTaskLater(any(), any(Runnable.class), anyLong());
		}
	}

	@Test
	@DisplayName("RESTORE: a cancelled BlockBreakEvent leaves the block unbroken and falls back to regeneration")
	void restoreMode_cancelledEvent_blockNotBroken() {
		BlockDamageManager manager  = manager();
		Block              block    = block(Material.ICE);
		Player             shooter  = mock(Player.class);
		BlockBreakModifier modifier = new BlockBreakModifier(Set.of(Material.ICE), 1, BreakMode.RESTORE);

		try (MockedStatic<org.bukkit.Bukkit> bukkit = mockStatic(org.bukkit.Bukkit.class)) {
			PluginManager pluginManager = mock(PluginManager.class);
			bukkit.when(org.bukkit.Bukkit::getPluginManager).thenReturn(pluginManager);
			doAnswer(invocation -> {
				BlockBreakEvent event = invocation.getArgument(0);
				event.setCancelled(true);
				return null;
			}).when(pluginManager).callEvent(any());
			BukkitScheduler scheduler = mock(BukkitScheduler.class);
			bukkit.when(org.bukkit.Bukkit::getScheduler).thenReturn(scheduler);
			when(scheduler.runTaskLater(any(), any(Runnable.class), anyLong())).thenReturn(mock(BukkitTask.class));

			boolean broken = manager.applyDamage(block, modifier, shooter);

			assertFalse(broken, "a cancelled BlockBreakEvent must not report the block as broken");
			verify(block, never()).setType(any());
			// breakAndScheduleRestore's own restore-task scheduling never runs once the event is cancelled - the
			// only scheduler call must be applyDamage's regeneration fallback (BZ-RT-01 follow-up).
			verify(scheduler).runTaskLater(any(), any(Runnable.class), anyLong());
		}
	}

	@Test
	@DisplayName("DESTROY: an uncancelled event fires with the shooter as the BlockBreakEvent player and breaks the block")
	void destroyMode_uncancelledEvent_firesEventAndBreaksBlock() {
		BlockDamageManager manager  = manager();
		Block              block    = block(Material.GLASS);
		Player             shooter  = mock(Player.class);
		BlockBreakModifier modifier = new BlockBreakModifier(Set.of(Material.GLASS), 1, BreakMode.DESTROY);

		try (MockedStatic<org.bukkit.Bukkit> bukkit = mockStatic(org.bukkit.Bukkit.class)) {
			PluginManager pluginManager = mock(PluginManager.class);
			bukkit.when(org.bukkit.Bukkit::getPluginManager).thenReturn(pluginManager);

			boolean broken = manager.applyDamage(block, modifier, shooter);

			assertTrue(broken);
			verify(block).setType(Material.AIR);

			org.mockito.ArgumentCaptor<BlockBreakEvent> captor = org.mockito.ArgumentCaptor.forClass(BlockBreakEvent.class);
			verify(pluginManager).callEvent(captor.capture());
			assertTrue(captor.getValue().getPlayer() == shooter);
			assertTrue(captor.getValue().getBlock() == block);
			// BZ-RT-01: must be the WeaponBlockBreakEvent marker subclass, not a plain BlockBreakEvent, so
			// Bartizan's own WeaponInteract.onBlockBreak can recognize and skip its own synthetic event instead of
			// cancelling every weapon-caused break against itself.
			assertTrue(captor.getValue() instanceof WeaponBlockBreakEvent);
			assertFalse(captor.getValue().isDropItems(), "the synthetic break doesn't drop items itself");
		}
	}

	@Test
	@DisplayName("no attributable player (NPC shooter / explosion): the break proceeds without firing any BlockBreakEvent")
	void nullPlayer_noEventFired_blockStillBreaks() {
		BlockDamageManager manager  = manager();
		Block              block    = block(Material.GLASS);
		BlockBreakModifier modifier = new BlockBreakModifier(Set.of(Material.GLASS), 1, BreakMode.DESTROY);

		boolean broken = manager.applyDamage(block, modifier, null);

		assertTrue(broken);
		verify(block).setType(Material.AIR);
	}

	@Test
	@DisplayName("BZ-RT-06: a hit on a block whose world already unloaded does not throw")
	void applyDamage_unloadedWorld_doesNotThrow() {
		BlockDamageManager manager   = manager();
		Block              block     = mock(Block.class);
		Location           location  = mock(Location.class);
		BlockData          blockData = mock(BlockData.class);

		when(block.getLocation()).thenReturn(location);
		when(block.getType()).thenReturn(Material.GLASS);
		when(block.getBlockData()).thenReturn(blockData);
		when(blockData.clone()).thenReturn(blockData);
		// A real Location never returns null from getWorld() - on Spigot 1.16.5+ it throws
		// IllegalArgumentException("World unloaded") once the world's weak reference is cleared. isWorldLoaded()
		// is the only safe pre-check, so the mock pins that the guarded code never calls getWorld() at all here.
		when(location.isWorldLoaded()).thenReturn(false);
		when(location.getWorld()).thenThrow(new IllegalArgumentException("World unloaded"));

		// hitsRequired 5, one hit: stays below the threshold, so the only paths exercised are
		// sendBlockDamage (BZ-RT-06) and scheduleRegeneration — not destroyBlock/breakAndScheduleRestore.
		BlockBreakModifier modifier = new BlockBreakModifier(Set.of(Material.GLASS), 5, BreakMode.RESTORE);

		try (MockedStatic<org.bukkit.Bukkit> bukkit = mockStatic(org.bukkit.Bukkit.class)) {
			BukkitScheduler scheduler = mock(BukkitScheduler.class);
			bukkit.when(org.bukkit.Bukkit::getScheduler).thenReturn(scheduler);
			when(scheduler.runTaskLater(any(), any(Runnable.class), anyLong())).thenReturn(mock(BukkitTask.class));

			assertDoesNotThrow(() -> manager.applyDamage(block, modifier, null));
		}
	}

	@Test
	@DisplayName("BZ-RT-06: clearAll skips the block lookup for an entry whose world is unloaded")
	void clearAll_unloadedWorld_skipsBlockLookup() {
		BlockDamageManager manager  = manager();
		Block              block    = block(Material.ICE);
		Player             shooter  = mock(Player.class);
		BlockBreakModifier modifier = new BlockBreakModifier(Set.of(Material.ICE), 1, BreakMode.RESTORE);
		Location           location = block.getLocation();

		try (MockedStatic<org.bukkit.Bukkit> bukkit = mockStatic(org.bukkit.Bukkit.class)) {
			PluginManager pluginManager = mock(PluginManager.class);
			bukkit.when(org.bukkit.Bukkit::getPluginManager).thenReturn(pluginManager);
			BukkitScheduler scheduler = mock(BukkitScheduler.class);
			bukkit.when(org.bukkit.Bukkit::getScheduler).thenReturn(scheduler);
			when(scheduler.runTaskLater(any(), any(Runnable.class), anyLong())).thenReturn(mock(BukkitTask.class));

			// RESTORE, uncancelled: block is now AIR and mid-restore (damagedBlocks entry has broken = true).
			assertTrue(manager.applyDamage(block, modifier, shooter));

			// World unloads before the pending restore task (and before this clearAll) ever runs.
			when(location.isWorldLoaded()).thenReturn(false);

			assertDoesNotThrow(manager::clearAll);
			verify(location, never()).getBlock();
		}
	}

	/** Breaks {@code block} in RESTORE mode, returning the pending restore task the scheduler was handed. */
	private Runnable breakForRestore(BlockDamageManager manager, Block block, MockedStatic<org.bukkit.Bukkit> bukkit) {
		bukkit.when(org.bukkit.Bukkit::getPluginManager).thenReturn(mock(PluginManager.class));
		BukkitScheduler scheduler = mock(BukkitScheduler.class);
		bukkit.when(org.bukkit.Bukkit::getScheduler).thenReturn(scheduler);
		when(scheduler.runTaskLater(any(), any(Runnable.class), anyLong())).thenReturn(mock(BukkitTask.class));

		BlockBreakModifier modifier = new BlockBreakModifier(Set.of(block.getType()), 1, BreakMode.RESTORE);
		assertTrue(manager.applyDamage(block, modifier, mock(Player.class)));

		ArgumentCaptor<Runnable> restoreTask = ArgumentCaptor.forClass(Runnable.class);
		verify(scheduler).runTaskLater(any(), restoreTask.capture(), anyLong());
		return restoreTask.getValue();
	}

	@Test
	@DisplayName("BZ-RT-15: restoreWorld writes a mid-restore block back before its world unloads")
	void restoreWorld_writesBrokenBlockBack() {
		BlockDamageManager manager = manager();
		Block              block   = block(Material.GLASS);
		Location           location = block.getLocation();
		World              world    = location.getWorld();
		BlockData          original = block.getBlockData();

		try (MockedStatic<org.bukkit.Bukkit> bukkit = mockStatic(org.bukkit.Bukkit.class)) {
			breakForRestore(manager, block, bukkit);
		}
		// the break set it to AIR; the unload is about to save that
		when(block.getType()).thenReturn(Material.AIR);
		when(location.getBlock()).thenReturn(block);

		manager.restoreWorld(world);

		verify(block).setBlockData(original);
	}

	@Test
	@DisplayName("BZ-RT-15: restoreWorld leaves another world's mid-restore block alone")
	void restoreWorld_otherWorld_untouched() {
		BlockDamageManager manager = manager();
		Block              block   = block(Material.GLASS);

		try (MockedStatic<org.bukkit.Bukkit> bukkit = mockStatic(org.bukkit.Bukkit.class)) {
			breakForRestore(manager, block, bukkit);
		}

		manager.restoreWorld(mock(World.class));

		verify(block, never()).setBlockData(any());
	}

	@Test
	@DisplayName("BZ-RT-06: the restore task checks the world is loaded before reading the block")
	void restoreTask_unloadedWorld_neverReadsBlock() {
		BlockDamageManager manager  = manager();
		Block              block    = block(Material.GLASS);
		Location           location = block.getLocation();

		Runnable restoreTask;
		try (MockedStatic<org.bukkit.Bukkit> bukkit = mockStatic(org.bukkit.Bukkit.class)) {
			restoreTask = breakForRestore(manager, block, bukkit);
		}

		when(location.isWorldLoaded()).thenReturn(false);
		clearInvocations(block);
		restoreTask.run();

		verify(block, never()).getType();
		verify(block, never()).setBlockData(any());
	}

}
