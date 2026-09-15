package org.luckyraven.bartizan.importer.wm;

import java.util.List;
import java.util.Map;

/**
 * Tiny block-style YAML text emitter (weapons-roadmap.md gate {@code HM}, §6.5) - Bartizan has no
 * comment-preserving YAML writer, and this importer's whole point is emitting {@code # imported:}/
 * {@code # unmapped:} comments alongside the translated keys, so it writes plain text rather than going through
 * {@code YamlConfiguration} (which would drop them). 3-space indent, matching the shipped {@code weapon/*.yml}
 * house style.
 *
 * <p>Input shape: nested {@code LinkedHashMap<String, Object>} (order preserved) whose values are {@code String},
 * {@code Number}, {@code Boolean}, a nested {@code Map<String, Object>}, or a {@code List<?>} of scalars / of
 * {@code Map<String, Object>} (rendered {@code - Key: value} block-list style, matching
 * {@code EffectsSectionParser}'s {@code Effects.<hook>} shape). A map key starting with {@code "#"} renders as a
 * standalone {@code # <value>} comment line instead of a {@code Key: value} pair - the mechanism the importer uses
 * to attach {@code # imported:}/{@code # unmapped:} notes next to the key they explain. {@code null} values are
 * skipped, as are empty maps/lists, so callers can build a tree without pruning absent keys themselves.
 */
public final class WmYamlEmitter {

	private static final String INDENT_UNIT = "   ";

	private WmYamlEmitter() {
	}

	public static String emit(Map<String, Object> root) {
		StringBuilder out = new StringBuilder();
		writeMapEntries(out, root, 0);
		return out.toString();
	}

	private static void writeMapEntries(StringBuilder out, Map<String, Object> map, int indent) {
		for (Map.Entry<String, Object> entry : map.entrySet()) {
			Object value = entry.getValue();
			if (isEmpty(value)) continue;

			if (entry.getKey().startsWith("#")) {
				out.append(indent(indent)).append("# ").append(value).append('\n');
				continue;
			}

			writeKeyed(out, entry.getKey(), value, indent);
		}
	}

	private static void writeKeyed(StringBuilder out, String key, Object value, int indent) {
		if (value instanceof Map<?, ?> nested) {
			out.append(indent(indent)).append(key).append(":\n");
			@SuppressWarnings("unchecked")
			Map<String, Object> typed = (Map<String, Object>) nested;
			writeMapEntries(out, typed, indent + 1);
		} else if (value instanceof List<?> list) {
			out.append(indent(indent)).append(key).append(":\n");
			writeList(out, list, indent + 1);
		} else {
			out.append(indent(indent)).append(key).append(": ").append(scalar(value)).append('\n');
		}
	}

	private static void writeList(StringBuilder out, List<?> list, int indent) {
		String dashIndent = indent(indent);
		for (Object item : list) {
			if (item instanceof Map<?, ?> mapItem) {
				writeListMapItem(out, mapItem, dashIndent);
			} else {
				out.append(dashIndent).append("- ").append(scalar(item)).append('\n');
			}
		}
	}

	private static void writeListMapItem(StringBuilder out, Map<?, ?> mapItem, String dashIndent) {
		String continuationIndent = dashIndent + "  ";
		boolean first = true;
		for (Map.Entry<?, ?> entry : mapItem.entrySet()) {
			String key   = String.valueOf(entry.getKey());
			Object value = entry.getValue();
			if (isEmpty(value)) continue;

			String prefix = first ? dashIndent + "- " : continuationIndent;
			first = false;

			if (key.startsWith("#")) {
				out.append(prefix).append("# ").append(value).append('\n');
				continue;
			}

			if (value instanceof Map<?, ?> || value instanceof List<?>) {
				// Nested containers inside a list-of-maps entry aren't needed by this importer's own output shapes;
				// falling back to a flat scalar rendering keeps the emitter honest rather than silently mis-indenting.
				out.append(prefix).append(key).append(": ").append(scalar(value)).append('\n');
			} else {
				out.append(prefix).append(key).append(": ").append(scalar(value)).append('\n');
			}
		}
	}

	private static boolean isEmpty(Object value) {
		if (value == null) return true;
		if (value instanceof Map<?, ?> map) return map.isEmpty();
		if (value instanceof List<?> list) return list.isEmpty();
		return false;
	}

	private static String scalar(Object value) {
		if (value instanceof String string) return quoteIfNeeded(string);
		return String.valueOf(value);
	}

	/**
	 * Quotes a string scalar whenever leaving it bare could change its meaning or confuse SnakeYAML: any YAML
	 * structural character, leading/trailing whitespace, emptiness, or a leading character that isn't a letter/
	 * digit/underscore (colour codes always start with {@code &}). Matches the shipped files' own convention -
	 * coloured names/lore are quoted, plain lookup ids ({@code sound.gta.foo}, {@code auto}, {@code BULLET}) are not.
	 */
	private static String quoteIfNeeded(String raw) {
		boolean needsQuote = raw.isEmpty() || !raw.equals(raw.trim());

		if (!needsQuote) {
			char first = raw.charAt(0);
			needsQuote = !(Character.isLetterOrDigit(first) || first == '_' || first == '-');
		}
		if (!needsQuote) {
			for (int i = 0; i < raw.length() && !needsQuote; i++) {
				char c = raw.charAt(i);
				if (c == ':' || c == '#' || c == '"' || c == '\'' || c == '{' || c == '}' || c == '[' ||
				    c == ']' || c == ',' || c == '&' || c == '\n' || c == ' ') {
					needsQuote = true;
				}
			}
		}

		if (!needsQuote) return raw;
		return '"' + raw.replace("\"", "\\\"") + '"';
	}

	private static String indent(int level) {
		return INDENT_UNIT.repeat(level);
	}

}
