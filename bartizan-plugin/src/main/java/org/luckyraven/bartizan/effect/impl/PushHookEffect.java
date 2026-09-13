package org.luckyraven.bartizan.effect.impl;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.util.Vector;
import org.luckyraven.bartizan.api.weapon.dto.EffectSpec;
import org.luckyraven.bartizan.effect.Effect;
import org.luckyraven.bartizan.effect.EffectContext;

/**
 * {@code Push}: {@code Strength (1.0), Direction (look|away, look), Target (source)} — velocity along the target's
 * own look vector, or away from the source's location.
 */
public class PushHookEffect implements Effect {

	@Override
	public void run(EffectSpec spec, EffectContext ctx) {
		double  strength = spec.doubleArg("Strength", 1.0);
		boolean away     = "away".equalsIgnoreCase(spec.arg("Direction", "look"));
		String  target   = spec.arg("Target", "source");

		for (LivingEntity entity : ctx.targets(target, spec.doubleArg("Radius", 0))) {
			Vector direction = away ? awayFromSource(ctx, entity) : entity.getLocation().getDirection();
			if (direction.lengthSquared() > 1.0E-6) direction.normalize();

			entity.setVelocity(entity.getVelocity().add(direction.multiply(strength)));
		}
	}

	private Vector awayFromSource(EffectContext ctx, LivingEntity entity) {
		Location sourceLocation = ctx.getSource() != null ? ctx.getSource().getLocation() : null;
		if (sourceLocation == null) return entity.getLocation().getDirection();

		Vector direction = entity.getLocation().toVector().subtract(sourceLocation.toVector());
		return direction.lengthSquared() > 1.0E-6 ? direction : entity.getLocation().getDirection();
	}

}
