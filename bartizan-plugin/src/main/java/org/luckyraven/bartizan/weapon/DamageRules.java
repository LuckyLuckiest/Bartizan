package org.luckyraven.bartizan.weapon;

import org.bukkit.Bukkit;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Team;
import org.jetbrains.annotations.Nullable;
import org.luckyraven.bartizan.api.combat.CombatEligibility;
import org.luckyraven.bartizan.api.weapon.dto.DamageData;
import org.luckyraven.bartizan.api.weapon.dto.ExplosionData;
import org.luckyraven.bartizan.api.weapon.dto.ThrowableData;

/**
 * Resolves {@code Damage.Owner_Immunity} / {@code Damage.Ignore_Teams} (and the throwable equivalents) against a
 * shooter/victim pair (weapons-roadmap.md gate {@code HF}, §4), plus {@link CombatEligibility} (BZ-RT-03/BZ-RT-20):
 * a victim a consumer plugin has marked un-hittable (downed, etc.) is "protected" here too, so every caller that
 * already routes through this shared filter — hitscan/stepped raytrace entity hits and {@code ExplosionHandler}
 * blast victims alike — honours it without each needing its own check. A "protected" victim should take no damage —
 * callers skip it rather than stopping a raytrace ray on it.
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
		if (victim instanceof Player player && !CombatEligibility.resolve().canBeHit(player)) return true;

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
