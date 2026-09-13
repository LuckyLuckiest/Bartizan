package org.luckyraven.bartizan.status;

import lombok.Getter;
import lombok.Setter;
import org.bukkit.boss.BossBar;
import org.jetbrains.annotations.Nullable;
import org.luckyraven.bartizan.api.weapon.BiologicalWeapon;

import java.util.UUID;

/**
 * One victim's live status bookkeeping — the runtime twin of a weapon's {@code StatusData} config, owned by
 * {@link StatusEffectService} (weapons-roadmap.md gate {@code HB}, §2.2).
 */
@Getter
@Setter
public class ActiveStatus {

	private final UUID victimId;
	@Nullable
	private UUID shooterId;
	private BiologicalWeapon weapon;
	private int  level;
	/**
	 * Tick of the most recent {@code StatusEffectService#apply} call — what {@code WeaponDeathListener} reads
	 * against {@code Status.Kill_Credit_Window} to decide whether a killer-less death is still credited.
	 */
	private long appliedTick;
	/**
	 * Absolute tick the status disappears at.
	 */
	private long expiryTick;
	/**
	 * Tick the ambient particle / contagion roll last fired, initialised to {@link #appliedTick}. Checked as
	 * {@code now - last >= interval} rather than {@code now % interval == 0} — the tick clock is wall-clock derived
	 * and sampled every 10 ticks, so a modulo check can drift past the exact multiple and never fire again
	 * (weapons-roadmap.md gate {@code HB} review item 3).
	 */
	private long lastAmbientTick;
	private long lastContagionTick;
	@Nullable
	private BossBar bossBar;
	/**
	 * Set once {@link StatusEffectService#tick()} has logged a failure ticking this status, so a persistently
	 * throwing per-tick body (e.g. a misconfigured ambient particle) warns once instead of every tick.
	 */
	private boolean tickFailureWarned;

	public ActiveStatus(UUID victimId, @Nullable UUID shooterId, BiologicalWeapon weapon, int level, long appliedTick,
	                    long expiryTick) {
		this.victimId          = victimId;
		this.shooterId         = shooterId;
		this.weapon            = weapon;
		this.level             = level;
		this.appliedTick       = appliedTick;
		this.expiryTick        = expiryTick;
		this.lastAmbientTick   = appliedTick;
		this.lastContagionTick = appliedTick;
	}

}
