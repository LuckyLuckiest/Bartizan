package org.luckyraven.bartizan.api.weapon.recoil;

import org.bukkit.entity.Player;
import org.luckyraven.keystone.exception.PluginException;
import org.luckyraven.keystone.nms.PacketBridge;
import org.luckyraven.bartizan.api.weapon.Weapon;

import java.util.List;

public class RecoilManager {

	private final Weapon weapon;

	private int playerPatternIndex;

	public RecoilManager(Weapon weapon) {
		this.weapon = weapon;

		this.playerPatternIndex = 0;
	}

	public void resetRecoilPattern() {
		playerPatternIndex = 0;
	}

	public void applyRecoil(Player player) {
		if (weapon.getRecoilData() == null) return;
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
