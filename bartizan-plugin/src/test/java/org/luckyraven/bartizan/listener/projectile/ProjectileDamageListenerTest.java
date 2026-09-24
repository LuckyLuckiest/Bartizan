package org.luckyraven.bartizan.listener.projectile;

import org.bukkit.entity.Item;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.persistence.PersistentDataContainer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.raytrace.WeaponVisualSpawner;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers {@link ProjectileDamageListener}'s guards that keep Bartizan's purely cosmetic entities out of vanilla
 * mechanics: a hopper (or hopper minecart) must never collect a cosmetic item visual.
 */
@DisplayName("ProjectileDamageListener")
class ProjectileDamageListenerTest {

	private final WeaponVisualSpawner      visualSpawner = new WeaponVisualSpawner();
	private final ProjectileDamageListener listener      = new ProjectileDamageListener(visualSpawner);

	private static Item item(int entityId) {
		Item item = mock(Item.class);
		when(item.getEntityId()).thenReturn(entityId);
		when(item.getPersistentDataContainer()).thenReturn(mock(PersistentDataContainer.class));
		return item;
	}

	@Test
	@DisplayName("hopper pickup: a registered DROPPED_ITEM visual is refused")
	void hopperPickup_cosmeticVisual_cancelled() {
		visualSpawner.registerCosmetic(42);
		InventoryPickupItemEvent event = new InventoryPickupItemEvent(mock(Inventory.class), item(42));

		listener.onHopperPickup(event);

		assertTrue(event.isCancelled(), "a cosmetic visual must not become a real item in a hopper");
	}

	@Test
	@DisplayName("hopper pickup: an ordinary dropped item is left alone")
	void hopperPickup_ordinaryItem_notCancelled() {
		InventoryPickupItemEvent event = new InventoryPickupItemEvent(mock(Inventory.class), item(7));

		listener.onHopperPickup(event);

		assertFalse(event.isCancelled());
	}

}
