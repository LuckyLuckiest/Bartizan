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
	/**
	 * Wall-clock start of the current reload and its total duration in ticks, set by {@link #startReloading(Player,
	 * long)} — read back by {@link #reloadProgress()} for the HUD (weapons-roadmap.md gate {@code HD}). Spigot has
	 * no public "current tick" accessor, so progress is derived from the wall clock, same as {@code
	 * StatusEffectService}'s tick-equivalent clock.
	 */
	private       long          reloadStartMillis;
	private       long          reloadDurationTicks;

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

	/**
	 * @return 0.0-1.0 progress through the current reload (elapsed wall-clock time over {@code totalDurationTicks}
	 * 		from the {@link #startReloading(Player, long)} call that started it), or {@code 0.0} when not reloading.
	 */
	public double reloadProgress() {
		if (!isReloading()) return 0.0;
		if (reloadDurationTicks <= 0) return 1.0;

		long   elapsedMillis = System.currentTimeMillis() - reloadStartMillis;
		double progress      = elapsedMillis / (reloadDurationTicks * 50.0);

		return Math.max(0.0, Math.min(1.0, progress));
	}

	/**
	 * @return the total duration (ticks) of the current/most recent reload, as passed to {@link
	 * 		#startReloading(Player, long)} — read by {@code WeaponReloadListener} for the {@code HUD.Reload_Item_Cooldown}
	 * 		vanilla item-cooldown overlay (weapons-roadmap.md gate {@code HD}).
	 */
	public long totalDurationTicks() {
		return reloadDurationTicks;
	}

	public void rebindWeapon(Weapon newWeapon) {
		this.weapon = newWeapon;
	}

	/**
	 * @param totalDurationTicks the full duration of this reload attempt in ticks ({@code Reload.Cooldown}, or
	 *                            {@code numberOfInsertions * Reload.Cooldown} for a numbered reload) — read back by
	 *                            {@link #reloadProgress()}.
	 */
	protected void startReloading(Player player, long totalDurationTicks) {
		// track the player for stopReloading()
		this.currentPlayer = player;

		// set that the weapon is reloading
		this.reloading.set(true);

		this.reloadStartMillis   = System.currentTimeMillis();
		this.reloadDurationTicks = totalDurationTicks;

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
		endReloading(player, false);
	}

	/**
	 * @param interrupted {@code true} when this completion is raised by a swap-cancelled reload — see
	 *                    {@link WeaponReloadCompleteEvent#isInterrupted()}.
	 */
	protected void endReloading(Player player, boolean interrupted) {
		// set the weapon as not reloading
		this.reloading.set(false);
		this.currentPlayer = null;

		if (player == null) return;

		// un-scope the player to resume the showdown
		weapon.unScope(player, true);

		Bukkit.getPluginManager().callEvent(new WeaponReloadCompleteEvent(weapon, player, interrupted));
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
