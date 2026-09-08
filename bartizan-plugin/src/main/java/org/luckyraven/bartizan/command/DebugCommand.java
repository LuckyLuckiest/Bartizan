package org.luckyraven.bartizan.command;

import lombok.Getter;
import org.bukkit.command.CommandSender;
import org.luckyraven.bartizan.Bartizan;
import org.luckyraven.bartizan.command.data.InformationManager;
import org.luckyraven.bartizan.weapon.WeaponManager;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.keystone.command.Command;
import org.luckyraven.keystone.command.argument.Argument;
import org.luckyraven.keystone.bean.command.CommandHandler;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * {@code /bartizan debug} (bartizan.md §1.1: {@code command/DebugWeaponContribution.java} →
 * {@code command/DebugCommand.java}, REWRITE). The original was a {@code CommandContribution} attaching
 * {@code weapon} under Gangland core's {@code /glw debug} parent; Bartizan has no core debug command to attach to,
 * so this is now a real top-level {@code /bartizan debug} command whose {@code weapon} argument reproduces
 * {@code DebugWeaponContribution.create(...)} verbatim (lists the UUID of every currently loaded weapon).
 */
@Getter
@CommandHandler
public final class DebugCommand extends Command {

	private final Bartizan      bartizan;
	private final WeaponManager weaponManager;
	private final HelpInfo      helpInfo;

	public DebugCommand(Bartizan bartizan,
	                    InformationManager informationManager,
	                    WeaponManager weaponManager) {
		super(bartizan, Bartizan.FULL_PREFIX, "debug", false);

		this.bartizan      = bartizan;
		this.weaponManager = weaponManager;
		this.helpInfo      = new HelpInfo();

		var list = informationManager.getCommands().entrySet()
				.stream()
				.filter(entry -> entry.getKey().startsWith("bartizan_debug"))
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
		Argument weapon = new Argument(bartizan, "weapon", getArgumentTree(), (argument, sender, args) -> {
			Collection<Weapon> values = weaponManager.getWeapons().values();
			for (Weapon weaponInstance : values) {
				sender.sendMessage(weaponInstance.getUuid().toString());
			}
		});

		List<Argument> arguments = new ArrayList<>();
		arguments.add(weapon);

		getArgument().addAllSubArguments(arguments);
	}

	@Override
	protected void help(CommandSender sender, int page) {
		helpInfo.displayHelp(sender, page, "Debug");
	}

}
