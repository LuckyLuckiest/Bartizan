package org.luckyraven.bartizan.api.weapon.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jetbrains.annotations.Nullable;
import org.luckyraven.keystone.exception.PluginException;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DamageData implements Cloneable {

	private double explosionDamage;
	/**
	 * AOE blast radius in blocks. Authored separately from {@link #explosionDamage}: the two were conflated before
	 * 0.8.3, which turned a {@code Explosion_Damage: 50} rocket into a 50-block-radius blast.
	 */
	private double explosionRadius;
	private int    fireTicks;
	private double headDamage;
	private int    criticalHitChance;
	private double criticalHitDamage;

	/**
	 * {@code Damage.Dropoff} — resolved via {@code DamageMath#dropoff}. Never {@code null}, empty when unconfigured
	 * (weapons-roadmap.md gate {@code HF}, §1).
	 */
	private List<DropoffStep> dropoff = new ArrayList<>();

	/**
	 * Hit-zone deltas (gate {@code HF}, §2) — added on top of {@link #headDamage} for the matching zone; a
	 * {@code BACK} hit additionally adds {@link #backDamage} regardless of zone. All default {@code 0} and may be
	 * negative.
	 */
	private double bodyDamage;
	private double armsDamage;
	private double legsDamage;
	private double feetDamage;
	private double backDamage;

	/**
	 * {@code Damage.Armor_Damage} — durability damage dealt to each worn armour piece per gun hit (gate {@code HF},
	 * §3). {@code 0} (default) never touches armour.
	 */
	private int armorDamage;

	/**
	 * {@code Damage.Owner_Immunity} / {@code Damage.Ignore_Teams} (gate {@code HF}, §4). Both default {@code false}
	 * so existing self/teammate damage is unchanged unless a weapon opts in. Resolved via {@code DamageRules}.
	 */
	private boolean ownerImmunity;
	private boolean ignoreTeams;

	/**
	 * {@code Damage.Knockback} — {@code null} (absent) leaves vanilla knockback untouched; any non-null value
	 * (including {@code 0}) replaces it with {@code shotDir * knockback} on a direct gun hit, and adds a falloff
	 * vector on a rocket explosion (gate {@code HF}, §6).
	 */
	@Nullable
	private Double knockback;

	@Override
	public DamageData clone() {
		try {
			DamageData clone = (DamageData) super.clone();
			clone.dropoff = new ArrayList<>(dropoff);
			return clone;
		} catch (CloneNotSupportedException exception) {
			throw new PluginException(exception);
		}
	}

}
