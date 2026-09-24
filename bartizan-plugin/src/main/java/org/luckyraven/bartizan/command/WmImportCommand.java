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
import org.luckyraven.keystone.command.argument.types.OptionalArgument;
import org.luckyraven.keystone.bean.command.CommandHandler;
import org.luckyraven.keystone.persistence.FileManager;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * {@code /bartizan import weaponmechanics [--dry-run] [--force] [path]} (weapons-roadmap.md gate {@code HM},
 * §6.1) - same top-level-command-plus-one-literal-child shape as {@link ReloadCommand}/{@link DebugCommand}, with
 * three free-form {@code OptionalArgument} levels under the literal so Keystone lets the flags/path through; they
 * can appear in any order, or not at all, so every level's action just scans the raw {@code args} tail itself.
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
		// Keystone rejects any token under a leaf, so [--dry-run] [--force] [path] need three free-form levels; the
		// tokens can come in any order, so every level runs the same parser.
		Function<CommandSender, List<String>> suggestions = sender -> List.of("--dry-run", "--force", "<path>");
		OptionalArgument first  = new OptionalArgument(bartizan, getArgumentTree(), this::runImport, suggestions);
		OptionalArgument second = new OptionalArgument(bartizan, getArgumentTree(), this::runImport, suggestions);
		OptionalArgument third  = new OptionalArgument(bartizan, getArgumentTree(), this::runImport, suggestions);
		first.setDisplayName("option");
		second.setDisplayName("option");
		third.setDisplayName("option");
		second.addSubArgument(third);
		first.addSubArgument(second);
		weaponMechanics.addSubArgument(first);
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
		// Tracks fileKeys this run has already claimed, on top of outFile.exists() - the only thing that lets a
		// --dry-run detect two source weapons colliding on the same sanitized fileKey (a dry run never writes, so
		// exists() alone only ever reflects state from BEFORE this run).
		Set<String> claimedKeys = new HashSet<>();

		for (File weaponFile : weaponFiles) {
			YamlConfiguration doc = YamlConfiguration.loadConfiguration(weaponFile);
			for (String title : doc.getKeys(false)) {
				ConfigurationSection body = doc.getConfigurationSection(title);
				if (body == null) continue;

				WmImportReport.WeaponEntry entry = report.weapon(title);
				importOne(title, body, projectiles, ammos, entry, weaponOutDir, dryRun, force, ammoAppends,
				         claimedKeys);
			}
		}

		File reportFile;
		try {
			if (!dryRun && !ammoAppends.isEmpty()) {
				appendAmmunition(ammoAppends, report);
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
	                       List<WmWeaponImporter.AmmoAppend> ammoAppends, Set<String> claimedKeys) {
		try {
			WmWeaponImporter.ImportedWeapon imported =
					WmWeaponImporter.importWeapon(title, body, projectiles, ammos, entry);
			if (imported == null) return;

			File outFile = new File(weaponOutDir, imported.fileKey() + ".yml");
			if (collidesWithClaimedFile(imported.fileKey(), claimedKeys, outFile) && !force) {
				entry.error("not written - " + outFile.getName() + " already exists (pass --force to overwrite)");
				return;
			}
			claimedKeys.add(imported.fileKey());

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

	/**
	 * Whether {@code fileKey} would collide with an already-claimed weapon file - either one this SAME run has
	 * already written/claimed (so a {@code --dry-run}, which never touches the filesystem, still catches two
	 * source weapons sanitizing to the same {@code fileKey}) or one already on disk from an earlier run.
	 */
	static boolean collidesWithClaimedFile(String fileKey, Set<String> claimedKeysThisRun, File outFile) {
		return claimedKeysThisRun.contains(fileKey) || outFile.exists();
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

	private void appendAmmunition(List<WmWeaponImporter.AmmoAppend> ammoAppends, WmImportReport report)
			throws IOException {
		File ammoFile = new File(bartizan.getDataFolder(), "items/ammunition.yml");
		YamlConfiguration existing = ammoFile.isFile() ? YamlConfiguration.loadConfiguration(ammoFile)
		                                               : new YamlConfiguration();

		List<WmWeaponImporter.AmmoAppend> toWrite = resolveAmmoAppends(ammoAppends, existing.getKeys(false), report);
		if (toWrite.isEmpty()) return;

		StringBuilder appendText = new StringBuilder();
		for (WmWeaponImporter.AmmoAppend ammo : toWrite) appendText.append(ammoBlock(ammo));

		Files.writeString(ammoFile.toPath(), "\n" + appendText, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
	}

	/**
	 * Which of {@code ammoAppends} are actually new {@code ammunition.yml} entries, in order, skipping any id
	 * already in {@code idsAlreadyOnDisk}. {@code sanitizeKey} collapses punctuation (e.g. {@code "5.56mm"} and
	 * {@code "5,56mm"} both -> {@code wm_5_56mm}), so two different WM ammo refs can land on the same id - tracked
	 * only for ids THIS call is about to write (not ones already on disk from an earlier run, whose original ref
	 * is unknown), a later {@code AmmoAppend} whose id repeats with a DIFFERENT {@code sourceRef} is a genuine
	 * collision and gets a {@link WmImportReport#runNote} instead of silently keeping the first one's
	 * Material/Name with no trace; the same ref imported by a second weapon (identical {@code sourceRef}) is an
	 * expected, silent dedup.
	 */
	static List<WmWeaponImporter.AmmoAppend> resolveAmmoAppends(List<WmWeaponImporter.AmmoAppend> ammoAppends,
	                                                             Collection<String> idsAlreadyOnDisk,
	                                                             WmImportReport report) {
		Set<String>          seen           = new HashSet<>(idsAlreadyOnDisk);
		Map<String, String>  claimedThisRun = new LinkedHashMap<>();
		List<WmWeaponImporter.AmmoAppend> toWrite = new ArrayList<>();

		for (WmWeaponImporter.AmmoAppend ammo : ammoAppends) {
			if (seen.contains(ammo.id())) {
				String priorSourceRef = claimedThisRun.get(ammo.id());
				if (priorSourceRef != null && !priorSourceRef.equals(ammo.sourceRef())) {
					report.runNote("ammo id collision: Reload.Ammo '" + ammo.sourceRef() + "' sanitizes to the "
					                + "same ammunition.yml id '" + ammo.id() + "' as '" + priorSourceRef + "' - "
					                + "keeping '" + priorSourceRef + "'s Material/Name, '" + ammo.sourceRef()
					                + "' discarded");
				}
				continue;
			}

			toWrite.add(ammo);
			claimedThisRun.put(ammo.id(), ammo.sourceRef());
			seen.add(ammo.id());
		}

		return toWrite;
	}

	/**
	 * One {@code AmmoAppend}'s block, hand-built rather than run through a YAML dumper (weapons-roadmap.md gate
	 * {@code HM}, §6.1) - see {@link WmYamlEmitter} for why the importer writes plain text at all. {@code
	 * ammunition.yml} is the ONE file every weapon's {@code Ammunition.Ammo_Type} resolves against, appended to
	 * (never replaced) as a single SnakeYAML document (Keystone's {@code ConfigParser}), so both free-text fields
	 * (WM's own {@code Material}/{@code Name}) must go through {@link WmYamlEmitter#escapeDoubleQuoted} - an
	 * unescaped backslash-then-quote in either one closes the quoted scalar early and corrupts every ammo entry
	 * after it in the file, not just the one being imported.
	 */
	static String ammoBlock(WmWeaponImporter.AmmoAppend ammo) {
		return ammo.id() + ":\n"
		     + "   Material: \"" + WmYamlEmitter.escapeDoubleQuoted(ammo.material()) + "\"\n"
		     + "   Name: \"" + WmYamlEmitter.escapeDoubleQuoted(ammo.name()) + "\"\n"
		     + "   Lore:\n"
		     + "      - \"&7Imported from WeaponMechanics.\"\n";
	}

	private File writeReport(WmImportReport report) throws IOException {
		File importDir = new File(bartizan.getDataFolder(), "import");
		Files.createDirectories(importDir.toPath());

		File reportFile = new File(importDir, "weaponmechanics-" + LocalDateTime.now().format(REPORT_STAMP) + ".txt");
		Files.writeString(reportFile.toPath(), report.render());
		return reportFile;
	}

}
