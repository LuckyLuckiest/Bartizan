package org.luckyraven.bartizan.weapon;

import org.bukkit.Bukkit;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Team;
import org.jetbrains.annotations.Nullable;
import org.luckyraven.bartizan.api.weapon.dto.DamageData;
import org.luckyraven.bartizan.api.weapon.dto.ExplosionData;
import org.luckyraven.bartizan.api.weapon.dto.ThrowableData;

/**
 * Resolves {@code Damage.Owner_Immunity} / {@code Damage.Ignore_Teams} (and the throwable equivalents) against a
 * shooter/victim pair (weapons-roadmap.md gate {@code HF}, §4). A "protected" victim should take no damage — callers
 * skip it rather than stopping a raytrace ray on it.
 */
public final class DamageRules {

	private DamageRules() {
	}

	public static boolean isProtected(DamageData data, @Nullable LivingEntity shooter, LivingEntity victim) {
		return isProtected(data.isOwnerImmunity(), data.isIgnoreTeams(), shooter, victim);
	}

	public static boolean isProtected(ThrowableData data, @Nullable LivingEntity shooter, LivingEntity victim) {
		return isProtected(data.isOwnerImmunity(), data.isIgnoreTeams(), shooter, victim);
	}

	/**
	 * Gate {@code HI-a} — the unified {@code ExplosionHandler} reads {@code Owner_Immunity}/{@code Ignore_Teams}
	 * off {@link ExplosionData} directly rather than the gun/throwable DTO the blast originated from.
	 */
	public static boolean isProtected(ExplosionData data, @Nullable LivingEntity shooter, LivingEntity victim) {
		return isProtected(data.isOwnerImmunity(), data.isIgnoreTeams(), shooter, victim);
	}

	private static boolean isProtected(boolean ownerImmunity, boolean ignoreTeams, @Nullable LivingEntity shooter,
	                                   LivingEntity victim) {
		if (shooter == null) return false;
		if (victim.equals(shooter)) return ownerImmunity;

		return ignoreTeams && sameTeam(shooter, victim);
	}

	private static boolean sameTeam(LivingEntity a, LivingEntity b) {
		ScoreboardManager manager = Bukkit.getScoreboardManager();
		if (manager == null) return false;

		Scoreboard board = manager.getMainScoreboard();
		Team       teamA = board.getEntryTeam(entryName(a));
		if (teamA == null) return false;

		return teamA.equals(board.getEntryTeam(entryName(b)));
	}

	private static String entryName(LivingEntity entity) {
		return entity instanceof Player player ? player.getName() : entity.getUniqueId().toString();
	}

}
