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

		timer = new SequenceTimer(plugin);

		ReloadData reloadData = getWeapon().getReloadData();
		if (reloadData == null) return;

		// start reloading the gun
		timer.addIntervalTaskPair(0, time -> {
			super.startReloading(player);
		});

		// the sound that plays at the middle
		long midSound = reloadData.getCooldown() / 2;
		timer.addIntervalTaskPair(midSound, time -> {
			if (player != null && (player.isDead() || !CombatEligibility.resolve().canBeHit(player))) {
				stopReloading();
				return;
			}

			if (player != null) {
				// Per-shell mid sound intentionally stays direct here — no On_Reload_Mid hook in v1.
				SoundEffect.playSounds(player, getWeapon().getSoundData().getReloadCustomMid(), null);
			}
		});

		long remaining = Math.max(0, reloadData.getCooldown() - midSound);
		// continue execution after the sound had finished
		timer.addIntervalTaskPair(remaining, time -> {
			if (player != null && (player.isDead() || !CombatEligibility.resolve().canBeHit(player))) {
				stopReloading();
				return;
			}

			AmmunitionData ammunitionData = getWeapon().getAmmunitionData();
			if (ammunitionData == null) return;

			if (inventory != null) {
				// if ammo was lost before or during the reload start (e.g. dropped), abort immediately
				boolean contains = inventory.containsAtLeast(getAmmunition().buildItem(player, 1),
				                                             ammunitionData.getConsumeRate());
				if (removeAmmunition && !contains) {
					stopReloading();
					return;
				}

				// remove the magazine the moment the reloading starts to prevent bugs
				if (removeAmmunition) {
					inventory.removeItem(getAmmunition().buildItem(player, ammunitionData.getConsumeRate()));
				}
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

			// end reloading the gun
			super.endReloading(player);
		});

		timer.start(false);
	}

}
