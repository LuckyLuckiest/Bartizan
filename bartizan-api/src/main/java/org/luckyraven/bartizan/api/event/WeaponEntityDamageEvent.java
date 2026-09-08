package org.luckyraven.bartizan.api.event;

import lombok.AccessLevel;
import lombok.Getter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.luckyraven.bartizan.api.weapon.Weapon;

import java.util.Objects;

/**
 * Fired when a non-projectile weapon (incendiary, biological) hits a non-living entity such as a vehicle. Listeners
 * such as {@code CarDamageListener} intercept this to apply the correct weapon damage.
 *
 * <p>{@link #weaponName()} / {@link #kind()} replace the old {@code ThrowableAction} static maps
 * ({@code pendingKillerWeapon}, {@code pendingVehicleExplosionDamage} - bartizan.md §1.6(3)): the firing action
 * stamps both at construction time, so a listener never has to look up a short-lived side table keyed by entity
 * UUID.
 */
@Getter
public class WeaponEntityDamageEvent extends WeaponEvent {

	private static final HandlerList HANDLERS = new HandlerList();

	private final Entity entity;
	private final double damage;
	private final Player shooter;

	@Getter(AccessLevel.NONE)
	private final String weaponName;

	@Getter(AccessLevel.NONE)
	private final DamageKind kind;

	public WeaponEntityDamageEvent(Weapon weapon, Entity entity, double damage, Player shooter, String weaponName,
	                               DamageKind kind) {
		super(weapon);
		this.entity     = entity;
		this.damage     = damage;
		this.shooter    = shooter;
		this.weaponName = Objects.requireNonNull(weaponName, "weaponName");
		this.kind       = Objects.requireNonNull(kind, "kind");
	}

	public static HandlerList getHandlerList() {
		return HANDLERS;
	}

	/** The firing weapon's name, stamped at construction. Never {@code null}. */
	public String weaponName() {
		return weaponName;
	}

	/** What kind of hit this was - the firing action decides, the raytracer/entity-damage path cannot infer it. */
	public DamageKind kind() {
		return kind;
	}

	@Override
	@NotNull
	public HandlerList getHandlers() {
		return HANDLERS;
	}

	public enum DamageKind {
		DIRECT,
		EXPLOSION,
		FIRE,
		BIOLOGICAL,
		MELEE
	}

}
