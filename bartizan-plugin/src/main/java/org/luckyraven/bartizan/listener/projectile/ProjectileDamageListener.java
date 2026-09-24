package org.luckyraven.bartizan.listener.projectile;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.luckyraven.keystone.bean.autowire.AutowireTarget;
import org.luckyraven.keystone.bean.listener.ListenerHandler;
import org.luckyraven.bartizan.weapon.WeaponService;
import org.luckyraven.bartizan.api.raytrace.WeaponVisualSpawner;

/**
 * Vestigial listener that exists only to suppress the legacy {@code ProjectileHitEvent} /
 * {@code EntityDamageByEntityEvent} chain for cosmetic visual projectiles spawned by the new {@code WeaponRaytracer}.
 * All weapon damage logic now runs through the unified raytracer; this class only guards against vanilla event handling
 * for the visual carrier entities (Fireball, Firework, Snowball, etc.) so they don't double-process or trigger spurious
 * cops-n-crooks NPC reactions on contact.
 */
@ListenerHandler
@AutowireTarget({WeaponService.class})
public class ProjectileDamageListener implements Listener {

	private final WeaponVisualSpawner visualSpawner;

	public ProjectileDamageListener(WeaponVisualSpawner visualSpawner) {
		this.visualSpawner = visualSpawner;
	}

	@EventHandler(priority = EventPriority.LOWEST)
	public void onProjectileEntityDamage(EntityDamageByEntityEvent event) {
		// Cosmetic visuals do not drive damage — cancel the event so no downstream listener (including
		// cops-n-crooks NPC AI) reacts to a purely visual entity hitting something. Checked directly against the
		// damager's entity id (not narrowed to `instanceof Projectile`) since gate HI part b's Projectile.Visual
		// can drive a FallingBlock/Item/ArmorStand/TNTPrimed visual too, none of which are a Projectile.
		if (visualSpawner.isCosmetic(event.getDamager().getEntityId())) {
			event.setCancelled(true);
		}
	}

	/**
	 * Hoppers and hopper minecarts ignore an {@code Item}'s pickup delay, so without this a {@code DROPPED_ITEM}
	 * visual flying over one (or a thrown grenade's display item landing on one) would be collected as a real item.
	 */
	@EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
	public void onHopperPickup(InventoryPickupItemEvent event) {
		if (visualSpawner.isCosmetic(event.getItem().getEntityId()) || CosmeticTag.isMarked(event.getItem())) {
			event.setCancelled(true);
		}
	}

	@EventHandler(priority = EventPriority.LOWEST)
	public void onProjectileHit(ProjectileHitEvent event) {
		int projectileId = event.getEntity().getEntityId();

		if (visualSpawner.isCosmetic(projectileId)) {
			visualSpawner.unregisterCosmetic(projectileId);
		}
	}

}
