package org.luckyraven.bartizan.api.raytrace;

import lombok.Getter;
import lombok.Setter;
import org.bukkit.Location;
import org.bukkit.util.Vector;
import org.luckyraven.bartizan.api.weapon.ProjectileState;

import java.util.ArrayList;
import java.util.List;

/**
 * Mutable per-shot working state for {@link WeaponRaytracer}. Holds the live ray position and direction along with the
 * iteration counters and accumulated tracer points.
 * <p>
 * For instant hitscan shots, this lives on the stack inside {@code WeaponRaytracer.fireInstant}. For stepped slow
 * projectiles (rockets, throwables) it lives on the heap, owned by the {@code SteppedProjectileTask} that drives the
 * visual entity, so penetration / ricochet counters persist across ticks.
 */
@Getter
@Setter
public class RaytraceContext {

	private final RaytraceRequest request;
	private final ProjectileState state;
	private final List<Location>  tracerSegments;

	private Location currentOrigin;
	private Vector   currentDir;
	private double   remaining;
	private int      iterations;
	/**
	 * Set by {@code WeaponRaytracerImpl.handleEntityImpact} the moment a ray actually strikes an entity. Read by
	 * {@code WeaponRaytracerImpl.fireInstant} after the ray finishes to decide whether {@code EffectHook.ON_MISS}
	 * should fire (weapons-roadmap.md gate {@code HA}, §1). Sticky for the whole flight and set on every hit —
	 * penetrating or not — so it is unsuitable for deciding when a stepped projectile's flight should end; see
	 * {@link #terminalHit} for that.
	 */
	private boolean  hitEntity;
	/**
	 * Set by {@code WeaponRaytracerImpl.advanceRay} only when an entity hit does NOT penetrate (the ray's remaining
	 * distance is zeroed for good). Unlike {@link #hitEntity} — which a penetrating hit also sets — this is the
	 * correct flag for {@code SteppedProjectileTask} to terminate a rocket/flare's flight on, so
	 * {@code Modifiers.Penetration}'s {@code Pierce_Entities} keeps the projectile flying through a penetrating hit
	 * (HI-b review #4).
	 */
	private boolean  terminalHit;

	public RaytraceContext(RaytraceRequest request, ProjectileState state) {
		this.request        = request;
		this.state          = state;
		this.tracerSegments = new ArrayList<>();
		this.currentOrigin  = request.getOrigin().clone();
		this.currentDir     = request.getDirection().clone().normalize();
		this.remaining      = request.getMaxDistance();
		this.iterations     = 0;
	}

}
