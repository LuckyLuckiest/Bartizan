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
	 * {@code Damage_Modifiers:} — global percent-of-damage modifiers applied on the default gun-hit path
	 * (weapons-roadmap.md gate {@code HF}, §5). All default {@code 0} (no-op) when the section is present but a key
	 * is missing, and all default {@code 0} with a logged warning when the whole section is absent. {@code double}
	 * (not {@code int}) so a fractional percent like {@code Per_Armor_Point: -1.5} is honoured.
	 */
	private static @Getter double damageModifierPerArmorPoint;
	private static @Getter double damageModifierSneaking;
	private static @Getter double damageModifierSprinting;
	private static @Getter double damageModifierInMidair;
	private static @Getter double damageModifierShielding;

	/**
	 * {@code Stats:} — per-player weapon statistics (weapons-roadmap.md gate {@code HK}). All default to the
	 * values below (matching the shipped {@code settings.yml}) with a logged warning when the whole section is
	 * absent.
	 */
	private static @Getter boolean statsEnabled = true;
	private static @Getter int     statsAssistWindowTicks = 100;
	private static @Getter int     statsAutosaveMinutes = 5;

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

	private static double doubleVal(NodeReader parent, String key, double def) {
		if (parent == null) return def;
		return parent.get(key).asDouble().orDefault(def);
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

		if (root != null && root.has("Damage_Modifiers")) {
			NodeReader damageModifiers   = section(root, "Damage_Modifiers", report);
			damageModifierPerArmorPoint = doubleVal(damageModifiers, "Per_Armor_Point", 0);
			damageModifierSneaking      = doubleVal(damageModifiers, "Sneaking", 0);
			damageModifierSprinting     = doubleVal(damageModifiers, "Sprinting", 0);
			damageModifierInMidair      = doubleVal(damageModifiers, "In_Midair", 0);
			damageModifierShielding     = doubleVal(damageModifiers, "Shielding", 0);
		} else {
			log.warn("settings.yml has no Damage_Modifiers section; using the built-in defaults (all 0) — add "
			         + "the section to customise them");
			damageModifierPerArmorPoint = 0;
			damageModifierSneaking      = 0;
			damageModifierSprinting     = 0;
			damageModifierInMidair      = 0;
			damageModifierShielding     = 0;
		}

		if (root != null && root.has("Stats")) {
			NodeReader stats = section(root, "Stats", report);
			statsEnabled           = bool(stats, "Enabled", true);
			statsAssistWindowTicks = intVal(stats, "Assist_Window_Ticks", 100);
			statsAutosaveMinutes   = intVal(stats, "Autosave_Minutes", 5);
		} else {
			log.warn("settings.yml has no Stats section; using the built-in defaults (Enabled: true, "
			         + "Assist_Window_Ticks: 100, Autosave_Minutes: 5) — add the section to customise them");
			statsEnabled           = true;
			statsAssistWindowTicks = 100;
			statsAutosaveMinutes   = 5;
		}

		if (!report.isEmpty()) report.log(log);
	}

}
