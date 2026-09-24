package org.luckyraven.bartizan.importer.wm;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recursive-descent-ish parser + mapping table for WeaponMechanics' inline mechanics DSL
 * (weapons-roadmap.md gate {@code HM}, §6.3): {@code Name{key=value, ...} @Targeter{...} ?Condition{...}}.
 * {@link #parse(String)} turns one such string into a {@link ParsedMechanic}; {@link #translate(ParsedMechanic)}
 * maps the ~13 mechanic types Bartizan's {@code EffectRunner} understands onto their {@code EffectSpec} shape
 * (arg names taken from the real {@code *HookEffect} implementations, not just the roadmap's shorthand) and
 * returns {@code null} for everything else so the caller can emit a {@code # unmapped:} comment + report line.
 */
public final class WmMechanicsTranslator {

	/** Case-insensitive WM mechanic identifiers this translator has no Bartizan effect for. */
	private static final List<String> UNMAPPED_KNOWN = List.of(
			"damage", "blinding", "shockwave", "skybeam", "explosioncloud", "sculkbloom", "wardendisturbance",
			"fakeitem", "dropitem");

	private WmMechanicsTranslator() {
	}

	/**
	 * One parsed {@code Name{...} @Targeter{...} ?Cond{...}} mechanic string.
	 *
	 * @param type WM's mechanic identifier, verbatim (e.g. {@code "Sound"}, {@code "CustomSound"}).
	 * @param args the {@code key=value} pairs inside the main braces, keys/values verbatim (case preserved).
	 * @param target resolved Bartizan {@code Target} ({@code source|victim|nearby}), or {@code null} when neither
	 * 		a trailing {@code @Targeter{}} nor an inline {@code listeners=Source{}}/{@code listeners=Target{}} arg
	 * 		was present - the caller leaves the hook's own default in place by omitting the key.
	 * @param radius the {@code @World{range=N}} range, or {@code null}.
	 * @param conditions WM {@code ?Condition{...}} identifiers found after the targeter - recorded for the report,
	 * 		never applied (Bartizan's effect runner has no equivalent gating).
	 */
	public record ParsedMechanic(String type, Map<String, String> args, @Nullable String target,
	                             @Nullable Double radius, List<String> conditions) {
	}

	/** One translated Bartizan {@code Effects.<hook>} list entry, ready for {@code Type: <type>} + the args. */
	public record TranslatedEffect(String type, Map<String, String> args) {
	}

	private static final Pattern LEADING_IDENT = Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*)\\s*\\{");

	private static final Pattern NESTED_TARGETER_VALUE =
			Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*)\\{(.*)}$", Pattern.DOTALL);

	private static final Pattern TRAILING_TARGETER = Pattern.compile("^@([A-Za-z_][A-Za-z0-9_]*)\\s*\\{");

	private static final Pattern LEADING_CONDITION = Pattern.compile("^\\?([A-Za-z_][A-Za-z0-9_]*)\\s*\\{");

	private static final Pattern RANGE_ARG = Pattern.compile("range\\s*=\\s*([0-9.]+)");

	/**
	 * @return the parsed mechanic, or {@code null} when {@code raw} doesn't even start with an
	 * 		{@code Identifier{} } shape (malformed/empty entry - caller reports it and moves on).
	 */
	@Nullable
	public static ParsedMechanic parse(@Nullable String raw) {
		if (raw == null) return null;
		String text = raw.trim();

		Matcher identifier = LEADING_IDENT.matcher(text);
		if (!identifier.find()) return null;

		String type       = identifier.group(1);
		int    bodyStart   = identifier.end();
		int    bodyEndExcl = matchingBrace(text, bodyStart - 1);
		if (bodyEndExcl < 0) return null;

		String argsContent = text.substring(bodyStart, bodyEndExcl);
		String remainder   = text.substring(Math.min(bodyEndExcl + 1, text.length())).trim();

		Map<String, String> args           = new LinkedHashMap<>();
		String               listenerIdent = null;
		for (String pair : splitTopLevel(argsContent)) {
			int eq = pair.indexOf('=');
			if (eq < 0) continue;

			String key   = pair.substring(0, eq).trim();
			String value = pair.substring(eq + 1).trim();

			Matcher nested = NESTED_TARGETER_VALUE.matcher(value);
			if (nested.matches() && "listeners".equalsIgnoreCase(key)) {
				listenerIdent = nested.group(1);
				continue;
			}
			args.put(key, value);
		}

		String target = null;
		Double radius = null;
		List<String> conditions = new ArrayList<>();

		Matcher targeter = TRAILING_TARGETER.matcher(remainder);
		if (targeter.find()) {
			int targeterEnd = matchingBrace(remainder, targeter.end() - 1);
			String inner = targeterEnd >= 0 ? remainder.substring(targeter.end(), targeterEnd) : "";
			target = resolveTarget(targeter.group(1));

			Matcher range = RANGE_ARG.matcher(inner);
			if (range.find()) {
				try {
					radius = Double.parseDouble(range.group(1));
				} catch (NumberFormatException ignored) {
					// left null - caller falls back to the hook's own default radius.
				}
			}

			remainder = targeterEnd >= 0 ? remainder.substring(Math.min(targeterEnd + 1, remainder.length())).trim()
			                             : "";
		} else if (listenerIdent != null) {
			target = resolveTarget(listenerIdent);
		}

		while (!remainder.isEmpty()) {
			Matcher condition = LEADING_CONDITION.matcher(remainder);
			if (!condition.find()) break;

			conditions.add(condition.group(1));
			int conditionEnd = matchingBrace(remainder, condition.end() - 1);
			if (conditionEnd < 0) break;
			remainder = remainder.substring(Math.min(conditionEnd + 1, remainder.length())).trim();
		}

		return new ParsedMechanic(type, Map.copyOf(args), target, radius, List.copyOf(conditions));
	}

	@Nullable
	private static String resolveTarget(String targeterIdentifier) {
		return switch (targeterIdentifier.toLowerCase(Locale.ROOT)) {
			case "source" -> "source";
			case "target", "victim" -> "victim";
			case "world" -> "nearby";
			default -> null;
		};
	}

	/**
	 * @return the index of the {@code }} matching the {@code {} at {@code openBraceIndex}, tracking {@code {}/[]/()}
	 * 		nesting so a comma or brace inside a nested {@code [...]}/{@code (...)} (e.g. {@code Firework}'s
	 * 		{@code effects=[(shape=BALL, ...)]}) never closes the outer block early; {@code -1} when unbalanced.
	 */
	private static int matchingBrace(String text, int openBraceIndex) {
		int depth = 0;
		for (int i = openBraceIndex; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c == '{' || c == '[' || c == '(') depth++;
			else if (c == '}' || c == ']' || c == ')') {
				depth--;
				if (depth == 0) return i;
			}
		}
		return -1;
	}

	/** Splits {@code content} on top-level commas only - depth-aware, same bracket set as {@link #matchingBrace}. */
	private static List<String> splitTopLevel(String content) {
		List<String> parts = new ArrayList<>();
		int          depth = 0;
		int          start = 0;
		for (int i = 0; i < content.length(); i++) {
			char c = content.charAt(i);
			if (c == '{' || c == '[' || c == '(') depth++;
			else if (c == '}' || c == ']' || c == ')') depth--;
			else if (c == ',' && depth == 0) {
				parts.add(content.substring(start, i).trim());
				start = i + 1;
			}
		}
		String last = content.substring(start).trim();
		if (!last.isEmpty()) parts.add(last);
		return parts;
	}

	@Nullable
	private static String arg(ParsedMechanic parsed, String key) {
		for (Map.Entry<String, String> entry : parsed.args().entrySet()) {
			if (entry.getKey().equalsIgnoreCase(key)) return entry.getValue();
		}
		return null;
	}

	private static void copy(ParsedMechanic parsed, String wmKey, Map<String, String> out, String bartizanKey) {
		String value = arg(parsed, wmKey);
		if (value != null) out.put(bartizanKey, value);
	}

	/**
	 * @return the translated Bartizan effect entry, or {@code null} when {@code parsed}'s type has no Bartizan
	 * 		equivalent - the caller emits a {@code # unmapped:} comment and a report line instead.
	 */
	@Nullable
	public static TranslatedEffect translate(ParsedMechanic parsed) {
		String               wmType = parsed.type().toLowerCase(Locale.ROOT);
		Map<String, String>  out    = new LinkedHashMap<>();

		String bartizanType = switch (wmType) {
			// "noise" (pitch jitter) and "delayBeforePlay" (per-effect delay) are deliberately not copied here -
			// SoundHookEffect reads Sound/Volume/Pitch/Pitch_Per_Level/Target/At/Radius only, no Pitch_Variance or
			// Delay key exists to receive them. The caller (WmWeaponImporter.addHook) reports both as lossy.
			case "sound" -> {
				copy(parsed, "sound", out, "Sound");
				copy(parsed, "volume", out, "Volume");
				copy(parsed, "pitch", out, "Pitch");
				yield "sound";
			}
			case "customsound" -> {
				copy(parsed, "sound", out, "Sound");
				copy(parsed, "volume", out, "Volume");
				copy(parsed, "pitch", out, "Pitch");
				yield "custom_sound";
			}
			case "particle" -> {
				copy(parsed, "particle", out, "Particle");
				copy(parsed, "count", out, "Count");
				String noise = arg(parsed, "noise");
				if (noise != null) out.put("Offset", expandOffset(noise));
				String color = arg(parsed, "color");
				if (color != null) out.put("Color", namedColorToHex(color));
				yield "particle";
			}
			case "potion" -> {
				copy(parsed, "potion", out, "Potion");
				copy(parsed, "time", out, "Duration");
				copy(parsed, "level", out, "Amplifier");
				yield "potion";
			}
			case "actionbar" -> {
				copy(parsed, "message", out, "Text");
				yield "action_bar";
			}
			case "title" -> {
				copy(parsed, "title", out, "Title");
				copy(parsed, "subtitle", out, "Subtitle");
				copy(parsed, "fadein", out, "Fade_In");
				copy(parsed, "stay", out, "Stay");
				copy(parsed, "fadeout", out, "Fade_Out");
				yield "title";
			}
			case "message" -> {
				copy(parsed, "message", out, "Text");
				yield "message";
			}
			case "bossbar" -> {
				copy(parsed, "message", out, "Text");
				copy(parsed, "color", out, "Color");
				copy(parsed, "style", out, "Style");
				copy(parsed, "duration", out, "Duration");
				yield "boss_bar";
			}
			case "command" -> {
				copy(parsed, "command", out, "Command");
				// An absent console key defaults to player, not console: least-privilege for a converter that
				// cannot verify WeaponMechanics' own default for this flag, and it must never silently hand a
				// migrated weapon's effect full console permissions it wasn't explicitly configured for.
				String console = arg(parsed, "console");
				out.put("As", Boolean.parseBoolean(console) ? "console" : "player");
				yield "command";
			}
			case "push", "leap" -> {
				copy(parsed, "speed", out, "Strength");
				copy(parsed, "height", out, "Strength");
				copy(parsed, "direction", out, "Direction");
				yield "push";
			}
			case "ignite" -> {
				copy(parsed, "ticks", out, "Ticks");
				yield "ignite";
			}
			case "firework" -> {
				String effects = arg(parsed, "effects");
				if (effects != null) {
					Matcher shape = Pattern.compile("shape=([A-Za-z_]+)").matcher(effects);
					if (shape.find()) out.put("Firework_Type", shape.group(1));
					Matcher color = Pattern.compile("color=([A-Za-z_]+)").matcher(effects);
					if (color.find()) out.put("Color", namedColorToHex(color.group(1)));
				}
				yield "firework";
			}
			case "lightning" -> "lightning";
			case "camerashake" -> {
				copy(parsed, "yaw", out, "Yaw");
				copy(parsed, "pitch", out, "Pitch");
				yield "camera_shake";
			}
			default -> null;
		};

		if (bartizanType == null) return null;

		if (parsed.target() != null) out.put("Target", parsed.target());
		if (parsed.radius() != null) out.put("Radius", String.valueOf(parsed.radius()));

		return new TranslatedEffect(bartizanType, out);
	}

	/** @return {@code true} when {@code wmType} is a mechanic Bartizan knowingly has no effect for (vs. simply typo'd). */
	public static boolean isKnownUnmapped(String wmType) {
		return UNMAPPED_KNOWN.contains(wmType.toLowerCase(Locale.ROOT));
	}

	private static String expandOffset(String noise) {
		String[] parts = noise.trim().split("\\s+");
		if (parts.length >= 3) return parts[0] + " " + parts[1] + " " + parts[2];
		if (parts.length == 1) return parts[0] + " " + parts[0] + " " + parts[0];
		return noise;
	}

	private static final Map<String, String> NAMED_COLORS = Map.ofEntries(
			Map.entry("red", "#FF0000"), Map.entry("green", "#00FF00"), Map.entry("blue", "#0000FF"),
			Map.entry("yellow", "#FFFF00"), Map.entry("black", "#000000"), Map.entry("white", "#FFFFFF"),
			Map.entry("orange", "#FFA500"), Map.entry("purple", "#800080"), Map.entry("aqua", "#00FFFF"),
			Map.entry("pink", "#FFC0CB"), Map.entry("gray", "#808080"), Map.entry("grey", "#808080"));

	/** @return {@code #RRGGBB} for a WM colour name (best-effort, defaults to white); already-hex input passes through. */
	private static String namedColorToHex(String value) {
		String trimmed = value.trim();
		if (trimmed.startsWith("#")) return trimmed;
		return NAMED_COLORS.getOrDefault(trimmed.toLowerCase(Locale.ROOT), "#FFFFFF");
	}

}
