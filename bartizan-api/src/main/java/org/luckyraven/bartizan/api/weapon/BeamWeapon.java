package org.luckyraven.bartizan.api.weapon;

import lombok.Getter;
import lombok.Setter;
import org.bukkit.Material;
import org.jetbrains.annotations.Nullable;
import org.luckyraven.bartizan.api.weapon.dto.AmmunitionData;
import org.luckyraven.bartizan.api.weapon.dto.BeamData;
import org.luckyraven.bartizan.api.weapon.dto.ChargeData;
import org.luckyraven.bartizan.api.weapon.dto.ReloadData;

import java.util.List;
import java.util.UUID;

/**
 * A charge-then-release beam weapon (weapons-roadmap.md gate {@code HC}). Shaped like {@link BiologicalWeapon}:
 * {@link #charge} is the shared {@code ChargeController} config, {@link #beam} is everything specific to the fired
 * shot (range, pierce, damage, preview and render).
 */
@Getter
@Setter
public class BeamWeapon extends Weapon {

	private final BeamData   beam;
	private final ChargeData charge;

	public BeamWeapon(UUID uuid, String name, String displayName, WeaponType category, Material material,
	                  int customModelData, short durability, List<String> lore, boolean dropHologram,
	                  @Nullable List<String> deathMessages, BeamData beam, ChargeData charge,
	                  @Nullable ReloadData reloadData, @Nullable AmmunitionData ammunitionData) {
		super(uuid, name, displayName, category, material, customModelData, durability, lore, dropHologram,
		      deathMessages, reloadData, ammunitionData);
		this.beam   = beam;
		this.charge = charge;
	}

	@Override
	public BeamWeapon copyWithUUID(UUID newUuid) {
		BeamWeapon copy = (BeamWeapon) super.clone();
		copy.setUUID(newUuid);
		return copy;
	}

	@Override
	public BeamWeapon clone() {
		return (BeamWeapon) super.clone();
	}

}
