package org.luckyraven.bartizan.configuration.parser;

import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.jetbrains.annotations.Nullable;
import org.luckyraven.keystone.persistence.config.ConfigNode;
import org.luckyraven.keystone.persistence.config.ConfigReport;
import org.luckyraven.keystone.persistence.config.MappingNode;
import org.luckyraven.keystone.persistence.config.NodeReader;
import org.luckyraven.keystone.persistence.config.Severity;
import org.luckyraven.bartizan.api.weapon.dto.HudData;

import java.util.Locale;

/**
 * Parses a weapon's {@code HUD:} section into a {@link HudData} (weapons-roadmap.md gate {@code HD}) — category
 * agnostic, applied from {@code WeaponAddon.registerWeapon} like {@code EffectsSectionParser}. Every key is
 * optional; a bad {@code Boss_Bar.Color}/{@code Style} is a {@link Severity#WARNING}, falling back to
 * {@code WHITE}/{@code SOLID} rather than failing the whole file.
 */
public final class HudSectionParser {

	private HudSectionParser() {
	}

	/**
	 * @param hudSection the {@code HUD:} mapping reader, or {@code null} when the section is absent.
	 *
	 * @return the parsed data, or {@code null} when the section is absent — {@code Weapon#getHudData() == null}
	 * 		means the weapon shows no HUD at all.
	 */
	@Nullable
	public static HudData parse(@Nullable NodeReader hudSection, ConfigReport report) {
		if (hudSection == null) return null;

		String actionBar = hudSection.get("Action_Bar").asString().orNull();

		HudData.BossBarData bossBar        = null;
		MappingNode          bossBarSection = hudSection.get("Boss_Bar").asMapping().orNull();
		if (bossBarSection != null) {
			NodeReader bar = NodeReader.of(bossBarSection, report);

			String    title = bar.get("Title").asString().orDefault("");
			BarColor  color = parseColor(bar, "Color", report);
			BarStyle  style = parseStyle(bar, "Style", report);

			bossBar = new HudData.BossBarData(title, color, style);
		}

		boolean reloadItemCooldown = hudSection.get("Reload_Item_Cooldown").asBool().orDefault(false);

		return new HudData(actionBar, bossBar, reloadItemCooldown);
	}

	private static BarColor parseColor(NodeReader parent, String key, ConfigReport report) {
		String raw = parent.get(key).asString().orNull();
		if (raw == null) return BarColor.WHITE;

		try {
			return BarColor.valueOf(raw.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException exception) {
			warnBadEnum(parent, key, raw, "color", report);
			return BarColor.WHITE;
		}
	}

	private static BarStyle parseStyle(NodeReader parent, String key, ConfigReport report) {
		String raw = parent.get(key).asString().orNull();
		if (raw == null) return BarStyle.SOLID;

		try {
			return BarStyle.valueOf(raw.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException exception) {
			warnBadEnum(parent, key, raw, "style", report);
			return BarStyle.SOLID;
		}
	}

	private static void warnBadEnum(NodeReader parent, String key, String raw, String kind, ConfigReport report) {
		ConfigNode node = parent.get(key).node();
		report.add(Severity.WARNING, node != null ? node.location() : parent.mapping().location(),
		           childPath(parent, key), "unknown boss bar " + kind + " '" + raw + "'", "hud.unknown_" + kind);
	}

	private static String childPath(NodeReader parent, String key) {
		String parentPath = parent.mapping().path();
		return parentPath == null || parentPath.isEmpty() ? key : parentPath + "." + key;
	}

}
