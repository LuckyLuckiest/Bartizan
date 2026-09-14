package org.luckyraven.bartizan.api.weapon.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.jetbrains.annotations.Nullable;
import org.luckyraven.keystone.exception.PluginException;
import org.luckyraven.bartizan.api.ammo.Ammunition;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
public class AmmunitionData implements Cloneable {

	/**
	 * Configured ammo item types, in preference order ({@code Ammunition.Types}). Empty means {@code Ammo_Type:
	 * none} — a magazine that reloads without consuming any item (infinite supply).
	 */
	private List<Ammunition> ammoTypes = new ArrayList<>();
	private int              maxMagCapacity;
	private int              consumeRate;
	private int              restore;

	/**
	 * Convenience constructor for the single-{@code Ammo_Type} case (the vast majority of configured weapons).
	 * {@code ammoType} of {@code null} means {@code Ammo_Type: none}.
	 */
	public AmmunitionData(@Nullable Ammunition ammoType, int maxMagCapacity, int consumeRate, int restore) {
		this(ammoType != null ? List.of(ammoType) : List.<Ammunition>of(), maxMagCapacity, consumeRate, restore);
	}

	public AmmunitionData(List<Ammunition> ammoTypes, int maxMagCapacity, int consumeRate, int restore) {
		this.ammoTypes      = ammoTypes;
		this.maxMagCapacity = maxMagCapacity;
		this.consumeRate    = consumeRate;
		this.restore        = restore;
	}

	/**
	 * The single configured ammo type — the first of {@link #getAmmoTypes()}, or {@code null} for {@code
	 * Ammo_Type: none} / an empty {@code Types:} list. Kept so single-type call sites (display, comparisons, the
	 * legacy reload path) don't need to change.
	 */
	@Nullable
	public Ammunition getAmmoType() {
		return ammoTypes.isEmpty() ? null : ammoTypes.get(0);
	}

	@Override
	public AmmunitionData clone() {
		try {
			AmmunitionData copy = (AmmunitionData) super.clone();
			copy.ammoTypes = new ArrayList<>(this.ammoTypes);
			return copy;
		} catch (CloneNotSupportedException exception) {
			throw new PluginException(exception);
		}
	}

}
