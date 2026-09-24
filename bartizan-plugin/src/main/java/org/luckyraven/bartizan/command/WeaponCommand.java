package org.luckyraven.bartizan.command;

import lombok.Getter;
import org.bukkit.command.CommandSender;
import org.luckyraven.bartizan.Bartizan;
import org.luckyraven.bartizan.command.data.InformationManager;
import org.luckyraven.bartizan.file.WeaponLoader;
import org.luckyraven.bartizan.weapon.WeaponManager;
import org.luckyraven.bartizan.configuration.WeaponAddon;
import org.luckyraven.keystone.command.Command;
import org.luckyraven.keystone.command.argument.Argument;
import org.luckyraven.keystone.bean.command.CommandHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code /bartizan weapon} (bartizan.md §1.4) — rewrite of {@code W/command/WeaponCommand.java}: base class is
 * Keystone's {@link Command} directly (no Gangland adapter), permission namespace {@code bartizan.command.weapon}
 * via the prefix overload, help rendered through {@link HelpInfo} against {@link InformationManager}'s
 * {@code weapon_*} entries.
 *
 * <p>The {@code user} flag ({@code Command}'s 4th constructor argument) gates the WHOLE {@code /bartizan weapon}
 * tree, not any one sub-argument — so it had to flip from {@code true} to {@code false} at gate {@code HD} for
 * {@code give <player> <weapon> [amount]} to be usable from console. {@code info}/{@code list} still work
 * unchanged for players; {@code get} enforces its own self-only check since the framework no longer does it for
 * this command.
 */
@Getter
@CommandHandler
public final class WeaponCommand extends Command {

	private final Bartizan       bartizan;
	private final WeaponManager  weaponManager;
	private final WeaponAddon    weaponAddon;
	private final WeaponLoader   weaponLoader;
	private final HelpInfo       helpInfo;

	public WeaponCommand(Bartizan bartizan,
	                     InformationManager informationManager,
	                     WeaponManager weaponManager,
	                     WeaponAddon weaponAddon,
	                     WeaponLoader weaponLoader) {
		super(bartizan, Bartizan.FULL_PREFIX, "weapon", false);

		this.bartizan      = bartizan;
		this.weaponManager = weaponManager;
		this.weaponAddon   = weaponAddon;
		this.weaponLoader  = weaponLoader;
		this.helpInfo      = new HelpInfo();

		var list = informationManager.getCommands().entrySet()
				.stream()
				.filter(entry -> entry.getKey().startsWith("weapon"))
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
		Argument give = new WeaponGiveCommand(bartizan, getArgumentTree(), getArgument(), weaponManager, weaponAddon);
		Argument get  = new WeaponGetCommand(bartizan, getArgumentTree(), getArgument(), weaponManager, weaponLoader);
		Argument info = new WeaponInfoCommand(bartizan, getArgumentTree(), getArgument(), weaponManager, weaponAddon);
		Argument list = new WeaponListCommand(bartizan, getArgumentTree(), getArgument(), weaponAddon);
		Argument skin = new WeaponSkinCommand(bartizan, getArgumentTree(), getArgument(), weaponManager);

		List<Argument> arguments = new ArrayList<>();

		arguments.add(give);
		arguments.add(get);
		arguments.add(info);
		arguments.add(list);
		arguments.add(skin);

		getArgument().addAllSubArguments(arguments);
	}

	@Override
	protected void help(CommandSender sender, int page) {
		helpInfo.displayHelp(sender, page, "Weapon");
	}

}
