package org.luckyraven.bartizan.api.weapon.dto;

import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Map;

/**
 * One configured effect entry under a hook: an {@code EffectRunner}-registry type key (lowercase, e.g.
 * {@code "sound"}) plus its YAML-authored args, keys stored verbatim as written ({@code Sound}, {@code Volume},
 * {@code Target}, {@code At}, …). {@code args} is defensively copied and immutable.
 */
public record EffectSpec(String type, Map<String, String> args) {

	public EffectSpec {
		args = args == null ? Map.of() : Map.copyOf(args);
	}

	@Nullable
	public String arg(String key) {
		return args.get(key);
	}

	public String arg(String key, String def) {
		String value = args.get(key);
		return value != null ? value : def;
	}

	public int intArg(String key, int def) {
		String value = args.get(key);
		if (value == null) return def;

		try {
			return Integer.parseInt(value.trim());
		} catch (NumberFormatException exception) {
			return def;
		}
	}

	public double doubleArg(String key, double def) {
		String value = args.get(key);
		if (value == null) return def;

		try {
			return Double.parseDouble(value.trim());
		} catch (NumberFormatException exception) {
			return def;
		}
	}

	public boolean boolArg(String key, boolean def) {
		String value = args.get(key);
		if (value == null) return def;

		return switch (value.trim().toLowerCase(Locale.ROOT)) {
			case "true", "yes", "on" -> true;
			case "false", "no", "off" -> false;
			default -> def;
		};
	}

}
