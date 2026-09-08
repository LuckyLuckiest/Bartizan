package org.luckyraven.bartizan.api.weapon;

import lombok.Getter;
import lombok.Setter;
import org.bukkit.Material;
import org.jetbrains.annotations.Nullable;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.api.weapon.dto.AmmunitionData;
import org.luckyraven.bartizan.api.weapon.dto.MeleeData;
import org.luckyraven.bartizan.api.weapon.dto.ReloadData;
import org.luckyraven.bartizan.api.weapon.WeaponType;

import java.util.List;
import java.util.UUID;

@Getter
@Setter
public class MeleeWeapon extends Weapon {

	private final MeleeData meleeData;

	public MeleeWeapon(UUID uuid, String name, String displayName, WeaponType category, Material material,
	                   int customModelData, short durability, List<String> lore, boolean dropHologram,
	                   @Nullable List<String> deathMessages, MeleeData meleeData,
	                   @Nullable ReloadData reloadData, @Nullable AmmunitionData ammunitionData) {
		super(uuid, name, displayName, category, material, customModelData, durability, lore, dropHologram,
		      deathMessages, reloadData, ammunitionData);
		this.meleeData = meleeData;
	}

	@Override
	public MeleeWeapon copyWithUUID(UUID newUuid) {
		MeleeWeapon copy = (MeleeWeapon) super.clone();
		copy.setUUID(newUuid);
		return copy;
	}

	@Override
	public MeleeWeapon clone() {
		// No extra mutable fields — Weapon.clone() handles everything.
		return (MeleeWeapon) super.clone();
	}

}
