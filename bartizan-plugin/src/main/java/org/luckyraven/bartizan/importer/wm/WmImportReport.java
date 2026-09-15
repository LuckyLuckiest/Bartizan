package org.luckyraven.bartizan.importer.wm;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;

/**
 * Per-weapon (and whole-run) accounting for {@code /bartizan import weaponmechanics} (weapons-roadmap.md gate
 * {@code HM}, §6.1): every WM key the translator mapped, approximated, or dropped, so nothing disappears silently.
 * Rendered as plain text for {@code plugins/Bartizan/import/weaponmechanics-<date>.txt}.
 */
public final class WmImportReport {

	/** One weapon's bookkeeping. */
	public static final class WeaponEntry {

		final String       title;
		final List<String> mapped        = new ArrayList<>();
		final List<String> approximated  = new ArrayList<>();
		final List<String> dropped       = new ArrayList<>();
		final List<String> errors        = new ArrayList<>();

		WeaponEntry(String title) {
			this.title = title;
		}

		public void mapped(String line) {
			mapped.add(line);
		}

		public void approximated(String line) {
			approximated.add(line);
		}

		public void dropped(String line) {
			dropped.add(line);
		}

		public void error(String line) {
			errors.add(line);
		}

		public boolean hasErrors() {
			return !errors.isEmpty();
		}

	}

	private final Map<String, WeaponEntry> weapons = new LinkedHashMap<>();
	private final List<String>             runNotes = new ArrayList<>();

	public WeaponEntry weapon(String title) {
		return weapons.computeIfAbsent(title, WeaponEntry::new);
	}

	public void runNote(String line) {
		runNotes.add(line);
	}

	public boolean hasErrors() {
		return weapons.values().stream().anyMatch(WeaponEntry::hasErrors);
	}

	public int weaponCount() {
		return weapons.size();
	}

	public int mappedCount() {
		return weapons.values().stream().mapToInt(w -> w.mapped.size()).sum();
	}

	public int approximatedCount() {
		return weapons.values().stream().mapToInt(w -> w.approximated.size()).sum();
	}

	public int droppedCount() {
		return weapons.values().stream().mapToInt(w -> w.dropped.size()).sum();
	}

	public String render() {
		StringBuilder out = new StringBuilder();
		out.append("WeaponMechanics import report - ")
		   .append(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)).append('\n');
		out.append(weapons.size()).append(" weapon(s): ").append(mappedCount()).append(" mapped, ")
		   .append(approximatedCount()).append(" approximated, ").append(droppedCount()).append(" dropped\n");

		if (!runNotes.isEmpty()) {
			out.append("\n== run notes ==\n");
			runNotes.forEach(note -> out.append("- ").append(note).append('\n'));
		}

		for (WeaponEntry entry : weapons.values()) {
			out.append("\n== ").append(entry.title).append(" ==\n");
			if (entry.hasErrors()) {
				out.append("  errors:\n");
				entry.errors.forEach(line -> out.append("    - ").append(line).append('\n'));
			}
			if (!entry.mapped.isEmpty()) {
				out.append("  mapped (").append(entry.mapped.size()).append("):\n");
				entry.mapped.forEach(line -> out.append("    - ").append(line).append('\n'));
			}
			if (!entry.approximated.isEmpty()) {
				out.append("  approximated (").append(entry.approximated.size()).append("):\n");
				entry.approximated.forEach(line -> out.append("    - ").append(line).append('\n'));
			}
			if (!entry.dropped.isEmpty()) {
				out.append("  dropped (").append(entry.dropped.size()).append("):\n");
				entry.dropped.forEach(line -> out.append("    - ").append(line).append('\n'));
			}
		}

		return out.toString();
	}

}
