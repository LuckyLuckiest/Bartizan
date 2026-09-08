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

import java.io.IOException;
import java.util.Objects;

/**
 * Bartizan's {@code settings.yml} reader — same {@link FileInitializer} shape as Gangland's {@code Settings}
 * (bartizan.md §1.8), but carrying only the seven Gangland settings the weapon module actually reads, plus the
 * three infrastructure sections a standalone plugin needs (Debug, Auto_Save, Database). Never
 * {@code setDefaults}/{@code copyDefaults} — every default lives in the shipped {@code settings.yml} itself.
 */
@CustomLog
public class BartizanSettings implements FileInitializer {

	private static @Getter boolean debugEnabled;

	private static @Getter String languagePicked;
	private static @Getter String moneySymbol;

	private static @Getter int blockRestoreDelayTicks;
	private static @Getter int blockRegenerationDelayTicks;
	private static @Getter int blockRegenerationStepTicks;

	private static @Getter boolean autoSaveDebug;
	private static @Getter int     autoSaveTime;
	private static @Getter int     cleanUpTime;

	private static @Getter String  databaseType;
	private static @Getter String  mysqlHost, mysqlUsername, mysqlPassword;
	private static @Getter int     mysqlPort;
	private static @Getter boolean sqliteBackup, sqliteFailedMysql;

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

		NodeReader autoSave = section(root, "Auto_Save", report);
		autoSaveDebug = bool(autoSave, "Debug", true);
		autoSaveTime  = intVal(autoSave, "Time", 10);

		// Clean_Up.Time (days): the weapon-table cleanup schedule driving WeaponDataCleanupTask (gate-GG review B1).
		NodeReader cleanUp = section(root, "Clean_Up", report);
		cleanUpTime = intVal(cleanUp, "Time", 30);

		NodeReader database = section(root, "Database", report);
		NodeReader mysql     = section(database, "MySQL", report);
		NodeReader sqlite    = section(database, "SQLite", report);

		databaseType      = str(database, "Type", "sqlite");
		mysqlHost         = str(mysql, "Host", "localhost");
		mysqlUsername     = str(mysql, "Username", "root");
		mysqlPassword     = str(mysql, "Password", "");
		mysqlPort         = intVal(mysql, "Port", 3306);
		sqliteBackup      = bool(sqlite, "Backup", true);
		sqliteFailedMysql = bool(sqlite, "Failed_MySQL", true);

		if (!report.isEmpty()) report.log(log);
	}

}
