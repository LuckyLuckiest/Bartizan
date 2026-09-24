package org.luckyraven.bartizan.listener.wearable;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType.SlotType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.luckyraven.keystone.bean.autowire.AutowireTarget;
import org.luckyraven.keystone.bean.listener.ListenerHandler;
import org.luckyraven.bartizan.file.BartizanMessages;
import org.luckyraven.bartizan.wearable.WearableService;
import org.luckyraven.bartizan.api.wearable.Wearable;

/**
 * Handles wearable armor equip validation.
 *
 * <p>On equip (direct drag to armor slot, shift-click auto-equip, hotbar-number-key swap, or plain right-click):
 * if the armor piece is a registered {@link Wearable} with a permission node, the equip is blocked for players
 * that lack that permission.
 *
 * <p>bartizan.md §1.1 (group H PKG+): drops the deleted {@code WearableEquipService} indirection (T-14 is dissolved,
 * not ported — that seam existed only because the weapon module lived apart from {@code gangland-item}'s core
 * listener; both now live in the same plugin) and calls {@link WearableService} directly.
 */
@ListenerHandler
@AutowireTarget({WearableService.class})
public class WearableEquipListener implements Listener {

	private final WearableService wearableService;

	public WearableEquipListener(WearableService wearableService) {
		this.wearableService = wearableService;
	}

	@EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
	public void onArmorEquip(InventoryClickEvent event) {
		if (!(event.getWhoClicked() instanceof Player player)) return;

		ItemStack toEquip = resolveArmorBeingEquipped(event);
		if (isPermissionDenied(player, toEquip)) {
			event.setCancelled(true);
			sendDenied(player);
		}
	}

	/**
	 * BZ-WE-01 (fix round 1): no {@code ignoreCancelled} here. CraftBukkit's {@link PlayerInteractEvent}
	 * constructor sets {@code useClickedBlock = DENY} whenever the clicked block is {@code null}, and
	 * {@code isCancelled()} reads that same field — so every {@code RIGHT_CLICK_AIR} event (looking at open air,
	 * the ordinary way to equip armor by right-click) arrives already "cancelled" before any listener runs.
	 * {@code ignoreCancelled = true} would make Bukkit skip this handler for that exact case, leaving only the
	 * rarer {@code RIGHT_CLICK_BLOCK} path gated. Denying just {@code useItemInHand} (not the whole event) stops
	 * the equip before it happens without also blocking an unrelated block interaction (a chest, a door) while
	 * the denied item merely happens to be in hand.
	 */
	@EventHandler(priority = EventPriority.LOW)
	public void onArmorRightClickEquip(PlayerInteractEvent event) {
		if (event.getHand() != EquipmentSlot.HAND) return;

		Action action = event.getAction();
		if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;

		Player player = event.getPlayer();
		if (isPermissionDenied(player, event.getItem())) {
			event.setUseItemInHand(Event.Result.DENY);
			sendDenied(player);
		}
	}

	/**
	 * {@code true} when {@code toEquip} is a registered {@link Wearable} carrying a permission node the player
	 * lacks — the single permission check shared by every equip path above.
	 */
	private boolean isPermissionDenied(Player player, @Nullable ItemStack toEquip) {
		if (!Wearable.isRegisteredWearable(toEquip)) return false;

		String key = Wearable.getWearableKey(toEquip);
		if (key == null) return false;

		Wearable wearable = wearableService.getWearable(key);
		if (wearable == null) return false;

		String permission = wearable.getPermission();
		return permission != null && !permission.isEmpty() && !player.hasPermission(permission);
	}

	private void sendDenied(Player player) {
		player.sendMessage(BartizanMessages.WEARABLE_EQUIP_DENIED.toString());
	}

	/**
	 * Determines which armor ItemStack is being equipped in the given click event, or returns {@code null} if the click
	 * does not result in an armor piece being equipped.
	 *
	 * <p>Covers three cases:
	 * <ul>
	 *   <li>Direct placement into an armor slot (cursor → armor slot).</li>
	 *   <li>Shift-click of an armor item from any other slot (auto-equip to the matching armor
	 *       slot).</li>
	 *   <li>A hotbar number-key press over an armor slot ({@code HOTBAR_SWAP}) - the item that swaps in is the
	 *       one currently sitting in that hotbar slot, not the cursor. Pressing F ({@code ClickType.SWAP_OFFHAND})
	 *       over an armor slot also produces {@code HOTBAR_SWAP}, but CraftBukkit builds that event with the 5-arg
	 *       {@link InventoryClickEvent} constructor, which leaves {@link InventoryClickEvent#getHotbarButton()} at
	 *       its default of {@code -1} - the item that swaps in there is the current off-hand item, not
	 *       {@code getItem(-1)}.</li>
	 * </ul>
	 */
	private ItemStack resolveArmorBeingEquipped(InventoryClickEvent event) {
		// Case 1: player dragged cursor item directly onto an armor slot
		if (event.getSlotType() == SlotType.ARMOR) {
			InventoryAction action = event.getAction();
			if (action == InventoryAction.PLACE_ALL || action == InventoryAction.PLACE_ONE ||
			    action == InventoryAction.PLACE_SOME || action == InventoryAction.SWAP_WITH_CURSOR) {
				ItemStack cursor = event.getCursor();
				return Wearable.isArmorItem(cursor) ? cursor : null;
			}
			if (action == InventoryAction.HOTBAR_SWAP) {
				int hotbarButton = event.getHotbarButton();
				ItemStack hotbarItem = hotbarButton < 0
						? event.getWhoClicked().getInventory().getItemInOffHand()
						: event.getWhoClicked().getInventory().getItem(hotbarButton);
				return Wearable.isArmorItem(hotbarItem) ? hotbarItem : null;
			}
			return null;
		}

		// Case 2: shift-click on an armor item auto-equips it
		if (event.isShiftClick()) {
			ItemStack current = event.getCurrentItem();
			return Wearable.isArmorItem(current) ? current : null;
		}

		return null;
	}

}
