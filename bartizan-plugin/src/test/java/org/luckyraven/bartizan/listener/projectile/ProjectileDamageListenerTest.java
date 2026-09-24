package org.luckyraven.bartizan.listener.projectile;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Fireball;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.raytrace.WeaponVisualSpawner;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
	@DisplayName("hopper pickup: a CosmeticTag-marked grenade display item is refused")
	void hopperPickup_markedGrenade_cancelled() {
		Item grenade = item(9);
		when(grenade.getPersistentDataContainer().has(CosmeticTag.KEY, PersistentDataType.BYTE)).thenReturn(true);
		InventoryPickupItemEvent event = new InventoryPickupItemEvent(mock(Inventory.class), grenade);

		listener.onHopperPickup(event);

		assertTrue(event.isCancelled(), "a thrown grenade must not become a real item in a hopper");
	}

	@Test
	@DisplayName("entity damage: a CosmeticTag-marked Firework effect burst deals no damage")
	void entityDamage_markedFirework_cancelled() {
		PersistentDataContainer pdc      = mock(PersistentDataContainer.class);
		Firework                firework = mock(Firework.class);
		when(firework.getEntityId()).thenReturn(11);
		when(firework.getPersistentDataContainer()).thenReturn(pdc);
		when(pdc.has(CosmeticTag.KEY, PersistentDataType.BYTE)).thenReturn(true);
		EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(firework, mock(Player.class),
		                                                                DamageCause.ENTITY_EXPLOSION, 7.0);

		listener.onProjectileEntityDamage(event);

		assertTrue(event.isCancelled(), "an effect-only firework must not hurt the shooter or bystanders");
	}

	@Test
	@DisplayName("projectile hit: a cosmetic visual's hit is cancelled and it stays cosmetic for that hit's damage event")
	void projectileHit_cosmeticVisual_cancelledAndStillGuardsDamage() {
		Fireball fireball = mock(Fireball.class);
		when(fireball.getEntityId()).thenReturn(21);
		when(fireball.getPersistentDataContainer()).thenReturn(mock(PersistentDataContainer.class));
		visualSpawner.registerCosmetic(21);
		Player                    victim = mock(Player.class);
		ProjectileHitEvent        hit    = new ProjectileHitEvent(fireball, victim);
		EntityDamageByEntityEvent damage = new EntityDamageByEntityEvent(fireball, victim,
		                                                                 DamageCause.PROJECTILE, 6.0);

		listener.onProjectileHit(hit);
		listener.onProjectileEntityDamage(damage);

		assertTrue(hit.isCancelled(), "vanilla must not resolve a hit on a cosmetic visual");
		assertTrue(visualSpawner.isCosmetic(21), "only SteppedProjectileTask.terminate() may unregister the visual");
		assertTrue(damage.isCancelled(), "the same collision's vanilla damage must still be cancelled");
	}

	@Test
	@DisplayName("CosmeticTag.mark sets the flag isMarked reads")
	void cosmeticTag_markThenRead() {
		PersistentDataContainer pdc    = mock(PersistentDataContainer.class);
		Entity                  entity = mock(Entity.class);
		when(entity.getPersistentDataContainer()).thenReturn(pdc);

		CosmeticTag.mark(entity);

		verify(pdc).set(CosmeticTag.KEY, PersistentDataType.BYTE, (byte) 1);
	}

	@Test
	@DisplayName("hopper pickup: an ordinary dropped item is left alone")
	void hopperPickup_ordinaryItem_notCancelled() {
		InventoryPickupItemEvent event = new InventoryPickupItemEvent(mock(Inventory.class), item(7));

		listener.onHopperPickup(event);

		assertFalse(event.isCancelled());
	}

}
