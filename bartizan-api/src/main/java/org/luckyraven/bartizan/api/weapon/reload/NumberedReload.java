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

		// start reloading the gun — the total duration (for Weapon#reloadProgress, gate HD) is known only now that
		// numberOfInsertions has been clamped to what the player actually carries. Reload.Cooldown counts timer
		// periods (one second each on the default SequenceTimer), and the trailing end pair below is one more
		// period, so the tick duration the HUD bar and the item-cooldown overlay run on covers the whole sequence.
		long totalDurationTicks = ((long) numberOfInsertions * reloadData.getCooldown() + 1) * timer.getPeriod();
		timer.addIntervalTaskPair(0, time -> {
			super.startReloading(player, totalDurationTicks);
		});

		for (int i = 0; i < numberOfInsertions; ++i) {
			timer.addIntervalTaskPair(reloadData.getCooldown(), time -> {
				if (!isReloading()) return;

				if (player != null && (player.isDead() || !CombatEligibility.resolve().canBeHit(player))) {
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
					int newSlot = findWeaponSlot(inventory, getWeapon());
					if (newSlot > -1) {
						ItemStack existingItem = inventory.getItem(newSlot);
						ItemBuilder heldWeapon = new ItemBuilder(
								Objects.requireNonNullElseGet(existingItem, () -> getWeapon().buildItem(player)));
						getWeapon().updateWeaponData(heldWeapon, player);
						getWeapon().updateWeapon(player, heldWeapon, newSlot);
					}
				}
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

}
