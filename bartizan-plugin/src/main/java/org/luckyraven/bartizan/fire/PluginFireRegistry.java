package org.luckyraven.bartizan.fire;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.util.BlockVector;
import org.luckyraven.keystone.bean.BeanLifecycle;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of every {@code Material.FIRE} block placed by the weapon system (incendiary impacts and explosive
 * throwables). The companion {@code PluginFireProtectionListener} consults this set to keep every plugin-spawned fire
 * purely cosmetic — vanilla burn/spread/ignite events involving a tracked block are cancelled, so wood, wool and other
 * flammable terrain is never consumed even when many fires overlap.
 *
 * <p>Keys are {@link BlockVector} instances (integer coordinates) grouped by world UUID; raw {@link
 * org.bukkit.Location} is unsuitable because its {@code equals} considers yaw/pitch.
 */
public class PluginFireRegistry implements BeanLifecycle {

	private static final BlockFace[] FACE_NEIGHBOURS = {
			BlockFace.UP, BlockFace.DOWN,
			BlockFace.NORTH, BlockFace.SOUTH,
			BlockFace.EAST, BlockFace.WEST
	};

	private final Map<UUID, Set<BlockVector>> tracked = new ConcurrentHashMap<>();

	private static BlockVector toKey(Block block) {
		return new BlockVector(block.getX(), block.getY(), block.getZ());
	}

	public void track(Block block) {
		if (block == null) return;
		World world = block.getWorld();
		tracked.computeIfAbsent(world.getUID(), k -> ConcurrentHashMap.newKeySet())
		       .add(toKey(block));
	}

	public void untrack(Block block) {
		if (block == null) return;
		Set<BlockVector> worldSet = tracked.get(block.getWorld().getUID());
		if (worldSet == null) return;
		worldSet.remove(toKey(block));
	}

	public boolean isTracked(Block block) {
		if (block == null) return false;
		Set<BlockVector> worldSet = tracked.get(block.getWorld().getUID());
		return worldSet != null && worldSet.contains(toKey(block));
	}

	/**
	 * True when any of the six face neighbours of {@code block} is a tracked fire. Used as a fallback for
	 * {@code BlockBurnEvent} on Spigot, where {@code getIgnitingBlock()} can be null.
	 */
	public boolean hasTrackedNeighbour(Block block) {
		if (block == null) return false;
		Set<BlockVector> worldSet = tracked.get(block.getWorld().getUID());
		if (worldSet == null || worldSet.isEmpty()) return false;
		for (BlockFace face : FACE_NEIGHBOURS) {
			Block neighbour = block.getRelative(face);
			if (worldSet.contains(toKey(neighbour))) return true;
		}
		return false;
	}

	/**
	 * Reverts every still-{@code Material.FIRE} tracked block to {@code AIR} before clearing the bookkeeping (bug
	 * docket BZ-FA-04). Fire blocks are reverted by their own per-block scheduled tasks, which are cancelled along
	 * with everything else when the plugin disables - without this, any fire still burning at server stop or
	 * {@code /reload} was left in the world as a real block, and clearing {@link #tracked} without reverting it
	 * first meant {@code PluginFireProtectionListener} could no longer recognise it as plugin-placed on the next
	 * start, so it behaved as vanilla fire and could spread and burn player structures.
	 */
	@Override
	public void onShutdown() {
		for (Map.Entry<UUID, Set<BlockVector>> entry : tracked.entrySet()) {
			World world = Bukkit.getWorld(entry.getKey());
			if (world == null) continue;

			for (BlockVector vector : entry.getValue()) {
				Block block = world.getBlockAt(vector.getBlockX(), vector.getBlockY(), vector.getBlockZ());
				if (block.getType() == Material.FIRE) {
					block.setType(Material.AIR);
				}
			}
		}

		tracked.clear();
	}

}
