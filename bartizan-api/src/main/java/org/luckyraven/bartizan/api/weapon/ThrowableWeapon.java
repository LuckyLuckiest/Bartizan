package org.luckyraven.bartizan.api.weapon;

import lombok.Getter;
import lombok.Setter;
import org.bukkit.Material;
import org.jetbrains.annotations.Nullable;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.api.weapon.dto.AmmunitionData;
import org.luckyraven.bartizan.api.weapon.dto.ReloadData;
import org.luckyraven.bartizan.api.weapon.dto.ThrowableData;
import org.luckyraven.bartizan.api.weapon.WeaponType;

import java.util.List;
import java.util.UUID;

@Getter
@Setter
public class ThrowableWeapon extends Weapon {

	private final ThrowableData throwableData;

	public ThrowableWeapon(UUID uuid, String name, String displayName, WeaponType category, Material material,
	                       int customModelData, short durability, List<String> lore, boolean dropHologram,
	                       @Nullable List<String> deathMessages, ThrowableData throwableData,
	                       @Nullable ReloadData reloadData, @Nullable AmmunitionData ammunitionData) {
		super(uuid, name, displayName, category, material, customModelData, durability, lore, dropHologram,
		      deathMessages, reloadData, ammunitionData);
		this.throwableData = throwableData;
	}

	@Override
	public ThrowableWeapon copyWithUUID(UUID newUuid) {
		ThrowableWeapon copy = (ThrowableWeapon) super.clone();
		copy.setUUID(newUuid);
		return copy;
	}

	@Override
	public ThrowableWeapon clone() {
		// No extra mutable fields — Weapon.clone() handles everything.
		return (ThrowableWeapon) super.clone();
	}

}
