package org.luckyraven.bartizan.api.weapon.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.luckyraven.keystone.exception.PluginException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RecoilData implements Cloneable {

	private double         amount;
	private double         pushVelocity;
	private double         pushPowerUp;
	private List<String[]> pattern = new ArrayList<>();
	/**
	 * {@code Recoil.Random}: gaussian recoil instead of a fixed {@link #pattern}. {@code null} when unconfigured.
	 * Checked by {@code RecoilManager.applyRecoil} before {@link #pattern} — configuring both is a
	 * {@code ConfigReport} warning and {@code Random} wins.
	 */
	private RecoilRandom   random;

	/**
	 * @param meanX gaussian mean for the yaw kick
	 * @param meanY gaussian mean for the pitch kick
	 * @param varianceX gaussian standard deviation (sigma) for the yaw kick — despite the "variance" name
	 * 		inherited from the config key, this value is used directly as sigma (not squared/rooted), matching
	 * 		WM's 1:1 {@code Variance_X} -> sigma mapping
	 * @param varianceY gaussian standard deviation (sigma) for the pitch kick, same convention as {@code varianceX}
	 */
	public record RecoilRandom(double meanX, double meanY, double varianceX, double varianceY) {
	}

	@Override
	public RecoilData clone() {
		RecoilData recoilData;

		try {
			recoilData = (RecoilData) super.clone();
		} catch (CloneNotSupportedException exception) {
			throw new PluginException(exception);
		}

		List<String[]> patternCopy = new ArrayList<>();

		for (String[] arr : pattern) {
			patternCopy.add(arr.clone());
		}

		recoilData.setPattern(patternCopy);

		return recoilData;
	}

	@Override
	public String toString() {
		return String.format("RecoilConfig{amount=%.2f,pushVelocity=%.2f,pushPowerUp=%.2f,pattern=%s,random=%s}",
		                     amount, pushVelocity, pushPowerUp, pattern.stream().map(Arrays::toString).toList(),
		                     random);
	}

}
