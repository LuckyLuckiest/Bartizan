package org.luckyraven.bartizan.raytrace;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.MainHand;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.api.weapon.dto.MuzzleOffsetData;

/**
 * Shared muzzle-position helper. Computes the world-space "weapon position" — the point from which a weapon's
 * hit-detection ray (or visual projectile) originates — given a shooter and an aim direction.
 * <p>
 * The offset places the muzzle on the right side of the shooter's body, slightly below eye level, matching the visual
 * position of a held gun. Every weapon action (gun, incendiary, biological, melee, throwable) calls this helper so the
 * muzzle is identical across weapon types.
 */
public final class WeaponMuzzle {

	/**
	 * Lateral offset from the shooter's centerline along the right-hand cross product. Also the {@code Right_Hand}
	 * default when {@code Shoot.Muzzle_Offset} is unconfigured.
	 */
	public static final double RIGHT_OFFSET = 0.3;

	/**
	 * Vertical offset below the eye location to approximate hand height. Also the default "up" component of
	 * {@code Shoot.Muzzle_Offset} when unconfigured.
	 */
	public static final double DOWN_OFFSET = -0.2;

	private WeaponMuzzle() {
	}

	/**
	 * Computes the muzzle location for a shooter aiming in the given direction, consulting {@code weapon}'s
	 * {@code Shoot.Muzzle_Offset} (parsed into {@link MuzzleOffsetData}) when configured: {@code Scope} while the
	 * weapon is scoped, else {@code Left_Hand}/{@code Right_Hand} by the shooter's main hand. A {@code null}
	 * weapon, or one with no {@code Muzzle_Offset:} section, falls back to {@link #RIGHT_OFFSET}/
	 * {@link #DOWN_OFFSET} with no forward offset — identical to the historical behaviour.
	 * <p>
	 * The hitscan ray itself still originates at the shooter's eye (crosshair accuracy) — this offset only moves
	 * where the tracer/particle visuals and the slow-projectile (ROCKET/FLARE) spawn point appear.
	 *
	 * @param shooter The entity firing the weapon
	 * @param direction The aim direction (does not need to be normalised)
	 * @param weapon The weapon being fired, or {@code null} to always use the hardcoded default
	 *
	 * @return A new {@link Location} at the muzzle position, in the shooter's world
	 */
	public static Location compute(LivingEntity shooter, Vector direction, @Nullable Weapon weapon) {
		Location eye = shooter.getEyeLocation();

		double right   = RIGHT_OFFSET;
		double up      = DOWN_OFFSET;
		double forward = 0.0;

		MuzzleOffsetData offsets = weapon != null ? weapon.getMuzzleOffsetData() : null;
		if (offsets != null) {
			MuzzleOffsetData.Offset picked = pick(shooter, weapon, offsets);
			right   = picked.right();
			up      = picked.up();
			forward = picked.forward();
		}

		Vector rightVector = direction.clone().crossProduct(new Vector(0, 1, 0));

		// Degenerate case: looking straight up or down. Pick an arbitrary stable right vector.
		if (rightVector.lengthSquared() < 0.001) {
			rightVector = new Vector(1, 0, 0);
		}

		rightVector.normalize().multiply(right);

		Vector forwardVector = direction.clone().normalize().multiply(forward);

		return eye.clone().add(rightVector).add(forwardVector).add(0, up, 0);
	}

	private static MuzzleOffsetData.Offset pick(LivingEntity shooter, Weapon weapon, MuzzleOffsetData offsets) {
		if (weapon.getScopeData() != null && weapon.getScopeData().isScoped()) return offsets.scope();
		if (shooter instanceof Player player && player.getMainHand() == MainHand.LEFT) return offsets.leftHand();
		return offsets.rightHand();
	}

}
