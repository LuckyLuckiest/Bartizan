package org.luckyraven.bartizan.api.weapon.recoil;

import org.bukkit.entity.Player;
import org.luckyraven.keystone.exception.PluginException;
import org.luckyraven.keystone.nms.PacketBridge;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.api.weapon.dto.RecoilData;

import java.util.List;
import java.util.Random;

public class RecoilManager {

	private final Weapon weapon;
	private final Random random;

	private int playerPatternIndex;

	public RecoilManager(Weapon weapon) {
		this(weapon, new Random());
	}

	/**
	 * Test-only seam: inject a fixed {@link Random} so {@code RecoilManagerTest} can pin
	 * {@link #applyRecoil(Player)}'s gaussian ({@code Recoil.Random}) branch.
	 */
	RecoilManager(Weapon weapon, Random random) {
		this.weapon = weapon;
		this.random = random;

		this.playerPatternIndex = 0;
	}

	public void resetRecoilPattern() {
		playerPatternIndex = 0;
	}

	public void applyRecoil(Player player) {
		if (weapon.getRecoilData() == null) return;
		RecoilData.RecoilRandom randomConfig = weapon.getRecoilData().getRandom();
		if (randomConfig != null) {
			applyRandomRecoil(player, randomConfig);
			return;
		}

		List<String[]> recoilPattern = weapon.getRecoilData().getPattern();

		// Check if a recoil pattern is available and not empty
		if (!(recoilPattern != null && !recoilPattern.isEmpty())) {
			applyDefaultRecoil(player, weapon);
			return;
		}

		// Get the current pattern index
		int currentIndex = playerPatternIndex;

		// Get the current pattern entry
		String[] patternEntry = recoilPattern.get(currentIndex);

		try {
			// Parse yaw and pitch from the pattern
			float patternYaw   = Float.parseFloat(patternEntry[0]);
			float patternPitch = Float.parseFloat(patternEntry[1]);

			// Apply modifiers based on sneaking/scoping
			float finalYaw   = patternYaw;
			float finalPitch = patternPitch;

			if (player.isSneaking()) {
				if (weapon.getScopeData() != null && weapon.getScopeData().isScoped()) {
					finalYaw /= 2;
					finalPitch /= 2;
				} else {
					finalYaw /= 4;
					finalPitch /= 4;
				}
			}

			// Apply the recoil
			recoil(player, finalYaw, finalPitch);

			// Move to the next pattern index (loop back to 0 if at the end)
			playerPatternIndex = (currentIndex + 1) % recoilPattern.size();

		} catch (NumberFormatException | ArrayIndexOutOfBoundsException exception) {
			// Fallback to default recoil if pattern parsing fails
			applyDefaultRecoil(player, weapon);
		}
	}

	@Override
	public RecoilManager clone() {
		try {
			return (RecoilManager) super.clone();
		} catch (CloneNotSupportedException exception) {
			throw new PluginException(exception);
		}
	}

	@Override
	public String toString() {
		return "Recoil{playerPatternIndex=" + playerPatternIndex + "}";
	}

	/**
	 * {@code Recoil.Random}: gaussian yaw/pitch kick instead of a fixed pattern step, using {@link #random} so the
	 * distribution is pinnable in tests. Keeps the same sneak/scope dampening as the pattern branch.
	 */
	private void applyRandomRecoil(Player player, RecoilData.RecoilRandom randomConfig) {
		float yaw   = (float) gaussian(randomConfig.meanX(), randomConfig.varianceX());
		float pitch = (float) gaussian(randomConfig.meanY(), randomConfig.varianceY());

		float finalYaw   = yaw;
		float finalPitch = pitch;

		if (player.isSneaking()) {
			if (weapon.getScopeData() != null && weapon.getScopeData().isScoped()) {
				finalYaw /= 2;
				finalPitch /= 2;
			} else {
				finalYaw /= 4;
				finalPitch /= 4;
			}
		}

		recoil(player, finalYaw, finalPitch);
	}

	/**
	 * {@code Recoil.Random.Variance_X/Y} is used directly as the gaussian standard deviation (sigma), not
	 * squared/rooted first — matches the roadmap's 1:1 {@code Variance_X} -> sigma mapping (WM parity), despite
	 * the historical "variance" name in {@link RecoilData.RecoilRandom}.
	 */
	private double gaussian(double mean, double variance) {
		return mean + random.nextGaussian() * Math.max(0.0, variance);
	}

	private void applyDefaultRecoil(Player player, Weapon weapon) {
		float recoil = (float) weapon.getRecoilData().getAmount();

		if (!player.isSneaking()) recoil(player, recoil, recoil);
		else {
			float newValue = recoil / 2;

			if (weapon.getScopeData() != null && weapon.getScopeData().isScoped()) recoil(player, newValue,
			                                                                              newValue);
			else recoil(player, newValue / 2, newValue / 2);
		}
	}

	private void recoil(Player player, float yaw, float pitch) {
		PacketBridge.adapter().relativeCameraRotation(player, -yaw + 1, pitch - 1);
	}
}
