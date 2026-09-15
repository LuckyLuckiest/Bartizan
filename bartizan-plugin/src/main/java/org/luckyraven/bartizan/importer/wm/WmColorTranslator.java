package org.luckyraven.bartizan.importer.wm;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Translates WeaponMechanics' MiniMessage-flavoured text (WM's {@code <gold>Title</gold>}-style tags) into
 * Bartizan's {@code &}-code convention (weapons-roadmap.md gate {@code HM}, §6.2). Named colours and the format
 * tags map to their legacy code; a hex tag ({@code <#RRGGBB>}) becomes {@code &#RRGGBB} — Keystone's
 * {@code ChatUtil.color} already expands that form, so no "nearest legacy colour" fallback is needed. Any other
 * tag (hover/click/gradient/rainbow/... and every closing tag, since Bartizan colours aren't scoped) is stripped.
 */
public final class WmColorTranslator {

	private static final Pattern TAG = Pattern.compile("<([^<>]+)>");

	private static final Pattern HEX = Pattern.compile("^#([0-9A-Fa-f]{6})$");

	private static final Map<String, String> NAMED = Map.ofEntries(
			Map.entry("black", "&0"), Map.entry("dark_blue", "&1"), Map.entry("dark_green", "&2"),
			Map.entry("dark_aqua", "&3"), Map.entry("dark_red", "&4"), Map.entry("dark_purple", "&5"),
			Map.entry("gold", "&6"), Map.entry("gray", "&7"), Map.entry("grey", "&7"),
			Map.entry("dark_gray", "&8"), Map.entry("dark_grey", "&8"), Map.entry("blue", "&9"),
			Map.entry("green", "&a"), Map.entry("aqua", "&b"), Map.entry("red", "&c"),
			Map.entry("light_purple", "&d"), Map.entry("pink", "&d"), Map.entry("yellow", "&e"),
			Map.entry("white", "&f"), Map.entry("bold", "&l"), Map.entry("b", "&l"),
			Map.entry("italic", "&o"), Map.entry("i", "&o"), Map.entry("em", "&o"),
			Map.entry("underlined", "&n"), Map.entry("u", "&n"),
			Map.entry("strikethrough", "&m"), Map.entry("st", "&m"),
			Map.entry("obfuscated", "&k"), Map.entry("obf", "&k"), Map.entry("reset", "&r"));

	private WmColorTranslator() {
	}

	/**
	 * @param miniMessage WM's MiniMessage-lite text, or {@code null}.
	 *
	 * @return the {@code &}-coded translation ({@code null} in, {@code null} out); unresolvable tags are dropped
	 * 		(their bracketed text removed, the rest of the line kept).
	 */
	public static String translate(String miniMessage) {
		if (miniMessage == null) return null;

		Matcher       matcher = TAG.matcher(miniMessage);
		StringBuilder out     = new StringBuilder();
		int           last    = 0;

		while (matcher.find()) {
			out.append(miniMessage, last, matcher.start());
			last = matcher.end();

			String inner = matcher.group(1).trim();
			// closing tags (</gold>) carry no Bartizan equivalent - Bartizan colours aren't scoped - drop silently.
			if (inner.startsWith("/")) continue;

			Matcher hex = HEX.matcher(inner);
			if (hex.matches()) {
				out.append("&#").append(hex.group(1));
				continue;
			}

			String code = NAMED.get(inner.toLowerCase(Locale.ROOT));
			if (code != null) out.append(code);
			// else: unknown tag (hover:/click:/gradient/rainbow/...) - stripped.
		}
		out.append(miniMessage, last, miniMessage.length());

		return out.toString();
	}

}
