package org.luckyraven.bartizan.api.weapon;

import lombok.Getter;
import lombok.Setter;
import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.luckyraven.bartizan.api.ammo.Ammunition;
import org.luckyraven.bartizan.api.weapon.SelectiveFire;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.api.weapon.dto.AmmunitionData;
import org.luckyraven.bartizan.api.weapon.dto.DamageData;
import org.luckyraven.bartizan.api.weapon.dto.ExplosionData;
import org.luckyraven.bartizan.api.weapon.dto.ProjectileData;
import org.luckyraven.bartizan.api.weapon.dto.ReloadData;
import org.luckyraven.bartizan.api.weapon.WeaponType;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
public class GunWeapon extends Weapon {

	// Gun-specific immutable configuration
	private final ProjectileData projectileData;
	private final int            weaponConsumedOnShot;

	// Gun-specific mutable configuration
	private DamageData    damageData;
	/**
	 * Gate {@code HI-a} — the unified AOE explosion config for ROCKET/FLARE projectiles. Default-constructed like
	 * {@link #damageData}; {@code GunWeaponParser} lowers the legacy {@code Damage.Explosion_*} keys into it via
	 * {@code ExplosionSectionParser} so existing rocket configs behave identically.
	 */
	private ExplosionData explosionData;

	public GunWeapon(UUID uuid, String name, String displayName, WeaponType category, Material material,
	                 int customModelData, short durability, List<String> lore, boolean dropHologram,
	                 @Nullable List<String> deathMessages, SelectiveFire selectiveFire, int weaponConsumedOnShot,
	                 ProjectileData projectileData, @Nullable ReloadData reloadData,
	                 @Nullable AmmunitionData ammunitionData) {
		super(uuid, name, displayName, category, material, customModelData, durability, lore, dropHologram,
		      deathMessages, reloadData, ammunitionData);
		this.projectileData       = projectileData;
		this.weaponConsumedOnShot = weaponConsumedOnShot;
		this.setCurrentSelectiveFire(selectiveFire);

		this.damageData    = new DamageData();
		this.explosionData = new ExplosionData();
	}

	// --- Magazine override (uses projectile consumed amount per shot) ---

	@Override
	public boolean consumeShot() {
		if (isMagazineEmpty()) return false;
		setCurrentMagCapacity(Math.max(0, getCurrentMagCapacity() - projectileData.getConsumed()));
		return true;
	}

	// --- Overrides ---

	@Override
	public GunWeapon copyWithUUID(UUID newUuid) {
		GunWeapon copy = this.clone();
		copy.setUUID(newUuid);
		return copy;
	}

	@Override
	public GunWeapon clone() {
		// super.clone() (Weapon.clone()) performs Object.clone() + initClone(),
		// covering tags, durabilityData, soundData, reloadActionBarData,
		// modifiersData, recoilData, scopeData, spreadData,
		// recoil/spread managers, durabilityCalculator, currentMagCapacity, and reload.
		GunWeapon copy = (GunWeapon) super.clone();

		// Clone Gun-specific mutable data
		copy.damageData    = this.damageData.clone();
		copy.explosionData = this.explosionData.clone();

		return copy;
	}

	@Override
	public int compareTo(@NotNull Weapon other) {
		int base = super.compareTo(other);
		if (base != 0 || !(other instanceof GunWeapon otherGunWeapon)) return base;
		return Comparator.<GunWeapon, Double>comparing(
								 g -> g.projectileData != null ? g.projectileData.getSpeed() : 0.0)
		                 .thenComparing(g -> g.projectileData != null ? g.projectileData.getType().name() : "")
		                 .thenComparingDouble(g -> g.projectileData != null ? g.projectileData.getDamage() : 0.0)
		                 .thenComparingInt(g -> g.projectileData != null ? g.projectileData.getConsumed() : 0)
		                 .thenComparingInt(g -> g.projectileData != null ? g.projectileData.getPerShot() : 0)
		                 .thenComparingInt(g -> g.projectileData != null ? g.projectileData.getCooldown() : 0)
		                 .thenComparingInt(g -> g.projectileData != null ? g.projectileData.getDistance() : 0)
		                 .thenComparing(g -> g.projectileData != null && g.projectileData.isParticle())
		                 .thenComparingInt(
								 g -> g.getAmmunitionData() != null ? g.getAmmunitionData().getMaxMagCapacity() : 0)
		                 .thenComparingInt(g -> g.getReloadData() != null ? g.getReloadData().getCooldown() : 0)
		                 .thenComparing(GunWeapon::ammoTypeNameForComparison)
		                 .thenComparingInt(
								 g -> g.getAmmunitionData() != null ? g.getAmmunitionData().getConsumeRate() : 0)
		                 .thenComparingInt(g -> g.getAmmunitionData() != null ? g.getAmmunitionData().getRestore() : 0)
		                 .thenComparing(g -> g.getReloadData() != null ? g.getReloadData().getType().name() : "")
		                 .compare(this, otherGunWeapon);
	}

	/**
	 * {@code AmmunitionData#getAmmoType()} is {@code null} for {@code Ammo_Type: none}, so this reads it through a
	 * null-safe accessor rather than inlining another {@code != null ? ... : ""} ternary into the comparator chain.
	 */
	private static String ammoTypeNameForComparison(GunWeapon gun) {
		AmmunitionData data = gun.getAmmunitionData();
		Ammunition     ammo = data != null ? data.getAmmoType() : null;
		return ammo != null ? ammo.getName() : "";
	}

}
