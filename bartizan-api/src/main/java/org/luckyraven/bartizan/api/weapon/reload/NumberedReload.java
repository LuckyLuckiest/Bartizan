package org.luckyraven.bartizan.api.weapon.reload;

import org.bukkit.Material;
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
import org.luckyraven.bartizan.api.weapon.reload.Reload;

import java.util.Objects;

public class NumberedReload extends Reload {

	private final int amount;

	private SequenceTimer timer;

	public NumberedReload(Weapon weapon, Ammunition ammunition, int amount) {
		super(weapon, ammunition);

		this.amount = amount;
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
		return String.format("NumberedReload{isReloading=%s, amount=%d, timer=%s}", isReloading(), amount, timer);
	}

	@Override
	protected void executeReload(JavaPlugin plugin, Player player, boolean removeAmmunition) {
		PlayerInventory inventory = player != null ? player.getInventory() : null;

		AmmunitionData ammunitionData = getWeapon().getAmmunitionData();
		if (ammunitionData == null) {
			resetReloading();
			return;
		}

		unloadAmmoIfConfigured(inventory, player, removeAmmunition);
		setAmmunition(resolveAmmoType(inventory, player, amount));
		resetStageTracking();

		timer = new SequenceTimer(plugin);

		// calculate the number of inserts according to the mag capacity
		int leftToInsert       = ammunitionData.getMaxMagCapacity() - getWeapon().getCurrentMagCapacity();
		int numberOfInsertions = leftToInsert / ammunitionData.getRestore();

		Ammunition ammoToConsume = getAmmunition();

		if (inventory != null && ammoToConsume != null) {
			// limit insertions by how much ammo the player actually carries
			int numberOfAmmunition = 0;
			for (int i = 0; i < inventory.getSize(); i++) {
				ItemStack item = inventory.getItem(i);
				if (item == null || item.getType() == Material.AIR || !Ammunition.isAmmunition(item)) continue;
				if (item.equals(ammoToConsume.buildItem(item.getAmount()))) {
					numberOfAmmunition += item.getAmount();
				}
			}
			int maxPossibleInsertions = numberOfAmmunition / amount;
			numberOfInsertions = Math.min(numberOfInsertions, maxPossibleInsertions);
		}
		// NPC path (inventory == null) or Ammo_Type: none (ammoToConsume == null) — unlimited ammo supply either way.

		ReloadData reloadData = getWeapon().getReloadData();
		if (reloadData == null) {
			resetReloading();
			return;
		}

		// weapons-roadmap.md gate HO Phase 2 resume — never for the NPC path (inventory == null). Stages here are
		// derived from the timer, not from Reload.Stages.*.Share (that config only shapes an instant reload): open,
		// one insert per shell (numberOfInsertions of them, already clamped to what the player carries), close.
		// A resumed run's own stage numbering is local to itself (1..numberOfInsertions, not the original shell
		// count) — the magazine already keeps every previously-committed shell regardless.
		boolean resuming            = inventory != null && canResume();
		int     previouslyCommitted = resuming ? previouslyCommittedFor(resumeStageIndex()) : 0;
		clearResume();

		int  stageCount = stageCountFor(numberOfInsertions);
		long period     = timer.getPeriod();

		// start reloading the gun — the total duration (for Weapon#reloadProgress, gate HD) is known only now that
		// numberOfInsertions has been clamped to what the player actually carries. Reload.Cooldown counts timer
		// periods (one second each on the default SequenceTimer), and the trailing end pair below is one more
		// period, so the tick duration the HUD bar and the item-cooldown overlay run on covers the whole sequence.
		long elapsedTicksAlready = (long) previouslyCommitted * reloadData.getCooldown() * period;
		long totalDurationTicks  = elapsedTicksAlready
		                           + ((long) numberOfInsertions * reloadData.getCooldown() + 1) * period;

		final boolean fResuming = resuming;
		timer.addIntervalTaskPair(0, time -> {
			super.startReloading(player, totalDurationTicks, elapsedTicksAlready);
			// open has zero width of its own (there is no extra delay before the first insertion beyond the
			// standard per-shell wait below) — a fresh run still reports it once before moving straight to the
			// first insert; a resumed run skips it and continues with the next shell. Stage 1 itself is entered
			// by the first insert callback below, at its own start, not here — entering both back to back in this
			// same interval-0 tick fired ON_RELOAD_STAGE twice at once and reported a zero-insertion reload as
			// already at its final stage (weapons-roadmap.md gate HO review fix 4).
			if (!fResuming) enterStage(0, stageCount);
		});

		for (int i = 0; i < numberOfInsertions; ++i) {
			final int stageIndex = i + 1;
			timer.addIntervalTaskPair(reloadData.getCooldown(), time -> {
				if (!isReloading()) return;

				// the first insertion enters its own stage here, at its own callback's start, instead of the
				// interval-0 anchor above; every later insertion's stage was already entered by the previous
				// insertion's commit below (gate HO review fix 4).
				if (stageIndex == 1) enterStage(1, stageCount);

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

				Ammunition ammoForThisInsertion = getAmmunition();

				if (inventory != null && ammoForThisInsertion != null) {
					// if ammo was dropped mid-reload, abort the remaining insertions
					boolean contains = inventory.containsAtLeast(ammoForThisInsertion.buildItem(player, 1), amount);
					if (removeAmmunition && !contains) {
						stopReloading();
						return;
					}
				}

				// reload middle sound — intentionally stays direct here, no On_Reload_Mid hook in v1
				if (player != null) {
					SoundEffect.playSounds(player, getWeapon().getSoundData().getReloadCustomMid(), null);
				}

				if (inventory != null && removeAmmunition && ammoForThisInsertion != null) {
					inventory.removeItem(ammoForThisInsertion.buildItem(player, amount));
				}

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

				// this shell just committed — move on to the next one (or close, once it was the last).
				enterStage(stageIndex + 1, stageCount);
			});
		}

		// end reloading the gun
		timer.addIntervalTaskPair(1, time -> {
			if (player != null && (player.isDead() || !CombatEligibility.resolve().canBeHit(player))) {
				stopReloading();
				return;
			}

			super.endReloading(player);
		});

		timer.start(false);
	}

	/**
	 * @return the total stage count of a numbered reload run of {@code numberOfInsertions} shells: {@code open} +
	 * 		one {@code insert} per shell + {@code close}.
	 */
	static int stageCountFor(int numberOfInsertions) {
		return numberOfInsertions + 2;
	}

	/**
	 * @return how many shells had already committed when the interrupted run's {@code resumeStageIndex} was
	 * 		recorded — {@code resumeStageIndex} counts "open" as stage 0 and the shell currently in progress
	 * 		(waiting, not yet committed) as its own stage, so every stage before it is a committed shell.
	 */
	static int previouslyCommittedFor(int resumeStageIndex) {
		return Math.max(0, resumeStageIndex - 1);
	}

}
