package org.luckyraven.bartizan.api.weapon.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.luckyraven.keystone.exception.PluginException;
import org.luckyraven.bartizan.api.weapon.ThrowableType;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ThrowableData implements Cloneable {

	private int     fuseTime;
	private double  explosionRadius;
	private int     explosionDamage;
	private int     fireTicks;
	private boolean bounces;
	private int     maxBounces;
	private boolean sticky;

	/**
	 * Behaviour discriminator. Defaults to {@link ThrowableType#EXPLOSIVE} when missing from yml so existing throwable
	 * configs keep their legacy detonation behaviour.
	 */
	private ThrowableType type;
	/**
	 * Potion effect tokens applied on detonation. Used by {@link ThrowableType#STUN} (instant on every entity in
	 * radius) and {@link ThrowableType#SMOKE} (re-applied periodically while the cloud lasts). Parsed via
	 * {@code PotionEffectParser}.
	 */
	private List<String>  effects;
	/**
	 * Lifetime of a smoke cloud in ticks. Only meaningful when {@link #type} is {@link ThrowableType#SMOKE}.
	 */
	private int           cloudDuration;
	/**
	 * Radius of a smoke cloud in blocks. Only meaningful when {@link #type} is {@link ThrowableType#SMOKE}; defaults to
	 * {@link #explosionRadius} when zero.
	 */
	private double        cloudRadius;
	/**
	 * Item visually shown on the airborne thrown entity. When {@code null}, falls back to the weapon's held material.
	 */
	@Nullable
	private ItemStack     displayItem;

	/**
	 * {@code Owner_Immunity} / {@code Ignore_Teams} (gate {@code HF}, §4) — same keys and meaning as a gun's
	 * {@code Damage.Owner_Immunity}/{@code Damage.Ignore_Teams}, read here directly under {@code Throw:} since
	 * throwables have no nested {@code Damage:} sub-block. {@code Owner_Immunity} defaults {@code false} so grenades
	 * keep self-damaging unless a weapon opts in.
	 */
	private boolean ownerImmunity;
	private boolean ignoreTeams;

	/**
	 * {@code Knockback} (gate {@code HF}, §6) — per victim, an additional
	 * {@code direction-from-centre * knockback * (1 - dist/radius)} vector added after the damage call. {@code 0}
	 * (default) adds nothing; the thrower's own existing blast knockback is unaffected by this field.
	 */
	private double knockback;

	@Override
	public ThrowableData clone() {
		try {
			ThrowableData clone = (ThrowableData) super.clone();
			if (effects != null) clone.effects = new ArrayList<>(effects);
			if (displayItem != null) clone.displayItem = displayItem.clone();
			return clone;
		} catch (CloneNotSupportedException exception) {
			throw new PluginException(exception);
		}
	}

}
