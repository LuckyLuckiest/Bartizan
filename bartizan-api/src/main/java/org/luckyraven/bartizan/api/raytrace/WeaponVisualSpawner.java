package org.luckyraven.bartizan.api.raytrace;

import java.lang.reflect.Method;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Fireball;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.Vector;
import org.luckyraven.bartizan.api.weapon.dto.VisualData;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Spawns purely cosmetic Bukkit entities (visual carriers) for weapons that want a visible flying object — slow
 * rockets, flares, throwables, and tracer-style hitscan visuals.
 * <p>
 * Cosmetic entities never drive damage logic. Their entity IDs are registered in {@link #cosmeticEntityIds}, and
 * {@code ProjectileDamageListener} early-returns out of {@code ProjectileHitEvent} and
 * {@code EntityDamageByEntityEvent} for any entity in this set. Hit detection is owned exclusively by
 * {@link WeaponRaytracer}.
 */
public class WeaponVisualSpawner {

	private final Set<Integer> cosmeticEntityIds = ConcurrentHashMap.newKeySet();
	// Entity refs alongside the id set above, so a shutdown can actually remove what's still flying — an
	// ARMOR_STAND/PRIMED_TNT visual never self-expires and would otherwise leak into the world (HI-b review #5).
	private final Map<Integer, Entity> spawnedEntities = new ConcurrentHashMap<>();

	/**
	 * Marks the given entity as a cosmetic carrier so the legacy listener will ignore it.
	 */
	public void registerCosmetic(int entityId) {
		cosmeticEntityIds.add(entityId);
	}

	/**
	 * Removes the given entity from the cosmetic registry (called when the visual is despawned).
	 */
	public void unregisterCosmetic(int entityId) {
		cosmeticEntityIds.remove(entityId);
		spawnedEntities.remove(entityId);
	}

	/**
	 * True if the given entity ID belongs to a registered cosmetic visual.
	 */
	public boolean isCosmetic(int entityId) {
		return cosmeticEntityIds.contains(entityId);
	}

	/**
	 * Removes every still-tracked cosmetic visual from the world (plugin disable / server stop). Without this, a
	 * visual type that never self-expires on its own (an {@code ARMOR_STAND} or a {@code PRIMED_TNT} whose fuse is
	 * set to {@code Integer.MAX_VALUE}) is orphaned in the world forever once its driving task stops running.
	 */
	public void removeAll() {
		for (Entity entity : spawnedEntities.values()) {
			if (!entity.isDead() && entity.isValid()) {
				entity.remove();
			}
		}
		spawnedEntities.clear();
		cosmeticEntityIds.clear();
	}

	/**
	 * Spawns a Bukkit projectile of the given type at {@code spawnLocation} with the given velocity and registers it as
	 * cosmetic. The returned entity is silent, gravity-free, and shooter-owned; its only role is visual.
	 */
	public <T extends Projectile> T spawnCosmetic(Class<T> type, LivingEntity shooter, Location spawnLocation,
	                                              Vector velocity) {
		var world = spawnLocation.getWorld();
		if (world == null) {
			return null;
		}

		T projectile = world.spawn(spawnLocation, type);
		projectile.setSilent(true);
		projectile.setGravity(false);
		projectile.setShooter(shooter);
		projectile.setVelocity(velocity);

		registerCosmetic(projectile.getEntityId());
		spawnedEntities.put(projectile.getEntityId(), projectile);
		return projectile;
	}

	/**
	 * Spawns the cosmetic entity a stepped slow projectile (weapons-roadmap.md gate {@code HI} part b) drives — the
	 * type named by {@link VisualData#type()}. Unlike {@link #spawnCosmetic}, {@code SteppedProjectileTask}
	 * repositions the returned entity itself every tick rather than relying on Bukkit physics ("server path is the
	 * truth"), so this only needs to set up a plausible first frame — silent, no gravity, no vanilla interaction.
	 *
	 * @return the spawned entity, registered as cosmetic, or {@code null} when {@code spawnLocation}'s world is
	 * 		unloaded.
	 */
	public Entity spawnVisual(VisualData visual, Location spawnLocation, Vector direction, LivingEntity shooter) {
		World world = spawnLocation.getWorld();
		if (world == null) {
			return null;
		}

		Entity entity = switch (visual.type()) {
			case FIREBALL -> spawnProjectile(Fireball.class, world, spawnLocation, direction, shooter);
			case FIREWORK -> spawnProjectile(Firework.class, world, spawnLocation, direction, shooter);
			case DROPPED_ITEM -> spawnItem(world, spawnLocation, visual);
			case FALLING_BLOCK -> spawnFallingBlock(world, spawnLocation, visual);
			case ARMOR_STAND -> spawnArmorStand(world, spawnLocation, visual);
			case PRIMED_TNT -> spawnTnt(world, spawnLocation);
		};
		// never saved with its chunk: an unload mid-flight would otherwise leave it behind under a fresh entity id
		// this spawner no longer tracks - a DROPPED_ITEM visual a hopper can then collect (BZ-RT-18), or an orphaned
		// ARMOR_STAND/PRIMED_TNT
		entity.setPersistent(false);

		registerCosmetic(entity.getEntityId());
		spawnedEntities.put(entity.getEntityId(), entity);
		return entity;
	}

	private <T extends Projectile> T spawnProjectile(Class<T> type, World world, Location loc, Vector direction,
	                                                  LivingEntity shooter) {
		T projectile = world.spawn(loc, type);
		projectile.setSilent(true);
		projectile.setGravity(false);
		projectile.setVelocity(direction);

		if (projectile instanceof Firework firework) {
			// Vanilla fireworks self-detonate once their life reaches maxLife — a random 10-22 tick fuse by
			// default. A cosmetic visual driven by SteppedProjectileTask must outlive that (HI-b review #3); the
			// Bukkit API has no setTicksToDetonate, so push maxLife out instead of the current life counter.
			setMaxLife(firework, Integer.MAX_VALUE);
		} else if (projectile instanceof Fireball fireball) {
			// world.spawn(loc, Fireball.class) yields a LargeFireball — yield 1, incendiary — by default. Neutralize
			// both so the purely cosmetic visual can never actually blast/ignite on its own, and set the shooter so
			// it never collides with (and explodes on) its own shooter at the muzzle (HI-b review #3).
			fireball.setYield(0);
			fireball.setIsIncendiary(false);
			fireball.setShooter(shooter);
		}

		return projectile;
	}

	// Firework#setMaxLife is 1.19.4+; the compile floor is 1.16.5, so it is reached reflectively.
	// ponytail: below 1.19.4 a firework visual self-detonates after the vanilla 10-22 tick fuse; drive the NMS
	// lifetime field through Keystone's NmsCache if anyone runs one.
	private static final Method SET_MAX_LIFE = lookupSetMaxLife();

	private static Method lookupSetMaxLife() {
		try {
			return Firework.class.getMethod("setMaxLife", int.class);
		} catch (NoSuchMethodException absent) {
			return null;
		}
	}

	private static void setMaxLife(Firework firework, int ticks) {
		if (SET_MAX_LIFE == null) return;
		try {
			SET_MAX_LIFE.invoke(firework, ticks);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Firework#setMaxLife is present but not invokable", e);
		}
	}

	private Entity spawnItem(World world, Location loc, VisualData visual) {
		Item      item     = world.spawn(loc, Item.class);
		ItemStack stack    = withCustomModelData(new ItemStack(orDefault(visual.item(), Material.FEATHER)),
		                                         visual.customModelData());
		item.setItemStack(stack);
		// setCanMobPickup is Paper-only (not in Spigot's Item API — CLAUDE.md: Spigot only), so this can't stop a
		// mob from grabbing the visual; setPickupDelay(MAX_VALUE) at least keeps players off it.
		item.setPickupDelay(Integer.MAX_VALUE);
		item.setGravity(false);
		item.setSilent(true);
		return item;
	}

	private Entity spawnFallingBlock(World world, Location loc, VisualData visual) {
		Material     material = orDefault(visual.block(), Material.STONE);
		FallingBlock block    = world.spawnFallingBlock(loc, material.createBlockData());
		block.setGravity(false);
		block.setDropItem(false);
		block.setHurtEntities(false);
		return block;
	}

	private Entity spawnArmorStand(World world, Location loc, VisualData visual) {
		ArmorStand stand = world.spawn(loc, ArmorStand.class);
		stand.setVisible(false);
		stand.setMarker(true);
		stand.setSmall(true);
		stand.setGravity(false);
		stand.setInvulnerable(true);
		stand.setSilent(true);

		if (visual.item() != null) {
			EntityEquipment equipment = stand.getEquipment();
			if (equipment != null) {
				equipment.setHelmet(withCustomModelData(new ItemStack(visual.item()), visual.customModelData()));
			}
		}
		return stand;
	}

	private Entity spawnTnt(World world, Location loc) {
		TNTPrimed tnt = world.spawn(loc, TNTPrimed.class);
		tnt.setFuseTicks(Integer.MAX_VALUE);
		tnt.setGravity(false);
		return tnt;
	}

	private static Material orDefault(Material material, Material fallback) {
		return material != null ? material : fallback;
	}

	private static ItemStack withCustomModelData(ItemStack stack, int customModelData) {
		if (customModelData <= 0) {
			return stack;
		}

		ItemMeta meta = stack.getItemMeta();
		if (meta != null) {
			meta.setCustomModelData(customModelData);
			stack.setItemMeta(meta);
		}
		return stack;
	}

}
