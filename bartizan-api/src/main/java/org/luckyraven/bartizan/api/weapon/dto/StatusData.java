package org.luckyraven.bartizan.api.weapon.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jetbrains.annotations.Nullable;
import org.luckyraven.keystone.exception.PluginException;

import java.util.List;

/**
 * The tracked "status" (infection, radiation, whatever the weapon names it) a biological weapon's hit applies.
 * The potion payload from {@code Shoot.Effects_Per_Level} is what a status <i>does</i>; this is what the
 * victim/shooter <i>see</i> and what kill credit is attached to — owned at runtime by {@code StatusEffectService}
 * (weapons-roadmap.md gate {@code HB}, §2).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StatusData implements Cloneable {

	private String        name;
	private String        icon;
	private int           durationPerLevel;
	private Stacking      stacking;
	private int           maxLevel;
	private int           killCreditWindow;
	@Nullable
	private ContagionData contagion;
	private CureData      cure;
	private BossBarData   bossBar;
	@Nullable
	private String        ambientParticle;
	@Nullable
	private String        ambientColor;
	private int           ambientInterval;
	@Nullable
	private String        messageSpread;

	public enum Stacking {
		REFRESH,
		EXTEND,
		ESCALATE,
		IGNORE
	}

	/**
	 * @param radius blocks around the carrier a nearby player is rolled against.
	 * @param chance per-{@code interval} roll chance, in {@code [0, 1]}.
	 * @param interval ticks between contagion rolls.
	 * @param levelDrop levels subtracted from the carrier's level when the status spreads (floored at 1).
	 */
	public record ContagionData(double radius, double chance, int interval, int levelDrop) {
	}

	/**
	 * @param items material names that cure the status when consumed ({@code PlayerItemConsumeEvent}).
	 * @param wearableTrait a worn-wearable trait key that reduces the incoming level before it is ever applied.
	 */
	public record CureData(List<String> items, @Nullable String wearableTrait) {

		public CureData {
			items = items == null ? List.of() : List.copyOf(items);
		}

	}

	/**
	 * @param text boss bar title template — {@code %icon% %status% %level% %seconds%} placeholders, formatted by
	 * 		{@code StatusEffectService}.
	 * @param color an {@code org.bukkit.boss.BarColor} name, resolved in the plugin.
	 * @param style an {@code org.bukkit.boss.BarStyle} name, resolved in the plugin.
	 */
	public record BossBarData(String text, String color, String style) {
	}

	@Override
	public StatusData clone() {
		try {
			// contagion/cure/bossBar are immutable records — safe to share the reference across the shallow copy.
			return (StatusData) super.clone();
		} catch (CloneNotSupportedException exception) {
			throw new PluginException(exception);
		}
	}

}
