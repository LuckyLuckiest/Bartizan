package org.luckyraven.bartizan.api.weapon.modifiers;

import com.cryptomorin.xseries.XSound;
import com.cryptomorin.xseries.particles.XParticle;
import lombok.Getter;
import lombok.Setter;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;
import org.luckyraven.bartizan.api.weapon.modifiers.action.BlockBreakModifier;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages block damage states for weapon projectile impacts. Handles crack animation progression and block
 * regeneration.
 */
public class BlockDamageManager {

	private static final int MAX_DAMAGE_STAGE = 9;

	private final JavaPlugin                      plugin;
	private final BlockRegenerationSettings       settings;
	private final Map<Location, BlockDamageState> damagedBlocks;

	private int entityIdCounter;

	public BlockDamageManager(JavaPlugin plugin, BlockRegenerationSettings settings) {
		this.plugin          = plugin;
		this.settings        = settings;
		this.damagedBlocks   = new ConcurrentHashMap<>();
		this.entityIdCounter = Integer.MAX_VALUE - 100000;
	}

	/**
	 * Applies damage to a block from a projectile hit. Equivalent to {@code applyDamage(block, modifier, null)} — no
	 * vanilla {@link BlockBreakEvent} is fired, so nothing can veto the break. Prefer the 3-arg overload whenever a
	 * {@link Player} caused the hit, so protection plugins (WorldGuard, GriefPrevention, ...) get a chance to see it.
	 *
	 * @param block The block that was hit
	 * @param modifier The break block modifier configuration
	 *
	 * @return true if the block was broken, false otherwise
	 */
	public boolean applyDamage(Block block, BlockBreakModifier modifier) {
		return applyDamage(block, modifier, null);
	}

	/**
	 * Applies damage to a block from a projectile hit fired by {@code player}. A {@code DESTROY}/{@code RESTORE}
	 * break fires a cancellable vanilla {@link BlockBreakEvent} first — the same event a protection plugin already
	 * listens to for a hand-mined block — so a claim/region plugin can veto a weapon-caused break exactly as it
	 * would a punch. {@code player} is {@code null} for a weapon-caused break with no attributable player (an NPC
	 * shooter, an explosion): the break proceeds unchecked in that case, matching the pre-existing behaviour.
	 *
	 * @param block The block that was hit
	 * @param modifier The break block modifier configuration
	 * @param player The player who caused the hit, or {@code null} if none is attributable
	 *
	 * @return true if the block was broken, false otherwise (including when a listener cancelled the break)
	 */
	public boolean applyDamage(Block block, BlockBreakModifier modifier, @Nullable Player player) {
		Location location = block.getLocation();
		Material material = block.getType();

		if (!modifier.appliesTo(material)) {
			return false;
		}

		// Ignore hits on a location that's mid-restore (block is currently AIR pending reappearance).
		BlockDamageState existing = damagedBlocks.get(location);
		if (existing != null && existing.isBroken()) {
			return false;
		}

		var state = damagedBlocks.computeIfAbsent(location, loc -> new BlockDamageState(block.getBlockData().clone(),
		                                                                                generateEntityId(), material));

		// Cancel any ongoing regeneration
		state.cancelRegeneration();

		// Increment hit count
		state.incrementHits();

		// Calculate damage stage based on hits
		int hitsRequired = modifier.hitsRequired();
		int currentHits  = state.getHitCount();
		int damageStage  = Math.min(MAX_DAMAGE_STAGE, (currentHits * MAX_DAMAGE_STAGE) / hitsRequired);

		state.setCurrentStage(damageStage);

		// Send crack animation to nearby players
		sendBlockDamage(location, damageStage, state.getEntityId());

		// Check if block should reach the threshold
		if (currentHits >= hitsRequired) {
			switch (modifier.mode()) {
				case DESTROY -> {
					if (destroyBlock(block, location, player)) {
						return true;
					}
					// A listener cancelled the BlockBreakEvent: fall back to regeneration instead of leaving a
					// permanent max-stage crack overlay and a leaked damagedBlocks entry (BZ-RT-01 follow-up).
					scheduleRegeneration(location, state);
					return false;
				}
				case RESTORE -> {
					if (breakAndScheduleRestore(block, location, state, hitsRequired, player)) {
						return true;
					}
					scheduleRegeneration(location, state);
					return false;
				}
				case CRACK_ONLY -> {
					// Block reached max damage but should not break.
					// Keep it at max crack state and start regeneration.
					scheduleRegeneration(location, state);
					return false;
				}
			}
		}

		// Schedule regeneration for later
		scheduleRegeneration(location, state);

		return false;
	}

	/**
	 * Clears all block damage states (useful for plugin disable). Restores any blocks that are mid-restore so the world
	 * is left in a sane state after a reload.
	 */
	public void clearAll() {
		for (Map.Entry<Location, BlockDamageState> entry : damagedBlocks.entrySet()) {
			restore(entry.getKey(), entry.getValue());
		}
		damagedBlocks.clear();
	}

	/**
	 * {@link #clearAll()} for one world, called as it unloads: the unload saves its chunks while a block broken in
	 * {@code RESTORE} mode is still {@code AIR}, and the pending restore task then finds the world gone and drops the
	 * restore, so the block would be saved as {@code AIR} for good (BZ-RT-15). Call it before the save — Bukkit's
	 * {@code WorldUnloadEvent} fires while the world is still loaded.
	 */
	public void restoreWorld(World world) {
		damagedBlocks.entrySet().removeIf(entry -> {
			Location location = entry.getKey();
			// BZ-RT-06: getWorld() throws for an entry whose own world is already gone
			if (!location.isWorldLoaded() || !world.equals(location.getWorld())) return false;

			restore(location, entry.getValue());
			return true;
		});
	}

	/**
	 * Cancels {@code state}'s pending task, writes a mid-restore block back and clears its crack overlay.
	 */
	private void restore(Location location, BlockDamageState state) {
		state.cancelRegeneration();
		// BZ-RT-06: location.getWorld() throws once the world's weak reference is cleared (Spigot 1.16.5+),
		// it does not return null - isWorldLoaded() is the only safe check before touching the block.
		if (state.isBroken() && location.isWorldLoaded()) {
			Block block = location.getBlock();
			if (block.getType() == Material.AIR) {
				block.setBlockData(state.getOriginalData());
			}
		}
		clearBlockDamage(location, state.getEntityId());
	}

	/**
	 * Sends block damage animation to all players within render distance. A no-op if {@code location}'s world has
	 * been unloaded (BZ-RT-06) — a delayed/repeating regeneration or restore task can still fire after that, and
	 * must not throw inside the Bukkit scheduler callback. {@code location.getWorld()} throws once the world's weak
	 * reference is cleared (Spigot 1.16.5+ {@code Location.getWorld()}); it never returns {@code null}, so
	 * {@link Location#isWorldLoaded()} is the only safe check.
	 */
	private void sendBlockDamage(Location location, int stage, int entityId) {
		if (!location.isWorldLoaded()) {
			return;
		}
		World world = location.getWorld();

		float progress = Math.max(0.0f, Math.min(stage / (float) MAX_DAMAGE_STAGE, 1.0f));
		for (Player player : world.getPlayers()) {
			if (player.getLocation().distanceSquared(location) > 64 * 64) continue;
			sendBlockDamage(player, location, progress, entityId);
		}
	}

	/**
	 * Clears the block damage animation for a location. A no-op if {@code location}'s world has been unloaded
	 * (BZ-RT-06) — see {@link #sendBlockDamage(Location, int, int)}.
	 */
	private void clearBlockDamage(Location location, int entityId) {
		if (!location.isWorldLoaded()) {
			return;
		}
		World world = location.getWorld();
		for (Player player : world.getPlayers()) {
			if (player.getLocation().distanceSquared(location) > 64 * 64) continue;
			sendBlockDamage(player, location, 0.0f, entityId);
		}
	}

	// Player#sendBlockDamage(Location, float, int) — the per-source crack overlay — is 1.19.4+; the compile floor is
	// 1.16.5, so it is reached reflectively and the two-argument form is the fallback.
	// ponytail: below 1.19.4 the overlay is keyed on the viewer, so a second damaged block replaces the first one's
	// cracks for that viewer; send PacketPlayOutBlockBreakAnimation through Keystone's PacketBridge if that matters.
	private static final Method SEND_BLOCK_DAMAGE_WITH_SOURCE = lookupSendBlockDamageWithSource();

	private static Method lookupSendBlockDamageWithSource() {
		try {
			return Player.class.getMethod("sendBlockDamage", Location.class, float.class, int.class);
		} catch (NoSuchMethodException absent) {
			return null;
		}
	}

	private static void sendBlockDamage(Player player, Location location, float progress, int entityId) {
		if (SEND_BLOCK_DAMAGE_WITH_SOURCE == null) {
			player.sendBlockDamage(location, progress);
			return;
		}
		try {
			SEND_BLOCK_DAMAGE_WITH_SOURCE.invoke(player, location, progress, entityId);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Player#sendBlockDamage(Location, float, int) is present but not invokable", e);
		}
	}

	/**
	 * Schedules smooth block regeneration after the regeneration delay.
	 */
	private void scheduleRegeneration(Location location, BlockDamageState state) {
		// Cancel any existing regeneration task
		state.cancelRegeneration();

		// Schedule the start of smooth regeneration
		BukkitTask delayTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
			startSmoothRegeneration(location, state);
		}, settings.getRegenerationDelayTicks());

		state.setRegenerationTask(delayTask);
	}

	/**
	 * Starts the smooth regeneration process that gradually reduces crack stage.
	 */
	private void startSmoothRegeneration(Location location, BlockDamageState state) {
		// Create a repeating task that reduces damage stage one step at a time
		BukkitTask regenTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
			// BZ-RT-06: the world unloaded mid-regeneration — abandon cleanly instead of ticking a repeating task
			// against a block that can no longer be looked up.
			if (!location.isWorldLoaded()) {
				state.cancelRegeneration();
				damagedBlocks.remove(location);
				return;
			}

			int currentStage = state.getCurrentStage();

			if (currentStage <= 0) {
				// Fully regenerated
				state.cancelRegeneration();
				damagedBlocks.remove(location);
				clearBlockDamage(location, state.getEntityId());
				return;
			}

			// Reduce stage by 1
			int newStage = currentStage - 1;
			state.setCurrentStage(newStage);
			state.setHitCount(Math.max(0, state.getHitCount() - 1));

			// Update the visual
			sendBlockDamage(location, newStage, state.getEntityId());

		}, 0L, settings.getRegenerationStepTicks());

		state.setRegenerationTask(regenTask);
	}

	/**
	 * Permanently breaks the block — used by {@link BreakMode#DESTROY}. Discards the damage state.
	 *
	 * @return true if the block was broken, false if a listener cancelled the {@link BlockBreakEvent}
	 */
	private boolean destroyBlock(Block block, Location location, @Nullable Player player) {
		if (!fireBlockBreakEvent(block, player)) {
			return false;
		}

		BlockDamageState state = damagedBlocks.remove(location);
		if (state != null) {
			state.cancelRegeneration();
			clearBlockDamage(location, state.getEntityId());
		}

		playBreakEffects(block, location);

		// Set to air (doesn't drop items)
		block.setType(Material.AIR);
		return true;
	}

	/**
	 * Breaks the block and schedules its restoration after the configured delay — used by {@link BreakMode#RESTORE}.
	 * The damage state is kept in {@link #damagedBlocks} with {@code broken = true} so a follow-up restore task can
	 * find it.
	 *
	 * @return true if the block was broken, false if a listener cancelled the {@link BlockBreakEvent}
	 */
	private boolean breakAndScheduleRestore(Block block, Location location, BlockDamageState state, int hitsRequired,
	                                        @Nullable Player player) {
		if (!fireBlockBreakEvent(block, player)) {
			return false;
		}

		state.cancelRegeneration();
		playBreakEffects(block, location);
		clearBlockDamage(location, state.getEntityId());
		block.setType(Material.AIR);
		state.setBroken(true);

		BukkitTask restoreTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
			// State was replaced or already cleaned up
			if (damagedBlocks.get(location) != state) {
				return;
			}
			// BZ-RT-06: the world unloaded while this block sat mid-restore — abandon cleanly instead of reading or
			// writing block data in (and starting a regeneration timer against) a location that's gone. Checked
			// before the getType() read below, which already touches the unloaded world.
			if (!location.isWorldLoaded()) {
				damagedBlocks.remove(location);
				return;
			}
			// Someone (player or another plugin) filled the gap — leave it alone
			if (block.getType() != Material.AIR) {
				damagedBlocks.remove(location);
				return;
			}

			block.setBlockData(state.getOriginalData());
			state.setBroken(false);
			state.setHitCount(hitsRequired);
			state.setCurrentStage(MAX_DAMAGE_STAGE);
			sendBlockDamage(location, MAX_DAMAGE_STAGE, state.getEntityId());
			startSmoothRegeneration(location, state);
		}, settings.getRestoreDelayTicks());

		state.setRegenerationTask(restoreTask);
		return true;
	}

	/**
	 * Fires a cancellable vanilla {@link BlockBreakEvent} (a {@link WeaponBlockBreakEvent}, BZ-RT-01) for a
	 * weapon-caused break, so a protection plugin can veto it the same way it vetoes a hand-mined block.
	 * {@code player} is {@code null} when no player is attributable to the hit (an NPC shooter, an explosion) —
	 * there is nothing to fire the event as, so the break proceeds unchecked, matching the pre-existing behaviour
	 * for those paths. No items are dropped by this synthetic break (Bartizan handles the removal itself), so
	 * {@link BlockBreakEvent#setDropItems} is set {@code false}.
	 *
	 * @return false if a listener cancelled the break
	 */
	private boolean fireBlockBreakEvent(Block block, @Nullable Player player) {
		if (player == null) {
			return true;
		}

		BlockBreakEvent event = new WeaponBlockBreakEvent(block, player);
		event.setDropItems(false);
		Bukkit.getPluginManager().callEvent(event);
		return !event.isCancelled();
	}

	/**
	 * Plays break sound and particle effects for a block. Captures block data before any mutation so the particle uses
	 * the original block.
	 */
	private void playBreakEffects(Block block, Location location) {
		Material  material  = block.getType();
		BlockData blockData = block.getBlockData();
		World     world     = block.getWorld();

		XSound.Record breakSound = getBlockBreakSound(material);
		breakSound.soundPlayer().atLocation(location).play();

		// Particle.BLOCK is the 1.20.5+ name of BLOCK_CRACK; XParticle resolves whichever the running server has.
		world.spawnParticle(XParticle.BLOCK.get(), location.clone().add(0.5, 0.5, 0.5), 25, 0.3, 0.3, 0.3, 0.05, blockData);
	}

	/**
	 * Gets the appropriate break sound for a block material using XSound.
	 */
	private XSound.Record getBlockBreakSound(Material material) {
		String name = material.name();

		if (name.contains("GLASS")) return XSound.BLOCK_GLASS_BREAK.record();
		if (name.contains("STONE") || name.contains("COBBLE") || name.contains("BRICK") ||
		    (name.contains("CONCRETE") && !name.contains("POWDER"))) {
			return XSound.BLOCK_STONE_BREAK.record();
		}
		if (name.contains("WOOD") || name.contains("PLANKS") || name.contains("LOG") || name.contains("FENCE") ||
		    name.contains("DOOR")) {
			return XSound.BLOCK_WOOD_BREAK.record();
		}
		if (name.contains("GRAVEL") || name.contains("SAND") || name.contains("CONCRETE_POWDER")) {
			return XSound.BLOCK_GRAVEL_BREAK.record();
		}
		if (name.contains("WOOL") || name.contains("CARPET")) {
			return XSound.BLOCK_WOOL_BREAK.record();
		}
		if (name.contains("IRON") || name.contains("GOLD") || name.contains("COPPER") || name.contains("NETHERITE") ||
		    name.contains("CHAIN") || name.contains("LANTERN")) {
			return XSound.BLOCK_METAL_BREAK.record();
		}
		if (name.contains("TERRACOTTA")) return XSound.BLOCK_STONE_BREAK.record();
		if (name.contains("ICE")) return XSound.BLOCK_GLASS_BREAK.record();
		if (name.contains("LEAVES")) return XSound.BLOCK_GRASS_BREAK.record();

		return XSound.BLOCK_STONE_BREAK.record();
	}

	/**
	 * Generates a unique entity ID for block damage animation.
	 */
	private synchronized int generateEntityId() {
		return entityIdCounter--;
	}

	/**
	 * Holds the damage state for a single block.
	 */
	@Getter
	private static class BlockDamageState {
		private final BlockData originalData;
		private final int       entityId;
		private final Material  material;

		@Setter
		private int        hitCount;
		@Setter
		private int        currentStage;
		@Setter
		private BukkitTask regenerationTask;
		@Setter
		private boolean    broken;

		public BlockDamageState(BlockData originalData, int entityId, Material material) {
			this.originalData = originalData;
			this.entityId     = entityId;
			this.material     = material;
			this.hitCount     = 0;
			this.currentStage = 0;
			this.broken       = false;
		}

		public void incrementHits() {
			hitCount++;
		}

		public void cancelRegeneration() {
			if (regenerationTask != null && !regenerationTask.isCancelled()) {
				regenerationTask.cancel();
				regenerationTask = null;
			}
		}
	}

}
