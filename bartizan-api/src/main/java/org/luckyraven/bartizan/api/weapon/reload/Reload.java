package org.luckyraven.bartizan.api.weapon.reload;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;
import org.luckyraven.keystone.item.ItemBuilder;
import org.luckyraven.keystone.util.ActionBarManager;
import org.luckyraven.keystone.exception.PluginException;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.api.weapon.WeaponTag;
import org.luckyraven.bartizan.api.ammo.Ammunition;
import org.luckyraven.bartizan.api.weapon.dto.AmmunitionData;
import org.luckyraven.bartizan.api.weapon.dto.ReloadData;
import org.luckyraven.bartizan.api.weapon.dto.ReloadStagesData;
import org.luckyraven.bartizan.api.event.WeaponReloadCompleteEvent;
import org.luckyraven.bartizan.api.event.WeaponReloadStageEvent;
import org.luckyraven.bartizan.api.event.WeaponReloadStartEvent;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Getter(value = AccessLevel.PROTECTED)
public abstract class Reload implements Cloneable {

	/**
	 * The ammo type actually loaded into the magazine right now. Mutable (not {@code final}) because
	 * {@code Ammunition.Types} lets this be re-resolved at the start of every reload run — see
	 * {@link #resolveAmmoType(PlayerInventory, Player, int)}. {@code null} for {@code Ammo_Type: none}.
	 */
	@Setter(AccessLevel.PROTECTED)
	private       Ammunition    ammunition;
	private       Weapon        weapon;
	private       AtomicBoolean reloading;
	private       Player        currentPlayer;
	/**
	 * Wall-clock start of the current reload and its total duration in ticks, set by {@link #startReloading(Player,
	 * long)} — read back by {@link #reloadProgress()} for the HUD (weapons-roadmap.md gate {@code HD}). Spigot has
	 * no public "current tick" accessor, so progress is derived from the wall clock, same as {@code
	 * StatusEffectService}'s tick-equivalent clock.
	 */
	private       long          reloadStartMillis;
	private       long          reloadDurationTicks;
	/**
	 * Ticks left in the current/most recent reload after a resumed offset is subtracted from {@link
	 * #reloadDurationTicks} — see {@link #remainingDurationTicks()} (weapons-roadmap.md gate {@code HO} Phase 2).
	 */
	private       long          remainingDurationTicks;
	/**
	 * 0-based index of the stage currently in progress ({@code open}/{@code insert}/{@code close}, or one
	 * {@code insert} per shell for a numbered reload), -1 before the first stage of a reload starts. Set by {@link
	 * #enterStage(int, int)} — weapons-roadmap.md gate {@code HO} Phase 1.
	 */
	private       int           currentStageIndex = -1;
	private       int           stageCount;
	/**
	 * Bookkeeping for Phase 2 resume, written by {@link #endReloading(Player, boolean)} on an interrupted stop:
	 * the stage that was in progress (not yet committed) at the moment of the interrupt, and when that happened.
	 * -1 means there is nothing to resume.
	 */
	private       int           pendingResumeStageIndex = -1;
	private       long          pendingResumeAtMillis;

	public Reload(Weapon weapon, Ammunition ammunition) {
		this.weapon     = weapon;
		this.ammunition = ammunition;
		this.reloading  = new AtomicBoolean();
	}

	public abstract void stopReloading();

	protected abstract void executeReload(JavaPlugin plugin, Player player, boolean removeAmmunition);

	public void reload(JavaPlugin plugin, Player player, boolean removeAmmunition) {
		// Double-reload guard: startReloading (called from the concrete reload types' SequenceTimer interval-0
		// task) only flips `reloading` to true one tick after this method returns, so a caller whose next tick
		// runs before that (e.g. FullAutoTask, 1-tick period) could otherwise see isReloading() == false and start
		// a second, overlapping reload. Claim the flag synchronously instead. Any executeReload abort path that
		// returns without ever reaching startReloading/endReloading must call resetReloading() to release it.
		if (!reloading.compareAndSet(false, true)) return;

		// reload the weapon action bar status
		ReloadData reloadData = weapon.getReloadData();

		if (reloadData == null) {
			reloading.set(false);
			return;
		}

		if (player != null && weapon.getReloadActionBarData() != null) {
			ActionBarManager.send(plugin, player, weapon.getReloadActionBarData().getReloading(),
			                      reloadData.getCooldown());
		}

		// start executing the reload process
		executeReload(plugin, player, removeAmmunition);
	}

	@Override
	public Reload clone() {
		try {
			Reload clone = (Reload) super.clone();

			clone.reloading = new AtomicBoolean(this.reloading.get());

			return clone;
		} catch (CloneNotSupportedException exception) {
			throw new PluginException(exception);
		}
	}

	public boolean isReloading() {
		return reloading.get();
	}

	/**
	 * @return 0.0-1.0 progress through the current reload (elapsed wall-clock time over {@code totalDurationTicks}
	 * 		from the {@link #startReloading(Player, long)} call that started it), or {@code 0.0} when not reloading.
	 */
	public double reloadProgress() {
		if (!isReloading()) return 0.0;
		// A zero-duration reload (e.g. NumberedReload with numberOfInsertions == 0) is still mid-reload for one
		// tick - report empty progress rather than flashing the HUD bar full.
		if (reloadDurationTicks <= 0) return 0.0;

		long   elapsedMillis = System.currentTimeMillis() - reloadStartMillis;
		double progress      = elapsedMillis / (reloadDurationTicks * 50.0);

		return Math.max(0.0, Math.min(1.0, progress));
	}

	/**
	 * @return the total duration (ticks) of the current/most recent reload, as passed to {@link
	 * 		#startReloading(Player, long)} — read by {@code HudService} for the boss bar denominator
	 * 		(weapons-roadmap.md gate {@code HD}).
	 */
	public long totalDurationTicks() {
		return reloadDurationTicks;
	}

	/**
	 * @return ticks left in the current/most recent reload after a Phase 2 resume's offset is subtracted from
	 * 		{@link #totalDurationTicks()} — equal to {@link #totalDurationTicks()} for a fresh (non-resumed) reload.
	 * 		Read by {@code WeaponReloadListener} for the {@code HUD.Reload_Item_Cooldown} vanilla item-cooldown
	 * 		overlay, so a resumed reload's overlay reflects only what is actually left to wait through, not the full
	 * 		duration replayed a second time (weapons-roadmap.md gate {@code HO}).
	 */
	public long remainingDurationTicks() {
		return remainingDurationTicks;
	}

	/**
	 * @return the 0-based index of the stage currently in progress ({@code open}/{@code insert}/{@code close}), or
	 * 		-1 when not reloading — backs {@code %reload_stage%} (weapons-roadmap.md gate {@code HO} Phase 1).
	 */
	public int currentStageIndex() {
		return currentStageIndex;
	}

	/**
	 * @return the total stage count of the current/most recent reload attempt — backs {@code %reload_stage_max%}.
	 */
	public int stageCount() {
		return stageCount;
	}

	/**
	 * Marks {@code index} (0-based, out of {@code count} total) as the stage now in progress, runs the {@code
	 * On_Reload_Stage} hook and fires {@link WeaponReloadStageEvent} (weapons-roadmap.md gate {@code HO} Phase 1).
	 * Skipped for the NPC path ({@link #getCurrentPlayer()} {@code == null}) — there is no {@link Player} to report
	 * and, per the roadmap, the NPC path never resumes anyway.
	 */
	protected void enterStage(int index, int count) {
		this.currentStageIndex = index;
		this.stageCount        = count;

		if (currentPlayer == null) return;

		Bukkit.getPluginManager().callEvent(new WeaponReloadStageEvent(weapon, currentPlayer, index, count));
	}

	/**
	 * Resets stage tracking to "no stage entered yet" — called at the top of every fresh {@code executeReload()}
	 * run so a {@code stopReloading()} that races ahead of the new run's first timer callback (the {@code
	 * reloading} flag is claimed synchronously by {@link #reload}, but {@code SequenceTimer} only runs its
	 * interval-0 task on a later server tick) records "nothing committed yet" instead of a stale index left over
	 * from the previous attempt.
	 */
	protected void resetStageTracking() {
		this.currentStageIndex = -1;
		this.stageCount        = 0;
	}

	/**
	 * @return {@code true} when an interrupted reload is still within {@code Reload.Stages.Resume_Window} and may
	 * 		resume from {@link #resumeStageIndex()} instead of restarting at stage 0 (weapons-roadmap.md gate
	 * 		{@code HO} Phase 2). Callers must additionally exclude the NPC path themselves — {@code inventory == null}
	 * 		never resumes.
	 */
	protected boolean canResume() {
		if (pendingResumeStageIndex < 0) return false;

		ReloadData       reloadData = weapon.getReloadData();
		ReloadStagesData stages     = reloadData != null ? reloadData.getStages() : null;

		return stages != null && stages.canResume()
				&& withinResumeWindow(pendingResumeAtMillis, stages.getResumeWindowTicks(),
				                     System.currentTimeMillis());
	}

	/**
	 * @return the stage an eligible ({@link #canResume()}) reload should resume from — the stage that was in
	 * 		progress, not yet committed, when the previous attempt was interrupted.
	 */
	protected int resumeStageIndex() {
		return pendingResumeStageIndex;
	}

	/**
	 * Consumes the pending Phase 2 resume bookkeeping. Called once at the top of every fresh {@code
	 * executeReload()} run, right after it has read {@link #canResume()}/{@link #resumeStageIndex()} into locals,
	 * so a resume is offered exactly once and a stale/expired one doesn't linger for a later attempt to trip over.
	 */
	protected void clearResume() {
		pendingResumeStageIndex = -1;
		pendingResumeAtMillis   = 0L;
	}

	/**
	 * Pure resume-window check, split out of {@link #canResume()} so a test can drive the maths with an explicit
	 * {@code now} instead of sleeping (weapons-roadmap.md gate {@code HO} Phase 2, required test).
	 */
	static boolean withinResumeWindow(long interruptedAtMillis, int resumeWindowTicks, long now) {
		if (interruptedAtMillis <= 0 || resumeWindowTicks <= 0) return false;
		return now - interruptedAtMillis <= resumeWindowTicks * 50L; // 50ms/tick
	}

	public void rebindWeapon(Weapon newWeapon) {
		this.weapon = newWeapon;
	}

	/**
	 * The ammo type actually loaded into the magazine right now — {@code null} for {@code Ammo_Type: none}. Public
	 * (unlike the class-level protected getters) so {@link Weapon#getAmmoTypeForTag()} can read it across the
	 * api.weapon/api.weapon.reload package split without exposing the rest of {@code Reload}'s internals.
	 */
	@Nullable
	public Ammunition getLoadedAmmunition() {
		return ammunition;
	}

	/**
	 * Rehydrates the loaded ammo type — called by {@link Weapon#setLoadedAmmoType} when a {@code Weapon} instance
	 * is resolved from the item's persisted {@code AMMO_TYPE} tag, so {@code Ammunition.Types} keeps returning the
	 * type actually loaded (rather than resetting to the first configured type) across a relog, drop+pickup or
	 * {@code /bartizan reload}. Public for the same cross-package reason as {@link #getLoadedAmmunition()}.
	 */
	public void setLoadedAmmunition(@Nullable Ammunition ammunition) {
		setAmmunition(ammunition);
	}

	/**
	 * @param totalDurationTicks the full duration of this reload attempt in ticks ({@code Reload.Cooldown}, or
	 *                            {@code numberOfInsertions * Reload.Cooldown} for a numbered reload) — read back by
	 *                            {@link #reloadProgress()}.
	 */
	protected void startReloading(Player player, long totalDurationTicks) {
		startReloading(player, totalDurationTicks, 0L);
	}

	/**
	 * @param totalDurationTicks  as {@link #startReloading(Player, long)}.
	 * @param elapsedTicksAlready ticks already "spent" by stages skipped on a Phase 2 resume (weapons-roadmap.md
	 *                            gate {@code HO}) — 0 for a fresh reload. Backdates {@link #reloadStartMillis} so
	 *                            {@link #reloadProgress()} (the HUD boss bar) reports overall progress including
	 *                            the resumed offset, while {@link #remainingDurationTicks()} (the item-cooldown
	 *                            overlay) reports only what is actually left to wait through.
	 */
	protected void startReloading(Player player, long totalDurationTicks, long elapsedTicksAlready) {
		// track the player for stopReloading()
		this.currentPlayer = player;

		// set that the weapon is reloading
		this.reloading.set(true);

		long clampedElapsed = Math.max(0, Math.min(elapsedTicksAlready, totalDurationTicks));

		this.reloadStartMillis      = System.currentTimeMillis() - clampedElapsed * 50L; // 50ms/tick
		this.reloadDurationTicks    = totalDurationTicks;
		this.remainingDurationTicks = totalDurationTicks - clampedElapsed;

		if (player == null) return;

		// open the reload chamber action bar status
		if (weapon.getReloadActionBarData() != null) {
			ActionBarManager.send(player, weapon.getReloadActionBarData().getOpening());
		}

		// scope the player and make them slow down
		weapon.scope(player, false);

		Bukkit.getPluginManager().callEvent(new WeaponReloadStartEvent(weapon, player));
	}

	/**
	 * Releases the {@code reloading} flag claimed by {@link #reload}'s guard, for an {@code executeReload} abort
	 * that returns before {@link #startReloading} ever ran — so the next {@link #reload} call isn't permanently
	 * blocked. Does none of {@link #endReloading}'s side effects (un-scoping, the completion event, {@code
	 * Shoot_Delay_After_Reload}) since a reload that never started shouldn't run them.
	 */
	protected void resetReloading() {
		reloading.set(false);
	}

	protected void endReloading(Player player) {
		endReloading(player, false);
	}

	/**
	 * @param interrupted {@code true} when this completion is raised by a swap-cancelled reload — see
	 *                    {@link WeaponReloadCompleteEvent#isInterrupted()}. Both {@code InstantReload} and
	 *                    {@code NumberedReload#stopReloading} call into this one shared method, so the Phase 2
	 *                    resume bookkeeping below (weapons-roadmap.md gate {@code HO}) applies to every caller —
	 *                    {@code WeaponReloadListener#onHeldSlotChange} and {@code WeaponQuitCleanupListener} alike
	 *                    — without either listener needing to know about it.
	 */
	protected void endReloading(Player player, boolean interrupted) {
		// set the weapon as not reloading
		this.reloading.set(false);
		this.currentPlayer = null;

		if (interrupted) {
			// the stage in progress (not yet committed) when the interrupt landed — see #resumeStageIndex().
			pendingResumeStageIndex = currentStageIndex;
			pendingResumeAtMillis   = System.currentTimeMillis();
		} else {
			// a completed reload leaves nothing to resume.
			pendingResumeStageIndex = -1;
			pendingResumeAtMillis   = 0L;
		}

		if (player == null) return;

		// un-scope the player to resume the showdown
		weapon.unScope(player, true);

		// Reload.Shoot_Delay_After_Reload: only on a completed (non-interrupted) reload.
		if (!interrupted) {
			ReloadData reloadData = weapon.getReloadData();
			int        delayTicks = reloadData != null ? reloadData.getShootDelayAfterReload() : 0;
			if (delayTicks > 0) {
				weapon.setShootLockedUntilMillis(System.currentTimeMillis() + delayTicks * 50L); // 50ms/tick
			}
		}

		Bukkit.getPluginManager().callEvent(new WeaponReloadCompleteEvent(weapon, player, interrupted));
	}

	/**
	 * Resolves which of the weapon's configured ammo types ({@code Ammunition.Ammo_Type}/{@code Types}) this
	 * reload run should consume: the first type, in configured order, that the player carries at least
	 * {@code amountNeeded} of. Falls back to the first configured type when the player carries none of them (so
	 * the abort/insufficient-ammo checks downstream still have a concrete item to report against), and for the
	 * NPC path ({@code inventory == null} — NPCs have unlimited supply). Returns {@code null} for {@code
	 * Ammo_Type: none}.
	 *
	 * @param player the player carrying {@code inventory}, or {@code null} for the NPC path — threaded into the
	 *               probe {@code buildItem} call so placeholder-bearing ammo names match {@code containsAtLeast}
	 *               the same way {@code WeaponService.hasAmmunition} and every consume site do.
	 */
	@Nullable
	protected Ammunition resolveAmmoType(@Nullable PlayerInventory inventory, @Nullable Player player,
	                                     int amountNeeded) {
		AmmunitionData    ammunitionData = weapon.getAmmunitionData();
		List<Ammunition>  types          = ammunitionData != null ? ammunitionData.getAmmoTypes() : List.of();

		if (types.isEmpty()) return null;
		if (inventory == null) return types.get(0);

		for (Ammunition candidate : types) {
			if (inventory.containsAtLeast(candidate.buildItem(player, 1), amountNeeded)) return candidate;
		}

		return types.get(0);
	}

	/**
	 * {@code Reload.Unload_Ammo_On_Reload}: if configured and the magazine isn't already empty, returns
	 * {@code floor(currentMag / restore)} items of the currently-loaded ammo type to the player's inventory
	 * (dropping at their feet whatever doesn't fit) and zeroes the magazine before the normal reload sequence
	 * runs. Skipped for NPCs ({@code player == null}) and {@code Ammo_Type: none} (nothing to return).
	 *
	 * @param removeAmmunition {@code false} in creative mode: the magazine is still zeroed (a reload still
	 *                         happens), but nothing is handed back since nothing was actually consumed to load it.
	 */
	protected void unloadAmmoIfConfigured(@Nullable PlayerInventory inventory, @Nullable Player player,
	                                      boolean removeAmmunition) {
		if (player == null || inventory == null) return;

		ReloadData reloadData = weapon.getReloadData();
		if (reloadData == null || !reloadData.isUnloadAmmoOnReload()) return;

		AmmunitionData ammunitionData = weapon.getAmmunitionData();
		if (ammunitionData == null || ammunitionData.getAmmoTypes().isEmpty()) return;

		int currentMag = weapon.getCurrentMagCapacity();
		if (currentMag <= 0) return;

		if (removeAmmunition) {
			int restore = ammunitionData.getRestore();
			int amount  = restore > 0 ? currentMag / restore : 0;

			if (amount > 0) {
				// Returns whatever this Reload instance currently has loaded — rehydrated from the item's
				// AMMO_TYPE tag on every weapon lookup (see WeaponService#setWeaponData / Weapon#setLoadedAmmoType)
				// so this stays accurate across a relog, drop+pickup or /bartizan reload, not just within one
				// session.
				Ammunition loaded    = ammunition != null ? ammunition : ammunitionData.getAmmoTypes().get(0);
				ItemStack  toReturn  = loaded.buildItem(player, amount);
				var        leftover = inventory.addItem(toReturn);

				for (ItemStack overflow : leftover.values()) {
					player.getWorld().dropItem(player.getLocation(), overflow);
				}
			}
		}
		// creative (removeAmmunition == false): nothing was consumed to load the magazine, so nothing is handed
		// back — just clear it below.

		weapon.setCurrentMagCapacity(0);
	}

	/**
	 * Searches for the weapon's slot in the player's inventory by UUID.
	 *
	 * @param inventory the player's inventory to search
	 *
	 * @return the slot index where the weapon is located, or -1 if not found
	 */
	protected int findWeaponSlot(PlayerInventory inventory, Weapon weapon) {
		if (weapon.getUuid() == null) return -1;
		String      weaponUUID = weapon.getUuid().toString();
		ItemStack[] contents   = inventory.getContents();

		for (int i = 0; i < contents.length; i++) {
			ItemStack item = contents[i];

			UUID itemUuid = readWeaponUUID(item);

			if (itemUuid == null) continue;

			UUID defaultWeaponUuid = UUID.fromString(weaponUUID);

			if (defaultWeaponUuid.equals(itemUuid)) {
				return i;
			}
		}

		return -1;
	}

	/**
	 * Inlined from the plugin-side {@code WeaponService.getWeaponUUID(ItemStack)} (verbatim logic) so that
	 * {@code Reload} — which lives in {@code bartizan-api} — does not need to depend on the plugin-side
	 * {@code WeaponService}. See bartizan.md B5 section 7 for the deviation note.
	 */
	private static UUID readWeaponUUID(ItemStack item) {
		if (item == null || item.getType().equals(Material.AIR) || item.getAmount() == 0) return null;

		String      tagProperName = Weapon.getTagProperName(WeaponTag.UUID);
		ItemBuilder tempItem      = new ItemBuilder(item);

		String stringTagData = tempItem.getStringTagData(tagProperName);
		String value         = String.valueOf(stringTagData);
		UUID   uuid          = null;

		if (!(value == null || value.equals("null") || value.isEmpty())) {
			uuid = UUID.fromString(value);
		}

		return uuid;
	}

}
