package org.luckyraven.bartizan.importer.wm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WmColorTranslatorTest {

	@Test
	void translatesNamedColorTags() {
		assertEquals("&6AK-47", WmColorTranslator.translate("<gold>AK-47"));
		assertEquals("&7Very reliable", WmColorTranslator.translate("<gray>Very reliable"));
	}

	@Test
	void translatesFormatTags() {
		assertEquals("&lBold&r reset", WmColorTranslator.translate("<bold>Bold<reset> reset"));
	}

	@Test
	void translatesHexTagVerbatim() {
		assertEquals("&#FF00AATitle", WmColorTranslator.translate("<#FF00AA>Title"));
	}

	@Test
	void stripsClosingAndUnknownTags() {
		assertEquals("Hover text", WmColorTranslator.translate("<hover:show_text:'x'>Hover text</hover>"));
		assertEquals("&6Gradient", WmColorTranslator.translate("<gradient:red:blue><gold>Gradient</gradient>"));
	}

	@Test
	void nullInNullOut() {
		assertNull(WmColorTranslator.translate(null));
	}

	@Test
	void plainTextPassesThroughUnchanged() {
		assertEquals("no tags here", WmColorTranslator.translate("no tags here"));
	}

}
