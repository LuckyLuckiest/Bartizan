package org.luckyraven.bartizan.api.weapon.dto;

/**
 * {@code Scope.Type} (weapons-roadmap.md gate {@code HP}): which mechanism a weapon's scope uses.
 */
public enum ScopeType {

	/**
	 * Today's behaviour (default): a SLOWNESS potion amplifier, nothing else. Works on every server version and
	 * every {@code Information.Material}.
	 */
	SLOWNESS,

	/**
	 * Guns only, 1.17+: the held item's vanilla spyglass "use" drives the client's own zoom, raised-arm pose,
	 * movement slowdown and scope overlay for free - no packets, no NMS. Requires
	 * {@code Information.Material: SPYGLASS}; falls back to {@link #SLOWNESS} (with a loader warning) on an older
	 * server.
	 */
	SPYGLASS

}
