package org.luckyraven.bartizan.importer.wm;

import lombok.CustomLog;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.Nullable;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.file.BartizanSettings;
import org.luckyraven.bartizan.weapon.WeaponManager;
import org.luckyraven.keystone.bean.listener.ListenerHandler;
import org.luckyraven.keystone.item.ItemBuilder;

/**
 * Live WeaponMechanics item conversion (weapons-roadmap.md gate {@code HM}, §6.4): whenever a player's held/
 * clicked/carried item carries WM's own {@code weaponmechanics:weapon-title} PDC tag and an imported Bartizan
 * weapon of the same (sanitised) title exists, rebuilds the stack through {@code WeaponManager.getWeapon} and
 * carries {@code weaponmechanics:ammo-left} over via {@link Weapon#setCurrentMagCapacity}/{@link
 * Weapon#updateWeaponData(ItemBuilder)} - the same display-name/tag sync path {@code WeaponService#setWeaponData}
 * uses elsewhere, so the rebuilt item's tooltip and NBT agree from the moment it's handed back.
 * Reads WM's tags with plain Bukkit {@link PersistentDataContainer} - no WM dependency, and this runs even when
 * WM itself is no longer installed (the tags simply outlive the plugin that wrote them).
 * <p>
 * Gated on {@link BartizanSettings#isConvertWeaponMechanicsItems()} so a server that never imported WM pays
 * nothing for this (every event handler below returns immediately when it's off).
 */
@CustomLog
@ListenerHandler
public class WmItemConverterListener implements Listener {

	private static final NamespacedKey WEAPON_TITLE = new NamespacedKey("weaponmechanics", "weapon-title");
	private static final NamespacedKey AMMO_LEFT     = new NamespacedKey("weaponmechanics", "ammo-left");

	private final WeaponManager weaponManager;

	public WmItemConverterListener(WeaponManager weaponManager) {
		this.weaponManager = weaponManager;
	}

	@EventHandler
	public void onItemHeld(PlayerItemHeldEvent event) {
		if (!BartizanSettings.isConvertWeaponMechanicsItems()) return;

		Player          player    = event.getPlayer();
		PlayerInventory inventory = player.getInventory();
		convertSlot(inventory, event.getNewSlot());
	}

	@EventHandler
	public void onInventoryClick(InventoryClickEvent event) {
		if (!BartizanSettings.isConvertWeaponMechanicsItems()) return;

		ItemStack converted = convert(event.getCurrentItem());
		if (converted != null) event.setCurrentItem(converted);

		ItemStack cursorConverted = convert(event.getCursor());
		if (cursorConverted != null) event.setCursor(cursorConverted);
	}

	@EventHandler
	public void onPlayerJoin(PlayerJoinEvent event) {
		if (!BartizanSettings.isConvertWeaponMechanicsItems()) return;

		PlayerInventory inventory = event.getPlayer().getInventory();
		for (int slot = 0; slot < inventory.getSize(); slot++) {
			convertSlot(inventory, slot);
		}
	}

	private void convertSlot(PlayerInventory inventory, int slot) {
		if (slot < 0 || slot >= inventory.getSize()) return;

		ItemStack converted = convert(inventory.getItem(slot));
		if (converted != null) inventory.setItem(slot, converted);
	}

	/**
	 * @return a freshly built Bartizan item carrying {@code item}'s {@code ammo-left} over, or {@code null} when
	 * 		{@code item} isn't a WM weapon, carries a title with no matching imported weapon, or is already a
	 * 		Bartizan item (nothing to convert).
	 */
	@Nullable
	private ItemStack convert(@Nullable ItemStack item) {
		if (item == null || !item.hasItemMeta()) return null;

		ItemMeta meta = item.getItemMeta();
		if (meta == null) return null;

		PersistentDataContainer pdc = meta.getPersistentDataContainer();
		String title = pdc.get(WEAPON_TITLE, PersistentDataType.STRING);
		if (title == null || title.isBlank()) return null;

		String fileKey = WmWeaponImporter.sanitizeKey(title);
		Weapon template = weaponManager.getWeaponTemplate(fileKey);
		if (template == null) return null;

		Weapon fresh = weaponManager.getWeapon(null, null, fileKey, true);
		if (fresh == null) return null;

		// currentMagCapacity must be set BEFORE buildItem()/updateWeaponData() are called below - both derive the
		// baked display name (Weapon#buildDisplayName) and the AMMO_LEFT tag from this field, and buildItem() alone
		// only bakes the display name once (from whatever currentMagCapacity was at that moment), it never
		// recomputes it afterwards.
		Integer ammoLeft = pdc.get(AMMO_LEFT, PersistentDataType.INTEGER);
		if (ammoLeft != null) fresh.setCurrentMagCapacity(ammoLeft);

		ItemBuilder builder = new ItemBuilder(fresh.buildItem());
		// buildItem() bakes changingDisplayName from whatever currentMagCapacity was at construction/clone time
		// (the fresh weapon's full magazine), not the ammoLeft just synced above - updateWeaponData re-derives the
		// display name and re-syncs every runtime tag (AMMO_LEFT included) from fresh's current fields, the same
		// path WeaponService#setWeaponData relies on elsewhere, so the tooltip and the NBT a later shot/reload
		// reads back always agree.
		fresh.updateWeaponData(builder);
		ItemStack rebuilt = builder.build();
		rebuilt.setAmount(item.getAmount());

		log.debug("Converted a WeaponMechanics '{}' item to Bartizan's '{}'", title, fileKey);
		return rebuilt;
	}

}
