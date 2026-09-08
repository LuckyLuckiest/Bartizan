package org.luckyraven.bartizan.command.wearable;

import lombok.Getter;
import org.bukkit.command.CommandSender;
import org.luckyraven.bartizan.Bartizan;
import org.luckyraven.bartizan.command.HelpInfo;
import org.luckyraven.bartizan.command.data.InformationManager;
import org.luckyraven.bartizan.wearable.WearableAddon;
import org.luckyraven.keystone.command.Command;
import org.luckyraven.keystone.command.argument.Argument;
import org.luckyraven.keystone.bean.command.CommandHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code /bartizan wearable} (bartizan.md §1.1 group L/M/N table: {@code command/wearable/ItemWearableCommand.java}
 * → {@code command/wearable/WearableCommand.java}, REWRITE). Unlike the Gangland original — a {@code SubArgument}
 * attached under the core's {@code /glw item} via {@code ItemWearableContribution} (deleted, §1.1) — this is now a
 * top-level {@link Command} exactly like {@link org.luckyraven.bartizan.command.WeaponCommand}, so its children's
 * argument indices shift down one level (Gangland's {@code /glw item wearable give <name>} had {@code args[3]} for
 * {@code <name>}; Bartizan's {@code /bartizan wearable give <name>} has it at {@code args[2]}, matching
 * {@code WeaponGiveCommand}'s own indexing).
 */
@Getter
@CommandHandler
public final class WearableCommand extends Command {

	private final Bartizan      bartizan;
	private final WearableAddon wearableAddon;
	private final HelpInfo      helpInfo;

	public WearableCommand(Bartizan bartizan,
	                       InformationManager informationManager,
	                       WearableAddon wearableAddon) {
		super(bartizan, Bartizan.FULL_PREFIX, "wearable", true);

		this.bartizan      = bartizan;
		this.wearableAddon = wearableAddon;
		this.helpInfo      = new HelpInfo();

		var list = informationManager.getCommands().entrySet()
				.stream()
				.filter(entry -> entry.getKey().startsWith("wearable"))
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
		Argument give = new WearableGiveCommand(bartizan, getArgumentTree(), getArgument(), wearableAddon);
		Argument info = new WearableInfoCommand(bartizan, getArgumentTree(), getArgument(), wearableAddon);
		Argument list = new WearableListCommand(bartizan, getArgumentTree(), getArgument(), wearableAddon);

		List<Argument> arguments = new ArrayList<>();

		arguments.add(give);
		arguments.add(info);
		arguments.add(list);

		getArgument().addAllSubArguments(arguments);
	}

	@Override
	protected void help(CommandSender sender, int page) {
		helpInfo.displayHelp(sender, page, "Wearable");
	}

}
