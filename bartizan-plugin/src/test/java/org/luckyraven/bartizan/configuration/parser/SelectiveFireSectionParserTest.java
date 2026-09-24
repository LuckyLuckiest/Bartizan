package org.luckyraven.bartizan.configuration.parser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.weapon.SelectiveFire;
import org.luckyraven.bartizan.configuration.parser.SelectiveFireSectionParser.ParsedSelectiveFire;
import org.luckyraven.keystone.persistence.config.ConfigDocument;
import org.luckyraven.keystone.persistence.config.ConfigParser;
import org.luckyraven.keystone.persistence.config.ConfigReport;
import org.luckyraven.keystone.persistence.config.NodeReader;
import org.luckyraven.keystone.persistence.config.Severity;

import java.io.StringReader;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BZ-CF-14: an unrecognised {@code Selective_Fire}/{@code Allowed_Modes} value used to silently resolve to a
 * different, live fire mode (AUTO) via {@code SelectiveFire.getType}'s default branch, with no {@link ConfigReport}
 * entry anywhere.
 */
@DisplayName("SelectiveFireSectionParser — unknown mode values (BZ-CF-14)")
class SelectiveFireSectionParserTest {

	private static final Path FIXTURE = Path.of("weapon.yml");

	@Test
	@DisplayName("an unrecognised Selective_Fire value warns and falls back to AUTO instead of silently becoming it")
	void unknownSelectiveFire_warnsAndFallsBackToAuto() throws Exception {
		ConfigReport report = new ConfigReport();
		NodeReader   shoot  = shootReaderFor(report, """
				Shoot:
				   Selective_Fire: sinlge
				""");

		ParsedSelectiveFire parsed = SelectiveFireSectionParser.parse(shoot, report, "test_gun");

		assertEquals(SelectiveFire.AUTO, parsed.current());
		assertTrue(report.issues().stream().anyMatch(
				issue -> issue.severity() == Severity.WARNING
				         && issue.code().equals("selectiveFire.unknown_mode")));
	}

	@Test
	@DisplayName("a recognised Selective_Fire value parses cleanly with no warning")
	void knownSelectiveFire_noWarning() throws Exception {
		ConfigReport report = new ConfigReport();
		NodeReader   shoot  = shootReaderFor(report, """
				Shoot:
				   Selective_Fire: single
				""");

		ParsedSelectiveFire parsed = SelectiveFireSectionParser.parse(shoot, report, "test_gun");

		assertEquals(SelectiveFire.SINGLE, parsed.current());
		assertTrue(report.issues().stream().noneMatch(issue -> issue.severity() == Severity.WARNING));
	}

	@Test
	@DisplayName("an unrecognised Allowed_Modes entry is skipped (not coerced into AUTO), with a WARNING")
	void unknownAllowedModesEntry_skippedWithWarning() throws Exception {
		ConfigReport report = new ConfigReport();
		NodeReader   shoot  = shootReaderFor(report, """
				Shoot:
				   Selective_Fire: single
				   Allowed_Modes:
				      - single
				      - brust
				""");

		ParsedSelectiveFire parsed = SelectiveFireSectionParser.parse(shoot, report, "test_gun");

		assertEquals(Set.of(SelectiveFire.SINGLE), parsed.allowed(),
		            "the typo'd 'brust' entry must not silently become AUTO in the allowed set");
		assertTrue(report.issues().stream().anyMatch(
				issue -> issue.severity() == Severity.WARNING
				         && issue.code().equals("selectiveFire.unknown_mode")));
	}

	private NodeReader shootReaderFor(ConfigReport report, String yaml) {
		ConfigDocument doc  = new ConfigParser().parse(FIXTURE, new StringReader(yaml), report);
		NodeReader     root = NodeReader.of(doc.root(), report);
		return NodeReader.of(root.get("Shoot").asMapping().orNull(), report);
	}

}
