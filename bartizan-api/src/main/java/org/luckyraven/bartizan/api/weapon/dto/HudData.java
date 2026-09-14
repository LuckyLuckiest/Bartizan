package org.luckyraven.bartizan.api.weapon.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.jetbrains.annotations.Nullable;
import org.luckyraven.keystone.exception.PluginException;

/**
 * A weapon's {@code HUD:} section (weapons-roadmap.md gate {@code HD}): a continuous action bar and/or boss bar
 * shown while the weapon is held, plus the vanilla item-cooldown overlay during a reload. All three are optional —
 * {@code null}/{@code false} means that piece of the HUD is not shown. {@code null} on {@link
 * org.luckyraven.bartizan.api.weapon.Weapon#getHudData()} itself means the weapon has no {@code HUD:} block at all.
 *
 * <p>{@code Boss_Bar.Color}/{@code Style} are plain Bukkit {@link BarColor}/{@link BarStyle} — stable enums, so
 * unlike {@link StatusData.BossBarData} (which keeps them as unvalidated strings resolved later in the plugin) they
 * are validated once, at parse time, by {@code HudSectionParser} — a bad value is a {@code ConfigReport} warning
 * that falls back to {@code WHITE}/{@code SOLID}.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class HudData implements Cloneable {

	@Nullable
	private String      actionBar;
	@Nullable
	private BossBarData bossBar;
	private boolean     reloadItemCooldown;

	/**
	 * @param title boss bar title template, resolved through {@code WeaponPlaceholders} on every HUD tick.
	 */
	public record BossBarData(String title, BarColor color, BarStyle style) {
	}

	@Override
	public HudData clone() {
		try {
			// bossBar is an immutable record - safe to share the reference across the shallow copy.
			return (HudData) super.clone();
		} catch (CloneNotSupportedException exception) {
			throw new PluginException(exception);
		}
	}

}
