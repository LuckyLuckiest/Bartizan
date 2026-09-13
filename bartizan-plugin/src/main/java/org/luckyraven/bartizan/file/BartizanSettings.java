package org.luckyraven.bartizan.file;

import lombok.CustomLog;
import lombok.Getter;
import org.luckyraven.keystone.exception.PluginException;
import org.luckyraven.keystone.persistence.FileHandler;
import org.luckyraven.keystone.persistence.FileInitializer;
import org.luckyraven.keystone.persistence.FileManager;
import org.luckyraven.keystone.persistence.config.ConfigReport;
import org.luckyraven.keystone.persistence.config.FileHandlerReader;
import org.luckyraven.keystone.persistence.config.MappingNode;
import org.luckyraven.keystone.persistence.config.NodeReader;
import org.luckyraven.bartizan.api.weapon.dto.EffectsData;
import org.luckyraven.bartizan.configuration.parser.EffectsSectionParser;

import java.io.IOException;
import java.util.Objects;

/**
 * Bartizan's {@code settings.yml} reader — same {@link FileInitializer} shape as Gangland's {@code Settings}
 * (bartizan.md §1.8), but carrying only the settings the weapon module actually reads plus a {@code Debug} flag.
 * There is no database section: Bartizan keeps no database. Never {@code setDefaults}/{@code copyDefaults} — every
 * default lives in the shipped {@code settings.yml} itself.
 */
@CustomLog
public class BartizanSettings implements FileInitializer {

	private static @Getter boolean debugEnabled;

	private static @Getter String languagePicked;
	private static @Getter String moneySymbol;

	private static @Getter int blockRestoreDelayTicks;
	private static @Getter int blockRegenerationDelayTicks;
	private static @Getter int blockRegenerationStepTicks;

	/**
	 * Fallback effect lists used when a weapon declares no {@code Effects:} list for a given hook (weapons-roadmap.md
	 * gate {@code HA}, §1 "Global defaults"). Never {@code null} — empty when {@code Default_Effects:} is absent.
	 */
	private static @Getter EffectsData defaultEffects = EffectsData.empty();

	private final FileHandler fileHandler;

	public BartizanSettings(FileManager fileManager) {
		try {
			String fileName = "settings";

			fileManager.checkFileLoaded(fileName);

			this.fileHandler = Objects.requireNonNull(fileManager.getFile(fileName));
		} catch (IOException exception) {
			throw new PluginException(exception);
		}
	}

	private static NodeReader section(NodeReader parent, String key, ConfigReport report) {
		if (parent == null) return null;
		MappingNode child = parent.get(key).asMapping().orNull();
		if (child == null) return null;
		return NodeReader.of(child, report);
	}

	private static String str(NodeReader parent, String key, String def) {
		if (parent == null) return def;
		return parent.get(key).asString().orDefault(def);
	}

	private static int intVal(NodeReader parent, String key, int def) {
		if (parent == null) return def;
		return parent.get(key).asInt().orDefault(def);
	}

	private static boolean bool(NodeReader parent, String key, boolean def) {
		if (parent == null) return def;
		return parent.get(key).asBool().orDefault(def);
	}

	@Override
	public FileHandler getFileHandler() {
		return fileHandler;
	}

	@Override
	public void initialize() {
		init();
	}

	private void init() {
		ConfigReport report = new ConfigReport();
		NodeReader   root   = FileHandlerReader.read(fileHandler, report);

		NodeReader debug = section(root, "Debug", report);
		debugEnabled = bool(debug, "Enable", false);

		languagePicked = str(root, "Language", "en");
		moneySymbol    = str(root, "Money_Symbol", "$").substring(0, 1);

		NodeReader blockRegeneration = section(root, "Block_Regeneration", report);
		blockRestoreDelayTicks      = intVal(blockRegeneration, "Restore_Delay_Ticks", 100);
		blockRegenerationDelayTicks = intVal(blockRegeneration, "Regeneration_Delay_Ticks", 100);
		blockRegenerationStepTicks  = intVal(blockRegeneration, "Regeneration_Step_Ticks", 4);

		if (root != null && root.has("Default_Effects")) {
			NodeReader defaultEffectsSection = section(root, "Default_Effects", report);
			defaultEffects = EffectsSectionParser.parse(defaultEffectsSection, report);
		} else {
			log.warn("settings.yml has no Default_Effects section; using the built-in defaults — add the "
			         + "section to customise them");
			defaultEffects = EffectsSectionParser.builtInDefaults();
		}

		if (!report.isEmpty()) report.log(log);
	}

}
