package org.luckyraven.bartizan.effect.impl;

import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.api.weapon.dto.EffectSpec;
import org.luckyraven.bartizan.effect.Effect;
import org.luckyraven.bartizan.effect.EffectContext;

/**
 * {@code Cooldown}: {@code Ticks, Target (source)} — {@link Player#setCooldown} on the weapon's item material,
 * showing the vanilla item-cooldown overlay. Player-only; a no-op for a context with no weapon (e.g. a wearable's
 * {@code Effects:}, weapons-roadmap.md gate {@code HL}), since there is no item material to key the cooldown on.
 */
public class CooldownHookEffect implements Effect {

	@Override
	public void run(EffectSpec spec, EffectContext ctx) {
		int ticks = spec.intArg("Ticks", 0);
		if (ticks <= 0) return;

		Weapon weapon = ctx.getWeapon();
		if (weapon == null) return;

		Material material = weapon.getMaterial();
		String   target   = spec.arg("Target", "source");

		for (LivingEntity entity : ctx.targets(target, spec.doubleArg("Radius", 0))) {
			if (entity instanceof Player player) player.setCooldown(material, ticks);
		}
	}

}
