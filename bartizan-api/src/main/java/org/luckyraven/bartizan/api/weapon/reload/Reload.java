package org.luckyraven.bartizan.api.weapon.reload;

import lombok.AccessLevel;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.luckyraven.keystone.item.ItemBuilder;
import org.luckyraven.keystone.util.ActionBarManager;
import org.luckyraven.keystone.exception.PluginException;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.api.weapon.WeaponTag;
import org.luckyraven.bartizan.api.ammo.Ammunition;
import org.luckyraven.bartizan.api.weapon.dto.ReloadData;
import org.luckyraven.bartizan.api.event.WeaponReloadCompleteEvent;
import org.luckyraven.bartizan.api.event.WeaponReloadStartEvent;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Getter(value = AccessLevel.PROTECTED)
public abstract class Reload implements Cloneable {

	private final Ammunition    ammunition;
	private       Weapon        weapon;
	private       AtomicBoolean reloading;
	private       Player        currentPlayer;

	public Reload(Weapon weapon, Ammunition ammunition) {
		this.weapon     = weapon;
		this.ammunition = ammunition;
		this.reloading  = new AtomicBoolean();
	}

	public abstract void stopReloading();

	protected abstract void executeReload(JavaPlugin plugin, Player player, boolean removeAmmunition);

	public void reload(JavaPlugin plugin, Player player, boolean removeAmmunition) {
		// reload the weapon action bar status
		ReloadData reloadData = weapon.getReloadData();

		if (reloadData == null) return;

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

	public void rebindWeapon(Weapon newWeapon) {
		this.weapon = newWeapon;
	}

	protected void startReloading(Player player) {
		// track the player for stopReloading()
		this.currentPlayer = player;

		// set that the weapon is reloading
		this.reloading.set(true);

		if (player == null) return;

		// open the reload chamber action bar status
		if (weapon.getReloadActionBarData() != null) {
			ActionBarManager.send(player, weapon.getReloadActionBarData().getOpening());
		}

		// scope the player and make them slow down
		weapon.scope(player, false);

		Bukkit.getPluginManager().callEvent(new WeaponReloadStartEvent(weapon, player));
	}

	protected void endReloading(Player player) {
		// set the weapon as not reloading
		this.reloading.set(false);
		this.currentPlayer = null;

		if (player == null) return;

		// un-scope the player to resume the showdown
		weapon.unScope(player, true);

		Bukkit.getPluginManager().callEvent(new WeaponReloadCompleteEvent(weapon, player));
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
