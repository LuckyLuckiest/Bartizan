package org.luckyraven.bartizan.command;

import org.bukkit.command.CommandSender;
import org.luckyraven.bartizan.command.data.CommandInformation;
import org.luckyraven.bartizan.util.BartizanChatUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Bartizan's copy of Gangland's {@code command.HelpInfo} (bartizan.md §1.4) — paginated per-command help. Not
 * separately named in the checklist's B16 file list (§2), but every top-level command it names
 * ({@code WeaponCommand}, {@code AmmunitionCommand}, the rewritten {@code WearableCommand}, {@code DebugCommand},
 * {@code ReloadCommand}) needs it: §1.4 drops Gangland's {@code command.Command} adapter (the class that used to
 * carry a {@code HelpInfo} field), so this small shared utility is extracted once rather than five copies of the
 * same pagination loop — recorded in bartizan.md §7 as an extra file beyond the task's own rough count, same
 * pattern earlier groups (B7/B12/B13/B14) recorded when the checklist's per-task file count was an estimate.
 *
 * <p>The empty-list message is a plain literal (no {@code BartizanMessages} member) — the checklist's exhaustive
 * 18-entry table (§1.5) has no equivalent to Gangland's {@code Messages.COMMAND_HELP_EMPTY}, and B8's own
 * deviation note already establishes the house rule for this stream: don't invent a message key the source-of-truth
 * table doesn't define.
 */
public final class HelpInfo {

	private final List<CommandInformation> list;
	private final int                      breaks;

	public HelpInfo() {
		this.list   = new ArrayList<>();
		this.breaks = 7;
	}

	public void addAll(List<CommandInformation> elements) {
		list.addAll(elements);
	}

	public int size() {
		return list.size();
	}

	public List<CommandInformation> getList() {
		return Collections.unmodifiableList(list);
	}

	public int getMaxPages() {
		return (list.size() + breaks - 1) / breaks;
	}

	public void displayHelp(CommandSender sender, int page, String title) {
		if (list.isEmpty()) {
			sender.sendMessage(BartizanChatUtil.color("&cNo commands available."));
			return;
		}

		int maxPages = getMaxPages();
		if (page < 1) throw new IllegalArgumentException("Cannot get page less than 1");
		if (page > maxPages) throw new IllegalArgumentException("Cannot exceed maximum allowed pages");

		String header = BartizanChatUtil.color(
				"&3Oo&3&m------&r &8&l[&bB&fT&bZ&8&l]&7 " + title + " &8[&7" + page + "&5/&7" + maxPages +
				"&8] &3&m------&3oO");

		sender.sendMessage("");
		sender.sendMessage(header);
		sender.sendMessage("");

		int startIndex = (page - 1) * breaks;
		int endIndex   = Math.min(startIndex + breaks, size());

		for (int index = startIndex; index < endIndex; index++) {
			sender.sendMessage(BartizanChatUtil.commandDesign(list.get(index).toString()));
		}
	}

}
