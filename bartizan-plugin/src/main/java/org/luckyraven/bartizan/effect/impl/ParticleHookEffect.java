package org.luckyraven.bartizan.effect.impl;

import com.cryptomorin.xseries.particles.XParticle;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.jetbrains.annotations.Nullable;
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
		Object   data   = particle.getDataType() == Particle.DustOptions.class ? dustOptions(spec) : null;

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
		return dustOptions(spec.arg("Color", "#FFFFFF"));
	}

	/**
	 * Parses a {@code #RRGGBB} (or bare {@code RRGGBB}) hex string into a {@code DUST}-family particle's data,
	 * falling back to white on a missing/malformed value. Shared with {@code StatusEffectService}'s ambient
	 * particle, which needs the same hex parsing for its own {@code Ambient_Color}.
	 */
	public static Particle.DustOptions dustOptions(@Nullable String hex) {
		return new Particle.DustOptions(parseColor(hex), 1.0F);
	}

	/**
	 * The {@link #dustOptions(String)} color, unwrapped — used for particles whose data type is a bare
	 * {@link Color} (e.g. {@code ENTITY_EFFECT} on 1.20.5+) rather than {@code DustOptions}.
	 */
	public static Color parseColor(@Nullable String hex) {
		String cleaned = (hex == null ? "FFFFFF" : hex.replace("#", "")).trim();
		try {
			return Color.fromRGB(Integer.parseInt(cleaned, 16));
		} catch (NumberFormatException exception) {
			return Color.WHITE;
		}
	}

}
