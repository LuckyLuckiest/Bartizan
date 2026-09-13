package org.luckyraven.bartizan.effect.impl;

import com.cryptomorin.xseries.particles.XParticle;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.luckyraven.bartizan.api.weapon.dto.EffectSpec;
import org.luckyraven.bartizan.effect.Effect;
import org.luckyraven.bartizan.effect.EffectContext;

/**
 * {@code Particle}: {@code Particle, Count (1), Offset ("0 0 0"), Speed (0), At (source), Color (#RRGGBB, DUST
 * only)}, resolved cross-version through {@link XParticle}.
 */
public class ParticleHookEffect implements Effect {

	@Override
	public void run(EffectSpec spec, EffectContext ctx) {
		String particleName = spec.arg("Particle");
		if (particleName == null) return;

		Particle particle = XParticle.of(particleName).map(XParticle::get).orElse(null);
		if (particle == null) return;

		Location location = ctx.at(spec.arg("At", "source"));
		if (location == null || location.getWorld() == null) return;

		int      count  = spec.intArg("Count", 1);
		double[] offset = parseOffset(spec.arg("Offset", "0 0 0"));
		double   speed  = spec.doubleArg("Speed", 0);
		Object   data   = "DUST".equalsIgnoreCase(particleName.trim()) ? dustOptions(spec) : null;

		location.getWorld().spawnParticle(particle, location, count, offset[0], offset[1], offset[2], speed, data);
	}

	private double[] parseOffset(String raw) {
		String[] parts  = raw.trim().split("\\s+");
		double[] values = new double[3];

		for (int i = 0; i < 3 && i < parts.length; i++) {
			try {
				values[i] = Double.parseDouble(parts[i]);
			} catch (NumberFormatException ignored) { }
		}
		return values;
	}

	private Particle.DustOptions dustOptions(EffectSpec spec) {
		String hex = spec.arg("Color", "#FFFFFF").replace("#", "").trim();
		try {
			return new Particle.DustOptions(Color.fromRGB(Integer.parseInt(hex, 16)), 1.0F);
		} catch (NumberFormatException exception) {
			return new Particle.DustOptions(Color.WHITE, 1.0F);
		}
	}

}
