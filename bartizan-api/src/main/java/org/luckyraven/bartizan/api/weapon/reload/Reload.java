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
import org.luckyraven.bartizan.api.event.WeaponReloadCompleteEvent;
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
	 *                    {@link WeaponReloadCompleteEvent#isInterrupted()}.
	 */
	protected void endReloading(Player player, boolean interrupted) {
		// set the weapon as not reloading
		this.reloading.set(false);
		this.currentPlayer = null;

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
