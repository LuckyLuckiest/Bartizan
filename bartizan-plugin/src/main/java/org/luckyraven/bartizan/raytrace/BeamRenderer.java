package org.luckyraven.bartizan.raytrace;

import com.cryptomorin.xseries.particles.XParticle;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;
import org.luckyraven.bartizan.api.weapon.dto.BeamData;
import org.luckyraven.keystone.timer.RepeatingTimer;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws one fired beam shot: a core particle line along the axis plus a glow particle offset around it, redrawn
 * once per tick for {@code Render.Duration} ticks (weapons-roadmap.md gate {@code HC}, §3.2 "Render"). Constructed
 * fresh by {@code BeamAction} for each shot.
 */
public class BeamRenderer {

	public void render(JavaPlugin plugin, @Nullable World world, Location from, Location to,
	                   BeamData.RenderData render) {
		if (world == null) return;

		Particle core = XParticle.of(render.coreParticle()).map(XParticle::get).orElse(null);
		Particle glow = XParticle.of(render.glowParticle()).map(XParticle::get).orElse(null);
		if (core == null && glow == null) return;

		Particle.DustOptions dust = core != null && core.getDataType() == Particle.DustOptions.class
		                           ? dustOptions(render.coreColor(), render.thickness())
		                           : null;

		List<Location> axis = points(from, to, Math.max(0.05, render.step()));

		Vector direction = to.toVector().subtract(from.toVector());
		Vector perpA      = perpendicular(direction);
		Vector perpB      = direction.clone().normalize().crossProduct(perpA).normalize();

		new RepeatingTimer(plugin, 1L, timer -> {
			if (timer.getTickCount() >= render.duration()) {
				timer.stop();
				return;
			}

			for (int i = 0; i < axis.size(); i++) {
				Location point = axis.get(i);

				if (core != null) {
					world.spawnParticle(core, point, 1, 0, 0, 0, 0, dust);
				}

				if (glow != null) {
					Vector offset = (i % 2 == 0 ? perpA : perpB).clone().multiply(render.thickness());
					world.spawnParticle(glow, point.clone().add(offset), 1, 0, 0, 0, 0, null);
					world.spawnParticle(glow, point.clone().subtract(offset), 1, 0, 0, 0, 0, null);
				}
			}
		}).start(false);
	}

	/**
	 * Points spaced {@code step} blocks apart along the straight line from {@code from} to {@code to}, inclusive of
	 * both endpoints. Pure — no Bukkit world interaction — so it is unit-testable without a server, and public so
	 * {@code BeamAction}'s charge preview can reuse it too.
	 */
	public static List<Location> points(Location from, Location to, double step) {
		List<Location> result   = new ArrayList<>();
		double         distance = from.distance(to);

		if (step <= 0 || distance < 1e-6) {
			result.add(from.clone());
			if (distance >= 1e-6) result.add(to.clone());
			return result;
		}

		Vector direction = to.toVector().subtract(from.toVector()).normalize();
		for (double travelled = 0; travelled < distance; travelled += step) {
			result.add(from.clone().add(direction.clone().multiply(travelled)));
		}
		result.add(to.clone());

		return result;
	}

	private static Vector perpendicular(Vector direction) {
		Vector normalized = direction.clone().normalize();
		Vector arbitrary  = Math.abs(normalized.getY()) < 0.99 ? new Vector(0, 1, 0) : new Vector(1, 0, 0);
		return normalized.clone().crossProduct(arbitrary).normalize();
	}

	private static Particle.DustOptions dustOptions(String hexColor, double thickness) {
		String hex = hexColor == null ? "" : hexColor.replace("#", "").trim();
		try {
			return new Particle.DustOptions(Color.fromRGB(Integer.parseInt(hex, 16)), (float) (thickness * 2));
		} catch (IllegalArgumentException exception) {
			return new Particle.DustOptions(Color.WHITE, (float) (thickness * 2));
		}
	}

}
