package org.luckyraven.bartizan.command;

import lombok.CustomLog;
import lombok.Getter;
import org.bukkit.command.CommandSender;
import org.luckyraven.bartizan.Bartizan;
import org.luckyraven.bartizan.bootstrap.BartizanContext;
import org.luckyraven.bartizan.command.data.InformationManager;
import org.luckyraven.bartizan.util.BartizanChatUtil;
import org.luckyraven.keystone.command.Command;
import org.luckyraven.keystone.command.argument.Argument;
import org.luckyraven.keystone.bean.command.CommandHandler;
import org.luckyraven.keystone.persistence.FileManager;

import java.util.Map;

/**
 * {@code /bartizan reload} (bartizan.md §1.4: a new command, Bartizan has no Gangland original to port — the
 * checklist names it alongside {@code DebugCommand} as one of the two non-ported command classes group K adds).
 * Reloads every {@code FileInitializer}-backed file through {@link FileManager#initializeAll()} and runs the
 * {@code BeanLifecycle} reload pass through {@link BartizanContext#reloadBeans()} — the same two steps
 * {@code Gangland.ReloadPlugin.filesReload()} / {@code .reload()} perform for the core plugin, scaled down to what
 * Bartizan actually owns (no scoreboard/inventory/gang concerns to reload).
 */
@CustomLog
@Getter
@CommandHandler
public final class ReloadCommand extends Command {

	private final Bartizan       bartizan;
	private final BartizanContext context;
	private final HelpInfo       helpInfo;

	public ReloadCommand(Bartizan bartizan,
	                     InformationManager informationManager,
	                     BartizanContext context) {
		super(bartizan, Bartizan.FULL_PREFIX, "reload", false, "rl");

		this.bartizan = bartizan;
		this.context  = context;
		this.helpInfo = new HelpInfo();

		var list = informationManager.getCommands().entrySet()
				.stream()
				.filter(entry -> entry.getKey().startsWith("bartizan_reload"))
				.sorted(Map.Entry.comparingByKey())
				.map(Map.Entry::getValue)
				.toList();

		helpInfo.addAll(list);
	}

	@Override
	protected void onExecute(Argument argument, CommandSender commandSender, String[] arguments) {
		String permission = getPermission();

		BartizanChatUtil.sendToOperators(permission, "&bReloading&7 Bartizan...");

		try {
			FileManager fileManager = context.get(FileManager.class);
			if (fileManager != null) {
				fileManager.initializeAll();
			}

			context.reloadBeans();

			BartizanChatUtil.sendToOperators(permission, "&aReload has been completed.");
		} catch (Throwable throwable) {
			BartizanChatUtil.sendToOperators(permission, "&cThere was a problem reloading Bartizan!");
			log.error(throwable.getMessage(), throwable);
		}
	}

	@Override
	protected void initializeArguments() {
		// No sub-arguments — a bare /bartizan reload is the whole surface.
	}

	@Override
	protected void help(CommandSender sender, int page) {
		helpInfo.displayHelp(sender, page, "Reload");
	}

}
