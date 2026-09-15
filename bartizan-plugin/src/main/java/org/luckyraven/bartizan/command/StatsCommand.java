package org.luckyraven.bartizan.command;

import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;
import org.luckyraven.bartizan.Bartizan;
import org.luckyraven.bartizan.command.data.InformationManager;
import org.luckyraven.bartizan.file.BartizanMessages;
import org.luckyraven.bartizan.file.WeaponLoader;
import org.luckyraven.bartizan.stats.PlayerStats;
import org.luckyraven.bartizan.stats.StatsService;
import org.luckyraven.bartizan.stats.WeaponStat;
import org.luckyraven.bartizan.util.BartizanChatUtil;
import org.luckyraven.keystone.command.CommandMessages;
import org.luckyraven.keystone.command.Command;
import org.luckyraven.keystone.command.argument.Argument;
import org.luckyraven.keystone.command.argument.types.OptionalArgument;
import org.luckyraven.keystone.bean.command.CommandHandler;
import org.luckyraven.keystone.datastructure.JsonFormatter;
import org.luckyraven.keystone.persistence.FileHandler;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code /bartizan stats [player] [weapon]} (weapons-roadmap.md gate {@code HK}) — no nested noun, same shape as
 * {@code ReloadCommand}/{@code DebugCommand}. Console-usable when a player is named (an offline player's file is
 * loaded straight off disk through {@link StatsService#lookup}); the bare, self-lookup form needs a player sender.
 */
@Getter
@CommandHandler
public final class StatsCommand extends Command {

	private final Bartizan     bartizan;
	private final StatsService statsService;
	private final WeaponLoader weaponLoader;
	private final HelpInfo     helpInfo;

	public StatsCommand(Bartizan bartizan, InformationManager informationManager, StatsService statsService,
	                    WeaponLoader weaponLoader) {
		super(bartizan, Bartizan.FULL_PREFIX, "stats", false);

		this.bartizan     = bartizan;
		this.statsService = statsService;
		this.weaponLoader = weaponLoader;
		this.helpInfo     = new HelpInfo();

		var list = informationManager.getCommands().entrySet()
				.stream()
				.filter(entry -> entry.getKey().startsWith("stats"))
				.sorted(Map.Entry.comparingByKey())
				.map(Map.Entry::getValue)
				.toList();

		helpInfo.addAll(list);
	}

	@Override
	protected void onExecute(Argument argument, CommandSender commandSender, String[] arguments) {
		Player player = requirePlayer(commandSender);
		if (player == null) return;

		sendStats(commandSender, player.getUniqueId(), player.getName(), null);
	}

	@Override
	protected void initializeArguments() {
		OptionalArgument playerArg = new OptionalArgument(bartizan, getArgumentTree(), (argument, sender, args) ->
				handlePlayer(sender, args[1]),
				sender -> Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());

		OptionalArgument weaponArg = new OptionalArgument(bartizan, getArgumentTree(), (argument, sender, args) ->
				handlePlayerAndWeapon(sender, args[1], args[2]),
				sender -> weaponLoader.getFiles().stream().map(FileHandler::getName).toList());

		playerArg.setDisplayName("player");
		weaponArg.setDisplayName("weapon");

		playerArg.addSubArgument(weaponArg);
		getArgument().addAllSubArguments(List.of(playerArg));
	}

	@Override
	protected void help(CommandSender sender, int page) {
		helpInfo.displayHelp(sender, page, "Stats");
	}

	private void handlePlayer(CommandSender sender, String name) {
		UUID id = resolveUuid(sender, name);
		if (id == null) return;

		sendStats(sender, id, name, null);
	}

	private void handlePlayerAndWeapon(CommandSender sender, String name, String weaponName) {
		UUID id = resolveUuid(sender, name);
		if (id == null) return;

		sendStats(sender, id, name, weaponName.toLowerCase());
	}

	@Nullable
	private UUID resolveUuid(CommandSender sender, String name) {
		Player online = Bukkit.getPlayerExact(name);
		if (online != null) return online.getUniqueId();

		// Bukkit#getOfflinePlayer(String) is deprecated because it can block on a Mojang lookup for a name that
		// never joined this server — scanning the local, already-cached getOfflinePlayers() avoids that entirely.
		for (OfflinePlayer candidate : Bukkit.getOfflinePlayers()) {
			if (name.equalsIgnoreCase(candidate.getName())) return candidate.getUniqueId();
		}

		sender.sendMessage(BartizanMessages.PLAYER_NOT_FOUND.toString().replace("%player%", name));
		return null;
	}

	@Nullable
	private Player requirePlayer(CommandSender sender) {
		if (sender instanceof Player player) return player;

		sender.sendMessage(CommandMessages.playerOnly());
		return null;
	}

	private void sendStats(CommandSender sender, UUID playerId, String displayName, @Nullable String weaponFilter) {
		PlayerStats stats = statsService.lookup(playerId);
		if (stats == null) {
			sender.sendMessage(BartizanMessages.NO_STATS.toString().replace("%player%", displayName));
			return;
		}

		String body = weaponFilter != null ? weaponBlock(displayName, stats, weaponFilter)
		                                   : totals(displayName, stats);

		JsonFormatter formatter = new JsonFormatter();
		sender.sendMessage(formatter.formatToJson(BartizanChatUtil.color(body), " ".repeat(3)));
	}

	private String totals(String name, PlayerStats stats) {
		int    shots = 0, hits = 0, headshots = 0, kills = 0, assists = 0;
		double damage = 0, longest = 0;

		for (WeaponStat stat : stats.weapons.values()) {
			shots     += stat.shots;
			hits      += stat.hits;
			headshots += stat.headshots;
			kills     += stat.kills;
			assists   += stat.assists;
			damage    += stat.damageDealt;
			longest   = Math.max(longest, stat.longestKillDistance);
		}
		double accuracy = shots > 0 ? (hits * 100.0 / shots) : 0;

		StringBuilder info = new StringBuilder();
		info.append("&7Player&8: &b").append(name)
		    .append("\n&7Shots&8: &b").append(shots)
		    .append("\n&7Hits&8: &b").append(hits)
		    .append("\n&7Accuracy&8: &b").append(String.format("%.1f", accuracy)).append("%")
		    .append("\n&7Headshots&8: &b").append(headshots)
		    .append("\n&7Kills&8: &b").append(kills)
		    .append("\n&7Assists&8: &b").append(assists)
		    .append("\n&7Deaths&8: &b").append(stats.deaths)
		    .append("\n&7Damage Dealt&8: &b").append(String.format("%.1f", damage))
		    .append("\n&7Longest Kill&8: &b").append(String.format("%.1f", longest)).append(" blocks");

		if (!stats.weapons.isEmpty()) {
			info.append("\n&7Weapons&8:");
			stats.weapons.forEach((weaponName, stat) -> info.append("\n &8- &e").append(weaponName)
			        .append(" &8(&7").append(stat.shots).append(" shots, ").append(stat.hits).append(" hits, ")
			        .append(stat.kills).append(" kills&8)"));
		}

		return info.toString();
	}

	private String weaponBlock(String name, PlayerStats stats, String weaponName) {
		WeaponStat stat = stats.weapons.get(weaponName);
		if (stat == null) {
			return "&7Player&8: &b" + name + "\n&cNo stats recorded for &e" + weaponName + "&c.";
		}

		double accuracy = stat.shots > 0 ? (stat.hits * 100.0 / stat.shots) : 0;

		StringBuilder info = new StringBuilder();
		info.append("&7Player&8: &b").append(name)
		    .append("\n&7Weapon&8: &b").append(weaponName)
		    .append("\n&7Shots&8: &b").append(stat.shots)
		    .append("\n&7Hits&8: &b").append(stat.hits)
		    .append("\n&7Accuracy&8: &b").append(String.format("%.1f", accuracy)).append("%")
		    .append("\n&7Headshots&8: &b").append(stat.headshots)
		    .append("\n&7Kills&8: &b").append(stat.kills)
		    .append("\n&7Assists&8: &b").append(stat.assists)
		    .append("\n&7Damage Dealt&8: &b").append(String.format("%.1f", stat.damageDealt))
		    .append("\n&7Longest Kill&8: &b").append(String.format("%.1f", stat.longestKillDistance)).append(" blocks");

		stat.zoneHits.forEach((zone, count) ->
				info.append("\n &8- &7").append(zone.name()).append("&8: &b").append(count));

		return info.toString();
	}

}
