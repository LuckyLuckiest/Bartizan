package org.luckyraven.bartizan.api.weapon;

import lombok.Getter;
import lombok.Setter;
import org.bukkit.Material;
import org.jetbrains.annotations.Nullable;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.api.weapon.dto.AmmunitionData;
import org.luckyraven.bartizan.api.weapon.dto.ExplosionData;
import org.luckyraven.bartizan.api.weapon.dto.ReloadData;
import org.luckyraven.bartizan.api.weapon.dto.ThrowableData;
import org.luckyraven.bartizan.api.weapon.WeaponType;

import java.util.List;
import java.util.UUID;

@Getter
@Setter
public class ThrowableWeapon extends Weapon {

	private final ThrowableData throwableData;
	/**
	 * Gate {@code HI-a} — the unified AOE explosion config for {@code Type: EXPLOSIVE} throwables.
	 * Default-constructed like {@code GunWeapon#damageData}; {@code ThrowableWeaponParser} lowers the legacy
	 * {@code Throw.Explosion_*}/{@code Fuse_Time} keys into it via {@code ExplosionSectionParser} so existing
	 * grenade configs behave identically.
	 */
	private ExplosionData explosionData;

	public ThrowableWeapon(UUID uuid, String name, String displayName, WeaponType category, Material material,
	                       int customModelData, short durability, List<String> lore, boolean dropHologram,
	                       @Nullable List<String> deathMessages, ThrowableData throwableData,
	                       @Nullable ReloadData reloadData, @Nullable AmmunitionData ammunitionData) {
		super(uuid, name, displayName, category, material, customModelData, durability, lore, dropHologram,
		      deathMessages, reloadData, ammunitionData);
		this.throwableData = throwableData;
		this.explosionData = new ExplosionData();
	}

	@Override
	public ThrowableWeapon copyWithUUID(UUID newUuid) {
		// this.clone(), not super.clone() — must reach ThrowableWeapon.clone()'s explosionData deep copy below
		// through virtual dispatch, exactly like GunWeapon.copyWithUUID (BZ-WM-10).
		ThrowableWeapon copy = this.clone();
		copy.setUUID(newUuid);
		return copy;
	}

	@Override
	public ThrowableWeapon clone() {
		ThrowableWeapon copy = (ThrowableWeapon) super.clone();
		copy.explosionData = this.explosionData.clone();
		return copy;
	}

}
