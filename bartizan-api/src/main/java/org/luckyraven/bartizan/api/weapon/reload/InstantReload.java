package org.luckyraven.bartizan.api.weapon.reload;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.luckyraven.keystone.item.ItemBuilder;
import org.luckyraven.keystone.sound.SoundEffect;
import org.luckyraven.bartizan.api.combat.CombatEligibility;
import org.luckyraven.keystone.timer.SequenceTimer;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.api.ammo.Ammunition;
import org.luckyraven.bartizan.api.weapon.dto.AmmunitionData;
import org.luckyraven.bartizan.api.weapon.dto.ReloadData;
import org.luckyraven.bartizan.api.weapon.dto.ReloadStagesData;

import java.util.Objects;

public class InstantReload extends Reload {

	private SequenceTimer timer;

	public InstantReload(Weapon weapon, Ammunition ammunition) {
		super(weapon, ammunition);
	}

	@Override
	public void stopReloading() {
		if (timer == null || timer.isCancelled()) return;

		if (isReloading()) {
			super.endReloading(getCurrentPlayer(), true);
		}

		timer.stop();
		timer = null;
	}

	@Override
	public String toString() {
		return String.format("InstantReload{isReloading=%s, timer=%s}", isReloading(), timer);
	}

	@Override
	protected void executeReload(JavaPlugin plugin, Player player, boolean removeAmmunition) {
		PlayerInventory inventory = player != null ? player.getInventory() : null;

		ReloadData reloadData = getWeapon().getReloadData();
		if (reloadData == null) {
			resetReloading();
			return;
		}

		AmmunitionData ammunitionData = getWeapon().getAmmunitionData();
		if (ammunitionData == null) {
			resetReloading();
			return;
		}

		unloadAmmoIfConfigured(inventory, player, removeAmmunition);
		setAmmunition(resolveAmmoType(inventory, player, ammunitionData.getConsumeRate()));
		resetStageTracking();

		// weapons-roadmap.md gate HO: three stages — open (start to the mid sound), insert (mid sound to the
		// consume — the commit point), close (the final wait before the reload completes). Shares default to
		// 0.25/0.6/0.15 (Reload.Stages.Open/Insert/Close.Share) when the file has no Stages: section.
		ReloadStagesData stages      = reloadData.getStages();
		long             cooldown    = reloadData.getCooldown();
		long             openTicks   = stages.openTicks(cooldown);
		long             insertTicks = stages.insertTicks(cooldown);
		long             closeTicks  = stages.closeTicks(cooldown);

		timer = new SequenceTimer(plugin);

		// Phase 2 resume — never for the NPC path (inventory == null). A stale/expired pending resume is consumed
		// (cleared) regardless, so it can't be mistakenly offered to a later attempt.
		boolean resuming      = inventory != null && canResume();
		int     resumedStage  = resuming ? resumeStageIndex() : 0;
		clearResume();

		// weapons-roadmap.md gate HO review fix 2: the close stage (index 2) has no commit point of its own -
		// resuming straight into it would schedule only the close wait, and Unload_Ammo_On_Reload: true already
		// zeroed the magazine above (unloadAmmoIfConfigured) expecting the insert stage to re-fill it. A resume
		// recorded at/past close instead restarts the whole open/insert/close sequence from stage 0 - NOT stage 1,
		// which would skip open but still redo insert's commit, exactly as risky as a full restart with none of
		// its consistency.
		final int startStage = resumedStage >= 2 ? 0 : resumedStage;

		long period              = timer.getPeriod();
		long totalDurationTicks  = cooldown * period;
		long elapsedTicksAlready = elapsedTicksFor(startStage, openTicks, insertTicks) * period;

		// start reloading the gun. Reload.Cooldown counts timer periods (one second each on the default
		// SequenceTimer), so the tick duration the HUD bar and the item-cooldown overlay run on is cooldown * period.
		timer.addIntervalTaskPair(0, time -> {
			super.startReloading(player, totalDurationTicks, elapsedTicksAlready);
			enterStage(startStage, 3);
		});

		if (startStage <= 0) {
			// open -> insert: the mid sound, at Reload.Stages.Open.Share of the way through Cooldown.
			timer.addIntervalTaskPair(openTicks, time -> {
				// weapons-roadmap.md gate HO review fix 1: SequenceTimer#run() keeps cascading through queued
				// interval-0 pairs within the same tick, and Timer#stop() only cancels the Bukkit task - it does
				// not break out of a run() already in progress. A prior stage's abort already ended this reload;
				// bail out instead of running this stage as if it were still current.
				if (!isReloading()) return;

				if (player != null && (player.isDead() || !CombatEligibility.resolve().canBeHit(player))) {
					stopReloading();
					return;
				}

				if (player != null) {
					// Per-shell mid sound intentionally stays direct here — no On_Reload_Mid hook in v1.
					SoundEffect.playSounds(player, getWeapon().getSoundData().getReloadCustomMid(), null);
				}

				enterStage(1, 3);
			});
		}

		if (startStage <= 1) {
			// insert -> close: the commit point — the magazine item is consumed here, exactly as before gate HO.
			timer.addIntervalTaskPair(insertTicks, time -> {
				// see the open-stage guard above (gate HO review fix 1).
				if (!isReloading()) return;

				if (player != null && (player.isDead() || !CombatEligibility.resolve().canBeHit(player))) {
					stopReloading();
					return;
				}

				// the weapon must still be a top-level inventory item (hotbar, storage, armour or off hand): on the
				// cursor, in the crafting grid, an ender chest or a bundle the fill could never be written back and
				// the consumed ammo would be lost - interrupt before the commit consumes anything (BZ-WM-15)
				int newSlot = inventory != null ? findWeaponSlot(inventory, getWeapon()) : -1;
				if (inventory != null && newSlot < 0) {
					stopReloading();
					return;
				}

				Ammunition ammoToConsume = getAmmunition();

				if (inventory != null && ammoToConsume != null) {
					// if ammo was lost before or during the reload start (e.g. dropped), abort immediately
					boolean contains = inventory.containsAtLeast(ammoToConsume.buildItem(player, 1),
					                                             ammunitionData.getConsumeRate());
					if (removeAmmunition && !contains) {
						stopReloading();
						return;
					}

					// remove the magazine the moment it's consumed to prevent bugs
					if (removeAmmunition) {
						inventory.removeItem(ammoToConsume.buildItem(player, ammunitionData.getConsumeRate()));
					}
				}
				// Ammo_Type: none (ammoToConsume == null) — infinite supply, nothing to check/remove.

				// add to the weapon capacity
				getWeapon().addAmmunition(ammunitionData.getRestore());

				if (inventory != null) {
					// update the weapon data in the player's inventory
					if (newSlot > -1) {
						ItemStack existingItem = inventory.getItem(newSlot);
						ItemBuilder heldWeapon = new ItemBuilder(
								Objects.requireNonNullElseGet(existingItem, () -> getWeapon().buildItem(player)));

						getWeapon().updateWeaponData(heldWeapon, player);
						getWeapon().updateWeapon(player, heldWeapon, newSlot);
					}
				}

				enterStage(2, 3);
			});
		}

		// close -> end: nothing left to commit, just the final wait before the reload completes. Ammo is already
		// consumed by this point (or was never reached), so an interrupt here can only keep the rounds, never
		// refund or duplicate them.
		timer.addIntervalTaskPair(closeTicks, time -> {
			// see the open-stage guard above (gate HO review fix 1) - this is the callback the insert-stage abort
			// would otherwise cascade into within the same tick, completing a reload that was just interrupted.
			if (!isReloading()) return;

			if (player != null && (player.isDead() || !CombatEligibility.resolve().canBeHit(player))) {
				stopReloading();
				return;
			}

			// end reloading the gun
			super.endReloading(player);
		});

		timer.start(false);
	}

	/**
	 * @return ticks already elapsed by stages skipped on a Phase 2 resume (weapons-roadmap.md gate {@code HO}) —
	 * 		0 for a fresh reload ({@code startStage == 0}).
	 */
	private static long elapsedTicksFor(int startStage, long openTicks, long insertTicks) {
		long elapsed = 0;
		if (startStage >= 1) elapsed += openTicks;
		if (startStage >= 2) elapsed += insertTicks;
		return elapsed;
	}

}
