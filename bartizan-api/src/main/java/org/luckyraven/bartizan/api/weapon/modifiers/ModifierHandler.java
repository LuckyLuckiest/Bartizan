package org.luckyraven.bartizan.api.weapon.modifiers;

import com.cryptomorin.xseries.XAttribute;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.api.weapon.dto.ModifiersData;
import org.luckyraven.bartizan.api.weapon.modifiers.action.ArmorPiercingModifier;
import org.luckyraven.bartizan.api.weapon.modifiers.action.PenetrationModifier;
import org.luckyraven.bartizan.api.weapon.ProjectileState;

/**
 * Pure helpers for the weapon modifier pipeline. Damage / penetration math used by the unified {@code WeaponRaytracer};
 * reflection math, ricochet projectile spawning, and tracer-particle rendering have been moved into the raytracer
 * itself.
 */
public class ModifierHandler {

	/**
	 * Calculates final damage with armor piercing modifier applied.
	 *
	 * @param baseDamage The base damage amount
	 * @param target The target entity
	 * @param weapon The weapon used
	 *
	 * @return The final damage after armor calculations
	 */
	public static double calculateArmorPiercingDamage(double baseDamage, LivingEntity target, Weapon weapon) {
		ModifiersData modifiers = weapon.getModifiersData();

		if (!modifiers.hasArmorPiercing()) {
			return baseDamage;
		}

		ArmorPiercingModifier armorPiercing = modifiers.getArmorPiercing();

		// Get target's armor value using XAttribute for cross-version compatibility
		Attribute armorAttribute = XAttribute.ARMOR.get();
		if (armorAttribute == null) {
			return baseDamage;
		}

		AttributeInstance armorInstance = target.getAttribute(armorAttribute);
		if (armorInstance == null) {
			return baseDamage;
		}

		double armor          = armorInstance.getValue();
		double toughness      = readArmorToughness(target);
		double effectiveArmor = armorPiercing.calculateEffectiveArmor(armor);

		double intended = vanillaDamageAfterArmor(baseDamage, effectiveArmor, toughness);

		// living.damage() will re-run Minecraft's REAL armor formula against the target's un-pierced armor/
		// toughness on top of whatever we return here. BZ-RT-16: dividing by a single flat reduction constant (the
		// previous fix) overcorrects, because vanilla's actual reduction shrinks as the input damage grows
		// (vanillaDamageAfterArmor is quadratic in its damage argument, not linear) — so instead we solve for the
		// pre-armor damage that, once vanilla's real formula reduces it again, lands exactly `intended`.
		return solveForPreArmorDamage(intended, armor, toughness);
	}

	/**
	 * Reads the target's {@code Armor_Toughness} attribute via {@link XAttribute} for cross-version compatibility,
	 * defaulting to 0 (vanilla's own default) when the attribute or its instance is unavailable.
	 */
	private static double readArmorToughness(LivingEntity target) {
		Attribute toughnessAttribute = XAttribute.ARMOR_TOUGHNESS.get();
		if (toughnessAttribute == null) {
			return 0.0;
		}

		AttributeInstance toughnessInstance = target.getAttribute(toughnessAttribute);
		return toughnessInstance == null ? 0.0 : toughnessInstance.getValue();
	}

	/**
	 * Minecraft's real post-1.9 armor formula ({@code CombatRules.getDamageAfterAbsorb}) — not the flat
	 * {@code damage*(1-min(20,armor)/25)} model, which ignores toughness and stays constant regardless of how much
	 * damage is being reduced. The real reduction shrinks as {@code damage} grows.
	 */
	private static double vanillaDamageAfterArmor(double damage, double armor, double toughness) {
		double f = 2.0 + toughness / 4.0;
		double g = Math.max(armor * 0.2, Math.min(armor - damage / f, 20.0));
		return damage * (1.0 - g / 25.0);
	}

	/**
	 * Solves for the pre-armor damage {@code D} such that {@link #vanillaDamageAfterArmor} — applied with the
	 * target's real, un-bypassed {@code armor}/{@code toughness} — reduces {@code D} down to exactly
	 * {@code intended}. {@code vanillaDamageAfterArmor} is monotonic increasing in its damage argument, so a short
	 * bisection finds the inverse without needing a piecewise closed form for its clamp.
	 */
	private static double solveForPreArmorDamage(double intended, double armor, double toughness) {
		if (intended <= 0) {
			return 0.0;
		}

		// vanillaDamageAfterArmor(D) <= D always (reduction is never negative), so D must be >= intended.
		double low  = intended;
		double high = Math.max(intended * 5.0, 1.0);
		while (vanillaDamageAfterArmor(high, armor, toughness) < intended) {
			high *= 2;
		}

		for (int i = 0; i < 40; i++) {
			double mid = (low + high) / 2.0;
			if (vanillaDamageAfterArmor(mid, armor, toughness) < intended) {
				low = mid;
			} else {
				high = mid;
			}
		}
		return high;
	}

	/**
	 * Adds the flat damage bonus to the base damage.
	 *
	 * @param baseDamage The damage before the bonus
	 * @param weapon The weapon used
	 *
	 * @return The damage with the flat bonus added, or baseDamage unchanged if modifier is absent
	 */
	public static double applyFlatDamage(double baseDamage, Weapon weapon) {
		ModifiersData modifiers = weapon.getModifiersData();

		if (!modifiers.hasFlatDamage()) {
			return baseDamage;
		}

		return baseDamage + modifiers.getFlatDamage().bonus();
	}

	/**
	 * Handles entity penetration logic.
	 *
	 * @param state The projectile state
	 *
	 * @return true if the projectile should continue, false if it should stop
	 */
	public static boolean handleEntityPenetration(ProjectileState state) {
		if (!state.canPenetrateEntity()) {
			return false;
		}

		PenetrationModifier penetration = state.getWeapon().getModifiersData().getPenetration();

		// Increment penetration count
		state.setEntitiesPenetrated(state.getEntitiesPenetrated() + 1);

		// Apply damage reduction
		state.applyPenetrationReduction(penetration.damageReduction());

		// Entity budget only — a remaining block budget doesn't let the ray damage another entity past this one;
		// blocks are handled separately on the block path (handleBlockPenetration).
		return state.canPenetrateEntity();
	}

	/**
	 * Handles block penetration logic.
	 *
	 * @param state The projectile state
	 * @param hitBlock The block that was hit
	 *
	 * @return true if the projectile should continue through the block
	 */
	public static boolean handleBlockPenetration(ProjectileState state, Block hitBlock) {
		if (!state.canPenetrateBlock()) {
			return false;
		}

		PenetrationModifier penetration = state.getWeapon().getModifiersData().getPenetration();

		// Check if block is penetrable (non-solid or thin blocks)
		if (!isPenetrableBlock(hitBlock.getType())) {
			return false;
		}

		// Increment penetration count
		state.setBlocksPenetrated(state.getBlocksPenetrated() + 1);

		// Apply damage reduction
		state.applyPenetrationReduction(penetration.damageReduction());

		return true;
	}

	/**
	 * Checks if a block can be penetrated by projectiles.
	 */
	private static boolean isPenetrableBlock(Material material) {
		String name = material.name();

		// Glass and thin blocks
		if (name.contains("GLASS") || name.contains("PANE")) return true;
		if (name.contains("LEAVES")) return true;
		if (name.contains("FENCE") && !name.contains("GATE")) return true;
		if (name.contains("BARS")) return true;
		if (name.contains("CHAIN")) return true;
		if (name.contains("CARPET")) return true;
		if (name.contains("BANNER")) return true;
		if (name.contains("SIGN")) return true;
		if (name.contains("CANDLE")) return true;
		if (name.contains("FLOWER") || name.contains("PLANT") || name.contains("GRASS")) return true;
		if (name.contains("VINE") || name.contains("MOSS")) return true;

		return switch (material) {
			case COBWEB, SNOW, SUGAR_CANE, BAMBOO, SCAFFOLDING, LADDER -> true;
			default -> false;
		};
	}

}
