package org.luckyraven.bartizan.effect.impl;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.luckyraven.keystone.nms.PacketBridge;
import org.luckyraven.bartizan.api.weapon.dto.EffectSpec;
import org.luckyraven.bartizan.effect.Effect;
import org.luckyraven.bartizan.effect.EffectContext;

/**
 * {@code Camera_Shake}: {@code Yaw, Pitch, Target (source)} — player-only, through the same
 * {@code PacketAdapter.relativeCameraRotation} path {@code RecoilManager} uses, resolved lazily via
 * {@link PacketBridge#adapter()} (degrades to a rough teleport if no packet adapter is installed).
 */
public class CameraShakeHookEffect implements Effect {

	@Override
	public void run(EffectSpec spec, EffectContext ctx) {
		float yaw   = (float) spec.doubleArg("Yaw", 0);
		float pitch = (float) spec.doubleArg("Pitch", 0);
		if (yaw == 0 && pitch == 0) return;

		String target = spec.arg("Target", "source");
		for (LivingEntity entity : ctx.targets(target, spec.doubleArg("Radius", 0))) {
			if (entity instanceof Player player) {
				PacketBridge.adapter().relativeCameraRotation(player, yaw, pitch);
			}
		}
	}

}
