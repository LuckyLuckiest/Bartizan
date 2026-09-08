package org.luckyraven.bartizan.command;

import lombok.Getter;
import org.bukkit.command.CommandSender;
import org.luckyraven.bartizan.Bartizan;
import org.luckyraven.bartizan.command.data.InformationManager;
import org.luckyraven.bartizan.ammo.AmmunitionManager;
import org.luckyraven.keystone.command.Command;
import org.luckyraven.keystone.command.argument.Argument;
import org.luckyraven.keystone.bean.command.CommandHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code /bartizan ammo} (alias {@code ammunition}) — rewrite of {@code W/command/AmmunitionCommand.java} per the
 * same pattern as {@link WeaponCommand} (bartizan.md §1.4).
 */
@Getter
@CommandHandler
public final class AmmunitionCommand extends Command {

	private final Bartizan          bartizan;
	private final AmmunitionManager ammunitionManager;
	private final HelpInfo          helpInfo;

	public AmmunitionCommand(Bartizan bartizan,
	                         InformationManager informationManager,
	                         AmmunitionManager ammunitionManager) {
		super(bartizan, Bartizan.FULL_PREFIX, "ammo", true, "ammunition");

		this.bartizan          = bartizan;
		this.ammunitionManager = ammunitionManager;
		this.helpInfo          = new HelpInfo();

		var list = informationManager.getCommands().entrySet()
				.stream()
				.filter(entry -> entry.getKey().startsWith("ammunition"))
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
		Argument give = new AmmunitionGiveCommand(bartizan, getArgumentTree(), getArgument(), ammunitionManager);
		Argument info = new AmmunitionInfoCommand(bartizan, getArgumentTree(), getArgument(), ammunitionManager);
		Argument list = new AmmunitionListCommand(bartizan, getArgumentTree(), getArgument(), ammunitionManager);

		List<Argument> arguments = new ArrayList<>();

		arguments.add(give);
		arguments.add(info);
		arguments.add(list);

		getArgument().addAllSubArguments(arguments);
	}

	@Override
	protected void help(CommandSender sender, int page) {
		helpInfo.displayHelp(sender, page, "Ammunition");
	}

}
