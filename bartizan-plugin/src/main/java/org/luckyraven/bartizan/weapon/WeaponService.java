package org.luckyraven.bartizan.weapon;

import lombok.CustomLog;
import lombok.Getter;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;
import org.luckyraven.keystone.item.ItemBuilder;
import org.luckyraven.bartizan.configuration.WeaponAddon;
import org.luckyraven.bartizan.api.ammo.Ammunition;
import org.luckyraven.bartizan.api.weapon.dto.AmmunitionData;
import org.luckyraven.bartizan.api.weapon.SelectiveFire;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.api.weapon.WeaponCatalog;
import org.luckyraven.bartizan.api.weapon.WeaponTag;
import org.luckyraven.bartizan.api.weapon.WeaponType;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@CustomLog
public abstract class WeaponService implements Comparator<Weapon>, WeaponCatalog {

	private final WeaponAddon weaponAddon;

	/**
	 * Live instance per weapon item, keyed by the uuid the item carries in NBT. In-memory only: the item's own tags
	 * ({@code uuid}, {@code weapon}, {@code ammo-left}, {@code selective-fire}, durability) are the source of truth,
	 * and {@link #validateAndGetWeapon} rebuilds a missing entry from them on first use after every boot or reload.
	 */
	// ponytail: unbounded per-session cache (one entry per distinct item used since boot/reload); evict on player
	// quit if memory ever matters.
	@Getter
	private final Map<UUID, Weapon> weapons;

	public WeaponService(WeaponAddon weaponAddon) {
		this.weaponAddon = weaponAddon;
		this.weapons     = new HashMap<>();
	}

	@Nullable
	public static UUID getWeaponUUID(ItemStack item) {
		if (item == null || item.getType().equals(Material.AIR) || item.getAmount() == 0) return null;

		String      tagProperName = Weapon.getTagProperName(WeaponTag.UUID);
		ItemBuilder tempItem      = new ItemBuilder(item);

		String stringTagData = tempItem.getStringTagData(tagProperName);
		String value         = String.valueOf(stringTagData);
		UUID   uuid          = null;

		if (!(value == null || value.equals("null") || value.isEmpty())) {
			try {
				uuid = UUID.fromString(value);
			} catch (IllegalArgumentException exception) {
				// a hand-edited/corrupted tag must read as "not a weapon", not throw out of every listener
				log.warn("Ignoring weapon item " + item.getType() + " with a malformed '" + tagProperName + "' tag: " +
				         value);
			}
		}

		return uuid;
	}

	@Override
	public int compare(Weapon weapon1, Weapon weapon2) {
		return weapon1.compareTo(weapon2);
	}

	@Nullable
	public String getHeldWeaponName(ItemStack item) {
		if (item == null || item.getType().equals(Material.AIR) || item.getAmount() == 0) return null;

		return new ItemBuilder(item).getStringTagData(Weapon.getTagProperName(WeaponTag.WEAPON));
	}

	@Override
	public boolean isWeapon(ItemStack item) {
		if (item == null || item.getType().equals(Material.AIR) || item.getAmount() == 0) return false;

		// check the uuid of the weapon, and if it is available or not
		UUID weaponUuid = getWeaponUUID(item);

		if (weaponUuid == null) return false;

		// check if the uuid is in the weapons map
		if (weapons.containsKey(weaponUuid)) return true;

		// The uuid may not have been minted into the registry yet: converters, refreshers and shop deliveries build
		// transient copies so a registry entry is only created once the item is actually used. Fall back to the
		// configured catalogue so those items are still recognised as weapons.
		String weaponName = getHeldWeaponName(item);

		return weaponName != null && weaponAddon.getWeapon(weaponName) != null;
	}

	public boolean hasAmmunition(Player player, Weapon weapon) {
		AmmunitionData ammunitionData = weapon.getAmmunitionData();

		if (ammunitionData == null) return true;

		List<Ammunition> ammoTypes = ammunitionData.getAmmoTypes();
		if (ammoTypes.isEmpty()) return true; // Ammo_Type: none — infinite supply

		int consumeRate = ammunitionData.getConsumeRate();
		for (Ammunition ammoType : ammoTypes) {
			if (player.getInventory().containsAtLeast(ammoType.buildItem(player), consumeRate)) return true;
		}

		return false;
	}

	/**
	 * Starts a reload for {@code weapon} if it isn't already reloading, its magazine isn't already full, and the
	 * player carries the configured ammo (or the ammo type is {@code none} / the player is in creative mode).
	 * Shared by the sneak+drop reload trigger ({@code WeaponDroppedListener}) and {@code Reload.
	 * Auto_Reload_When_Empty} ({@code GunAction}) so both go through one path — the later HH gate exposes this on
	 * {@code BartizanApi} as {@code tryReload}.
	 *
	 * @return {@code true} if a reload was started.
	 */
	public boolean tryReload(JavaPlugin plugin, Player player, Weapon weapon) {
		if (weapon.getReloadData() == null) return false;
		if (weapon.isReloading()) return false;
		if (weapon.isMagazineFull()) return false;

		boolean haveItem = hasAmmunition(player, weapon);
		boolean creative = player.getGameMode() == GameMode.CREATIVE;

		if (!(haveItem || creative)) return false;

		weapon.reload(plugin, player, !creative);
		return true;
	}

	/**
	 * Gets the held weapon.
	 *
	 * @param player Current player.
	 *
	 * @return held weapon ItemBuilder or a null.
	 */
	@Nullable
	public ItemBuilder getHeldWeaponItem(Player player) {
		ItemStack mainHandItem = itemAccordingToSlot(player, EquipmentSlot.HAND);

		if (mainHandItem == null || mainHandItem.getType().equals(Material.AIR) || mainHandItem.getAmount() == 0)
			return null;
		if (isWeapon(mainHandItem)) return new ItemBuilder(mainHandItem);

		ItemStack offHandItem = itemAccordingToSlot(player, EquipmentSlot.OFF_HAND);

		if (offHandItem == null || offHandItem.getType().equals(Material.AIR) || offHandItem.getAmount() == 0)
			return null;

		return isWeapon(offHandItem) ? new ItemBuilder(offHandItem) : null;
	}

	/**
	 * Pushes an in-memory weapon mutation (ammo, tags, ...) back onto the player's held item, exactly like
	 * {@code GunAction}/{@code IncendiaryAction} do after consuming ammo. A no-op if the player is no longer
	 * holding the weapon (already swapped away, item gone).
	 *
	 * @param weapon weapon whose in-memory state (e.g. {@link Weapon#getCurrentMagCapacity()}) should be persisted.
	 * @param player player currently holding it.
	 */
	public void persistHeldWeapon(Weapon weapon, Player player) {
		ItemBuilder heldWeapon = getHeldWeaponItem(player);
		if (heldWeapon == null) return;

		weapon.updateWeaponData(heldWeapon, player);

		// getHeldWeaponItem checks the main hand first, only falling back to the off hand when the main hand
		// isn't the weapon (gate HJ review finding 4) - write back to whichever hand it actually came from,
		// not unconditionally the main-hand hotbar slot.
		if (isWeapon(player.getInventory().getItemInMainHand())) {
			weapon.updateWeapon(player, heldWeapon, player.getInventory().getHeldItemSlot());
		} else {
			player.getInventory().setItemInOffHand(heldWeapon.build());
		}
	}

	/**
	 * The shared, read-only catalogue entry for {@code type} exactly as it was parsed from its YAML file.
	 * <p/>
	 * Unlike {@link #getWeapon(String)} this never mints a uuid and never registers anything, so it is the correct
	 * lookup for every read-only caller (display names, death messages, sign validation). The returned instance is
	 * shared — never hand it to a player and never mutate it; use {@link #createTransientWeapon(String)} for that.
	 *
	 * @param type weapon file name.
	 *
	 * @return the catalogue template, or {@code null} when no weapon file carries that name.
	 */
	@Override
	@Nullable
	public Weapon getWeaponTemplate(@Nullable String type) {
		if (type == null || type.isEmpty()) return null;

		return weaponAddon.getWeapon(type);
	}

	/**
	 * Every catalogue template, for callers that need to scan the configured weapons by name. Previously these callers
	 * scanned {@link #getWeapons()}, which only ever held the instances minted so far.
	 */
	@Override
	public Collection<Weapon> getWeaponTemplates() {
		return Collections.unmodifiableCollection(weaponAddon.getWeapons());
	}

	/**
	 * A fresh, unregistered copy of the {@code type} template carrying a valid uuid.
	 * <p/>
	 * Used by item converters, refreshers and anything else that only needs an {@code ItemStack}: the instance stays
	 * out of {@link #getWeapons()} until the item is really picked up, at which point
	 * {@link #validateAndGetWeapon(Player, ItemStack)} registers it under the uuid the item carries.
	 *
	 * @param type weapon file name.
	 *
	 * @return an unregistered copy, or {@code null} when no weapon file carries that name.
	 */
	@Override
	@Nullable
	public Weapon createTransientWeapon(@Nullable String type) {
		Weapon template = getWeaponTemplate(type);

		if (template == null) return null;

		return template.copyWithUUID(mintUuid(template, type, null));
	}

	@Nullable
	public Weapon getWeapon(@Nullable String type) {
		return getWeapon(null, null, type);
	}

	@Nullable
	public Weapon getWeapon(Player player, @Nullable String type) {
		return getWeapon(player, null, type);
	}

	@Nullable
	public Weapon getWeapon(Player player, UUID uuid, @Nullable String type) {
		return getWeapon(player, uuid, type, false);
	}

	/**
	 * Resolves the live instance for {@code uuid}, minting and registering one from the {@code type} template when
	 * the registry has none yet. Pure in-memory work — a template clone plus a map put — so it is safe on the shot
	 * path: an item's first use after a boot or reload costs microseconds, not a round trip anywhere.
	 *
	 * @param player the player holding the item, or {@code null} for a fresh give.
	 * @param uuid the uuid the item carries, or {@code null} to mint a new one.
	 * @param type weapon file name; may be {@code null} only when {@code uuid} is already registered.
	 * @param newInstance {@code true} to skip syncing runtime state from the player's held item.
	 *
	 * @return the registered instance, or {@code null} when {@code uuid} is unknown and {@code type} is null or not
	 * 		a configured weapon.
	 */
	@Nullable
	public Weapon getWeapon(@Nullable Player player, UUID uuid, @Nullable String type, boolean newInstance) {
		// already registered
		if (uuid != null) {
			Weapon existing = weapons.get(uuid);
			if (existing != null) {
				if (player != null && !newInstance) setWeaponData(existing, player);
				return existing;
			}
		}

		// type shouldn't be null
		if (type == null || type.isEmpty()) return null;

		// the type is basically the name of the weapon in the files
		Weapon weaponAddon = this.weaponAddon.getWeapon(type);

		if (weaponAddon == null) return null;

		UUID finalUuid = mintUuid(weaponAddon, type, uuid);

		// first use of this item since boot/reload (or a brand-new give): clone the template under the item's uuid
		Weapon finalWeapon = weaponAddon.copyWithUUID(finalUuid);

		// Register the weapon first so isWeapon() can find it when setWeaponData
		// calls getHeldWeaponItem — otherwise the map lookup returns null and the
		// NBT ammo/durability data is never applied (weapon stays at max capacity).
		weapons.put(finalUuid, finalWeapon);

		// check if the weapon is new or not
		// if it was new, then no need to set the data of the uuid since it is not even created/built
		if (player != null && !newInstance) setWeaponData(finalWeapon, player);

		return finalWeapon;
	}

	/**
	 * The weapon {@code player} is actually holding: main hand, falling back to the off hand when the main hand
	 * isn't a weapon. Shared by {@code BartizanApiImpl#getHeldWeapon} and any death/quit cleanup that must
	 * un-scope/stop-reload whatever weapon the player was holding, not just the main hand.
	 */
	@Nullable
	public Weapon getHeldWeapon(Player player) {
		Weapon mainHand = validateAndGetWeapon(player, player.getInventory().getItemInMainHand());
		if (mainHand != null) return mainHand;

		return validateAndGetWeapon(player, player.getInventory().getItemInOffHand());
	}

	@Override
	@Nullable
	public Weapon validateAndGetWeapon(Player player, ItemStack heldItem) {
		if (heldItem == null || heldItem.getType().equals(Material.AIR) || heldItem.getAmount() == 0) return null;

		String weaponName = getHeldWeaponName(heldItem);
		if (weaponName == null) return null;

		// get the weapon information
		UUID uuid = getWeaponUUID(heldItem);
		if (uuid == null) return null;

		// newInstance=true skips the internal player-hand re-fetch inside getWeapon.
		// We sync directly from heldItem so dropped items, off-hand items, and
		// first-login cases all read the correct NBT ammo/durability values.
		Weapon weapon = getWeapon(player, uuid, weaponName, true);
		if (weapon == null) return null;

		setWeaponData(weapon, new ItemBuilder(heldItem));

		return weapon;
	}

	public void clear() {
		weapons.clear();
	}

	private ItemStack itemAccordingToSlot(Player player, EquipmentSlot equipmentSlot) {
		return player.getInventory().getItem(equipmentSlot);
	}

	/**
	 * Derives the uuid a fresh copy of {@code template} should carry. Throwables share one deterministic uuid per type
	 * so identical items stack in the inventory; everything else keeps the caller's uuid when there is one, otherwise
	 * gets a random uuid that does not collide with an already registered instance.
	 */
	private UUID mintUuid(Weapon template, @Nullable String type, @Nullable UUID uuid) {
		if (template.getCategory() == WeaponType.THROWABLE) {
			return UUID.nameUUIDFromBytes(("throwable:" + type).getBytes(StandardCharsets.UTF_8));
		}

		UUID finalUuid = (uuid != null) ? uuid : UUID.randomUUID();
		while (weapons.containsKey(finalUuid)) finalUuid = UUID.randomUUID();

		return finalUuid;
	}

	private void setWeaponData(Weapon weapon, Player player) {
		// need to collect data and save their values
		ItemBuilder itemBuilder = getHeldWeaponItem(player);

		setWeaponData(weapon, itemBuilder);
	}

	private void setWeaponData(Weapon weapon, @Nullable ItemBuilder itemBuilder) {
		if (itemBuilder == null) return;

		// get the ammo left
		int amountLeft = itemBuilder.getIntegerTagData(Weapon.getTagProperName(WeaponTag.AMMO_LEFT));
		// get the ammo type actually loaded (Ammunition.Types) so it survives this Weapon instance being rebuilt
		// from the catalogue template — see Weapon#setLoadedAmmoType.
		String ammoType = itemBuilder.getStringTagData(Weapon.getTagProperName(WeaponTag.AMMO_TYPE));
		// get the selective fire
		SelectiveFire selectiveFire = SelectiveFire.getType(
				itemBuilder.getStringTagData(Weapon.getTagProperName(WeaponTag.SELECTIVE_FIRE)));
		// get the selected Skins.Named skin, if any
		String skinName = itemBuilder.getStringTagData(Weapon.getTagProperName(WeaponTag.SKIN));
		// set weapon durability
		short durability = weapon.getDurabilityCalculator().calculateWeaponDurabilityFromItem(itemBuilder);

		weapon.setCurrentDurability(durability);
		weapon.setCurrentMagCapacity(amountLeft);
		weapon.setCurrentSelectiveFire(selectiveFire);
		weapon.setLoadedAmmoType(ammoType);
		// an unrecognised/removed skin name is treated as none, not left stale on the in-memory instance
		if (!weapon.setSelectedSkin(skinName)) weapon.setSelectedSkin(null);
	}

}
