package org.luckyraven.bartizan.command;

import lombok.CustomLog;
import lombok.Getter;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.luckyraven.bartizan.Bartizan;
import org.luckyraven.bartizan.bootstrap.BartizanContext;
import org.luckyraven.bartizan.command.data.InformationManager;
import org.luckyraven.bartizan.file.BartizanMessages;
import org.luckyraven.bartizan.file.BartizanSettings;
import org.luckyraven.bartizan.importer.wm.WmImportReport;
import org.luckyraven.bartizan.importer.wm.WmWeaponImporter;
import org.luckyraven.bartizan.importer.wm.WmYamlEmitter;
import org.luckyraven.bartizan.util.BartizanChatUtil;
import org.luckyraven.keystone.command.Command;
import org.luckyraven.keystone.command.argument.Argument;
import org.luckyraven.keystone.bean.command.CommandHandler;
import org.luckyraven.keystone.persistence.FileManager;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code /bartizan import weaponmechanics [--dry-run] [--force] [path]} (weapons-roadmap.md gate {@code HM},
 * §6.1) - same top-level-command-plus-one-literal-child shape as {@link ReloadCommand}/{@link DebugCommand}; the
 * optional flags/path aren't worth a full {@code Argument} sub-tree (they can appear in any order, or not at all),
 * so the child argument's action just scans the raw {@code args} tail itself.
 * <p>
 * Lives in {@code command} (not {@code importer.wm}, where the rest of gate {@code HM} lives) because
 * {@code BartizanContext.runCommandPhase} only scans {@code org.luckyraven.bartizan.command} for
 * {@code @CommandHandler} classes - a command class anywhere else silently never registers. The translator/
 * emitter/report/listener classes it delegates to stay in {@code importer.wm}, which the (whole-plugin-rooted)
 * listener scan and this class's own imports both reach fine.
 *
 * <p>Runs synchronously on the calling thread (main thread for an in-game/console sender) - ponytail: WM's 24
 * defaults import in well under a second; chunking across ticks is for a "hundreds of weapons" server this
 * codebase has never seen, add it if that ever actually shows up.
 */
@CustomLog
@Getter
@CommandHandler
public final class WmImportCommand extends Command {

	private static final DateTimeFormatter REPORT_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm");

	private final Bartizan       bartizan;
	private final BartizanContext context;
	private final HelpInfo       helpInfo;

	public WmImportCommand(Bartizan bartizan, InformationManager informationManager, BartizanContext context) {
		super(bartizan, Bartizan.FULL_PREFIX, "import", false);

		this.bartizan = bartizan;
		this.context  = context;
		this.helpInfo = new HelpInfo();

		var list = informationManager.getCommands().entrySet()
				.stream()
				.filter(entry -> entry.getKey().startsWith("import"))
				.sorted(Map.Entry.comparingByKey())
				.map(Map.Entry::getValue)
				.toList();

		helpInfo.addAll(list);
	}

	@Override
	protected void onExecute(Argument argument, CommandSender commandSender, String[] arguments) {
		help(commandSender, 1);
	}

	@Override
	protected void initializeArguments() {
		Argument weaponMechanics = new Argument(bartizan, "weaponmechanics", getArgumentTree(),
		                                        this::runImport);
		getArgument().addAllSubArguments(List.of(weaponMechanics));
	}

	@Override
	protected void help(CommandSender sender, int page) {
		helpInfo.displayHelp(sender, page, "Import");
	}

	private void runImport(Argument argument, CommandSender sender, String[] args) {
		boolean dryRun = false;
		boolean force  = false;
		String  path   = null;

		for (int i = 2; i < args.length; i++) {
			String token = args[i];
			if (token.equalsIgnoreCase("--dry-run")) dryRun = true;
			else if (token.equalsIgnoreCase("--force")) force = true;
			else path = token;
		}

		File wmDir = path != null ? new File(path)
		                          : new File(bartizan.getDataFolder().getParentFile(), "WeaponMechanics");
		if (!wmDir.isDirectory()) {
			sender.sendMessage(BartizanMessages.IMPORT_NOT_FOUND.toString().replace("%path%", wmDir.getPath()));
			return;
		}

		try {
			runImport(sender, wmDir, dryRun, force);
		} catch (IOException exception) {
			sender.sendMessage(BartizanChatUtil.errorMessage("Import failed: " + exception.getMessage()));
			log.error("WeaponMechanics import failed", exception);
		}
	}

	private void runImport(CommandSender sender, File wmDir, boolean dryRun, boolean force) throws IOException {
		WmImportReport report = new WmImportReport();

		Map<String, ConfigurationSection> projectiles = loadRefs(new File(wmDir, "projectiles"));
		Map<String, ConfigurationSection> ammos       = loadRefs(new File(wmDir, "ammos"));

		File weaponsDir = new File(wmDir, "weapons");
		List<File> weaponFiles = new ArrayList<>();
		collectYamlFiles(weaponsDir, weaponFiles);

		File weaponOutDir = new File(bartizan.getDataFolder(), "weapon");
		List<WmWeaponImporter.AmmoAppend> ammoAppends = new ArrayList<>();

		for (File weaponFile : weaponFiles) {
			YamlConfiguration doc = YamlConfiguration.loadConfiguration(weaponFile);
			for (String title : doc.getKeys(false)) {
				ConfigurationSection body = doc.getConfigurationSection(title);
				if (body == null) continue;

				WmImportReport.WeaponEntry entry = report.weapon(title);
				importOne(title, body, projectiles, ammos, entry, weaponOutDir, dryRun, force, ammoAppends);
			}
		}

		File reportFile;
		try {
			if (!dryRun && !ammoAppends.isEmpty()) {
				appendAmmunition(ammoAppends);
			}
		} catch (IOException exception) {
			report.runNote("failed to append ammunition.yml: " + exception.getMessage());
			log.error("WM import failed to append ammunition.yml", exception);
		} finally {
			// A half-migrated folder (some weapons written, ammunition.yml append failed, etc.) must still get its
			// report - never let an earlier I/O failure skip this and lose the whole run's accounting.
			reportFile = writeReport(report);
		}

		if (!dryRun) {
			FileManager fileManager = context.get(FileManager.class);
			if (fileManager != null) fileManager.initializeAll();
			context.reloadBeans();
			BartizanSettings.setConvertWeaponMechanicsItemsRuntimeOverride(true);
		}

		sender.sendMessage(BartizanMessages.IMPORT_DONE.toString()
		                            .replace("%weapons%", String.valueOf(report.weaponCount()))
		                            .replace("%mapped%", String.valueOf(report.mappedCount()))
		                            .replace("%approximated%", String.valueOf(report.approximatedCount()))
		                            .replace("%dropped%", String.valueOf(report.droppedCount()))
		                            .replace("%report%", reportFile.getPath()));

		if (!dryRun) {
			sender.sendMessage(BartizanChatUtil.commandMessage(
					"&7Live WeaponMechanics item conversion is now on for this session - add "
					+ "&fImport.Convert_WeaponMechanics_Items: true&7 to &fsettings.yml&7 to keep it after a reload."));
		}
	}

	private void importOne(String title, ConfigurationSection body, Map<String, ConfigurationSection> projectiles,
	                       Map<String, ConfigurationSection> ammos, WmImportReport.WeaponEntry entry,
	                       File weaponOutDir, boolean dryRun, boolean force,
	                       List<WmWeaponImporter.AmmoAppend> ammoAppends) {
		try {
			WmWeaponImporter.ImportedWeapon imported =
					WmWeaponImporter.importWeapon(title, body, projectiles, ammos, entry);
			if (imported == null) return;

			File outFile = new File(weaponOutDir, imported.fileKey() + ".yml");
			if (outFile.exists() && !force) {
				entry.error("not written - " + outFile.getName() + " already exists (pass --force to overwrite)");
				return;
			}

			if (!dryRun) {
				Files.createDirectories(outFile.getParentFile().toPath());
				Files.writeString(outFile.toPath(), WmYamlEmitter.emit(imported.yaml()));
			}

			if (imported.ammoAppend() != null) ammoAppends.add(imported.ammoAppend());
		} catch (Exception exception) {
			// One weapon's failure (translation bug, a bad file permission, whatever) must never abort the whole
			// import and lose every other weapon's report entry - isolate it here instead.
			entry.error("unexpected failure importing this weapon: " + exception);
			log.error("WM import failed for '{}'", title, exception);
		}
	}

	private Map<String, ConfigurationSection> loadRefs(File dir) {
		Map<String, ConfigurationSection> refs = new LinkedHashMap<>();
		if (!dir.isDirectory()) return refs;

		File[] files = dir.listFiles((ignored, name) -> name.endsWith(".yml"));
		if (files == null) return refs;

		for (File file : files) {
			YamlConfiguration doc = YamlConfiguration.loadConfiguration(file);
			for (String key : doc.getKeys(false)) {
				ConfigurationSection section = doc.getConfigurationSection(key);
				if (section != null) refs.put(key, section);
			}
		}
		return refs;
	}

	private void collectYamlFiles(File dir, List<File> out) {
		File[] children = dir.listFiles();
		if (children == null) return;

		for (File child : children) {
			if (child.isDirectory()) collectYamlFiles(child, out);
			else if (child.getName().endsWith(".yml")) out.add(child);
		}
	}

	private void appendAmmunition(List<WmWeaponImporter.AmmoAppend> ammoAppends) throws IOException {
		File ammoFile = new File(bartizan.getDataFolder(), "items/ammunition.yml");
		YamlConfiguration existing = ammoFile.isFile() ? YamlConfiguration.loadConfiguration(ammoFile)
		                                               : new YamlConfiguration();

		StringBuilder appendText = new StringBuilder();
		for (WmWeaponImporter.AmmoAppend ammo : ammoAppends) {
			if (existing.contains(ammo.id())) continue;

			appendText.append(ammo.id()).append(":\n")
			          .append("   Material: \"").append(ammo.material()).append("\"\n")
			          .append("   Name: \"").append(ammo.name().replace("\"", "\\\"")).append("\"\n")
			          .append("   Lore:\n")
			          .append("      - \"&7Imported from WeaponMechanics.\"\n");
			existing.set(ammo.id(), new LinkedHashMap<>()); // marks it seen so a later dupe in this same run is skipped
		}

		if (appendText.length() == 0) return;

		Files.writeString(ammoFile.toPath(), "\n" + appendText, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
	}

	private File writeReport(WmImportReport report) throws IOException {
		File importDir = new File(bartizan.getDataFolder(), "import");
		Files.createDirectories(importDir.toPath());

		File reportFile = new File(importDir, "weaponmechanics-" + LocalDateTime.now().format(REPORT_STAMP) + ".txt");
		Files.writeString(reportFile.toPath(), report.render());
		return reportFile;
	}

}
