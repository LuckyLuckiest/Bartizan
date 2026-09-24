package org.luckyraven.bartizan.api.weapon.spread;

import lombok.Getter;
import org.bukkit.util.Vector;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.api.weapon.dto.SpreadData;

import java.util.Random;

/**
 * Manages weapon spread mechanics including spread accumulation, reset, and bounds.
 */
public class SpreadManager {

	private final Random random;
	private final Weapon weapon;

	@Getter
	private double currentSpread;
	private long   lastShotTime;

	public SpreadManager(Weapon weapon) {
		this(weapon, new Random());
	}

	/**
	 * Test-only seam: inject a fixed/seeded {@link Random} so {@code SpreadManagerTest} can pin the
	 * multiplier-scaling arithmetic deterministically with a non-zero {@code currentSpread}, instead of relying
	 * on {@code currentSpread == 0} to cancel the randomness out (which proves nothing about the multiplier).
	 */
	SpreadManager(Weapon weapon, Random random) {
		this.random = random;
		this.weapon = weapon;
		SpreadData data = weapon.getSpreadData();
		this.currentSpread = data != null ? data.getStart() : 0.0;
		this.lastShotTime  = System.currentTimeMillis();
	}

	/**
	 * Applies spread to the given direction vector.
	 *
	 * @param originalVector The original direction vector
	 *
	 * @return The vector with spread applied
	 */
	public Vector applySpread(Vector originalVector) {
		return applySpread(originalVector, 1.0);
	}

	/**
	 * Applies spread to the given direction vector, scaling {@link #currentSpread} by {@code multiplier} before
	 * sampling the random offset — the caller (e.g. {@code WeaponShooting.fireHitscan}) computes this from
	 * {@code Spread.Modify_Spread_When} (zooming/sneaking/sprinting/midair/swimming).
	 *
	 * @param originalVector The original direction vector
	 * @param multiplier Scales the effective spread for this shot only; {@code currentSpread} itself still
	 * 		accumulates/resets by the unscaled value so the multiplier never distorts the weapon's own bloom curve
	 *
	 * @return The vector with spread applied
	 */
	public Vector applySpread(Vector originalVector, double multiplier) {
		SpreadData spreadData = weapon.getSpreadData();
		if (spreadData == null) return originalVector;

		checkSpreadReset(spreadData);

		double effectiveSpread = currentSpread * multiplier;
		double offsetX = (random.nextDouble() - 0.5) * effectiveSpread;
		double offsetY = (random.nextDouble() - 0.5) * effectiveSpread;
		double offsetZ = (random.nextDouble() - 0.5) * effectiveSpread;

		updateSpread(spreadData);

		return originalVector.add(new Vector(offsetX, offsetY, offsetZ)).normalize();
	}

	/**
	 * Manually resets the spread to its starting value.
	 */
	public void resetSpread() {
		SpreadData spreadData = weapon.getSpreadData();
		if (spreadData == null) return;

		this.currentSpread = spreadData.getStart();
		this.lastShotTime  = System.currentTimeMillis();
	}

	/**
	 * Checks if spread should reset based on the spreadResetTime.
	 */
	private void checkSpreadReset(SpreadData spreadData) {
		long currentTime       = System.currentTimeMillis();
		long timeSinceLastShot = currentTime - lastShotTime;

		// SpreadData.resetTime is authored in YAML as ticks (Time: 5), not milliseconds (BZ-WM-02).
		if (timeSinceLastShot >= spreadData.getResetTime() * 50L) { // 50ms/tick
			currentSpread = spreadData.getStart();
		}

		lastShotTime = currentTime;
	}

	/**
	 * Updates the spread value after a shot is fired.
	 */
	private void updateSpread(SpreadData spreadData) {
		double newSpread = currentSpread + spreadData.getChangeBase();

		if (newSpread >= spreadData.getBoundMaximum()) {
			currentSpread = spreadData.isResetOnBound() ? spreadData.getStart() : spreadData.getBoundMaximum();
		} else if (newSpread <= spreadData.getBoundMinimum()) {
			currentSpread = spreadData.isResetOnBound() ? spreadData.getStart() : spreadData.getBoundMinimum();
		} else {
			currentSpread = newSpread;
		}
	}
}
