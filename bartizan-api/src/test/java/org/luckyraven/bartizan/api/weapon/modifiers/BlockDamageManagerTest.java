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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.testsupport.BukkitRegistryFixture;
import org.luckyraven.bartizan.api.weapon.modifiers.action.BlockBreakModifier;
import org.mockito.MockedStatic;

import java.util.Collections;
import java.util.Set;

import static org.bukkit.Bukkit.getPluginManager;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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
		when(block.getWorld()).thenReturn(world);
		when(location.clone()).thenReturn(location);
		when(location.add(org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.anyDouble(),
		                  org.mockito.ArgumentMatchers.anyDouble())).thenReturn(location);
		when(world.getPlayers()).thenReturn(Collections.emptyList());

		return block;
	}

	@Test
	@DisplayName("DESTROY: a cancelled BlockBreakEvent leaves the block unbroken")
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

			boolean broken = manager.applyDamage(block, modifier, shooter);

			assertFalse(broken, "a cancelled BlockBreakEvent must not report the block as broken");
			verify(block, never()).setType(any());
		}
	}

	@Test
	@DisplayName("RESTORE: a cancelled BlockBreakEvent leaves the block unbroken and never touches the scheduler")
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

			boolean broken = manager.applyDamage(block, modifier, shooter);

			assertFalse(broken, "a cancelled BlockBreakEvent must not report the block as broken");
			verify(block, never()).setType(any());
			// breakAndScheduleRestore never reaches Bukkit.getScheduler() once the event is cancelled.
			bukkit.verify(org.bukkit.Bukkit::getScheduler, never());
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

}
