package org.luckyraven.bartizan.util;

import org.apache.logging.log4j.Logger;
import org.luckyraven.bartizan.Bartizan;
import org.luckyraven.bartizan.file.BartizanMessages;
import org.luckyraven.bartizan.file.BartizanSettings;
import org.luckyraven.keystone.util.ChatUtil;

/**
 * Bartizan's copy of Gangland's {@code GanglandChatUtil} (bartizan.md §1.2): the same {@code %money_symbol%}
 * substitution and prefixed-message helpers, routed through {@link BartizanMessages} / {@link BartizanSettings}
 * instead of Gangland's.
 */
public final class BartizanChatUtil extends ChatUtil {

	private BartizanChatUtil() {
		super();
	}

	public static String color(final String message) {
		return color(message, new Replacement("%money_symbol%", BartizanSettings.getMoneySymbol()));
	}

	public static String prefixMessage(String message) {
		return color(BartizanMessages.PREFIX + message);
	}

	public static String commandMessage(String message) {
		return color(BartizanMessages.COMMAND_PREFIX + message);
	}

	public static String errorMessage(String message) {
		return color(BartizanMessages.ERROR_PREFIX + message);
	}

	public static String informationMessage(String message) {
		return color(BartizanMessages.INFORMATION_PREFIX + message);
	}

	public static void sendToOperators(String permission, String message) {
		ChatUtil.sendToOperators(permission, commandMessage(message));
	}

	public static void sendToOperators(String permission, String message, Logger logger, boolean sendAsWarn) {
		ChatUtil.sendToOperators(permission, commandMessage(message), logger, sendAsWarn);
	}

	// Deviation from bartizan.md §1.2 (recorded in bartizan.md §7, task B9): the checklist says to replace
	// Gangland.SHORT_PREFIX with Bartizan.SHORT_PREFIX ("btz") here, but the command word that actually appears in
	// commands.json usage strings and in the registered PluginCommand (§C.5: "command bartizan with
	// aliases: [btz, weapon]") is "bartizan" (FULL_PREFIX), not "btz". Using SHORT_PREFIX would make this cosmetic
	// highlighter never match real help text. FULL_PREFIX is used instead so "/bartizan ..." actually highlights.
	public static String commandDesign(String command) {
		return color(command.replace("/" + Bartizan.FULL_PREFIX, "&6/" + Bartizan.FULL_PREFIX + "&7")
		                    .replace("<", "&5<&7")
		                    .replace(">", "&5>&7")
		                    .replace(" - ", " &c-&r ")
		                    .replaceAll("[\\[\\],]", ""));
	}

	public static String confirmCommand(String[] args) {
		return color("&cYou need to confirm using &e/" + Bartizan.FULL_PREFIX + " " + String.join(" ", args) +
		             " confirm &cto execute the command.");
	}

	public static String setArguments(String arguments, String command) {
		return color(arguments + commandDesign(command));
	}

}
