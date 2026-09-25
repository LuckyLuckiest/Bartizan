package org.luckyraven.bartizan.status;

import com.cryptomorin.xseries.particles.XParticle;
import lombok.CustomLog;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;
import org.luckyraven.bartizan.api.combat.CombatEligibility;
import org.luckyraven.bartizan.api.event.WeaponStatusApplyEvent;
import org.luckyraven.bartizan.api.event.WeaponStatusExpireEvent;
import org.luckyraven.bartizan.api.event.WeaponStatusExpireEvent.Reason;
import org.luckyraven.bartizan.api.weapon.BiologicalWeapon;
import org.luckyraven.bartizan.api.weapon.dto.EffectHook;
import org.luckyraven.bartizan.api.weapon.dto.StatusData;
import org.luckyraven.bartizan.effect.EffectContext;
import org.luckyraven.bartizan.effect.EffectRunner;
import org.luckyraven.bartizan.effect.impl.ParticleHookEffect;
import org.luckyraven.bartizan.util.BartizanChatUtil;
import org.luckyraven.bartizan.wearable.WearableService;
import org.luckyraven.keystone.bean.BeanLifecycle;
import org.luckyraven.keystone.timer.RepeatingTimer;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Tracks every victim's live biological status and drives the feedback the roadmap asks for: a boss bar, ambient
 * particles visible to everyone nearby, a contagion roll, expiry, and cure — see {@code weapons-roadmap.md} gate
 * {@code HB} §2.2.
 *
 * <p>{@link #tick()} and every arithmetic helper below are driven off the injected {@code tickClock} rather than a
 * live scheduler, so the whole service is unit-testable without standing up a real {@link RepeatingTimer} — the
 * timer {@link #start()} creates exists only to drive {@link #tick()} in production.
 */
@CustomLog
public class StatusEffectService implements BeanLifecycle {

	/**
	 * How often {@link #tick()} runs once the service's own timer is started. Ambient/contagion cadences are
	 * expressed as tick counts and checked against each {@link ActiveStatus}'s own last-fired tick
	 * ({@code now - last >= interval}), not a modulo against the clock — so a cadence shorter than this interval
	 * still fires (just at this interval's granularity) instead of silently drifting past its exact multiple.
	 */
	private static final long TICK_INTERVAL = 10L;

	private final JavaPlugin      plugin;
	private final EffectRunner    effectRunner;
	private final WearableService wearableService;
	private final LongSupplier    tickClock;
	private final Random          random;

	private final Map<UUID, ActiveStatus> active = new HashMap<>();

	@Nullable
	private RepeatingTimer timer;

	public StatusEffectService(JavaPlugin plugin, EffectRunner effectRunner, WearableService wearableService,
	                           LongSupplier tickClock, Random random) {
		this.plugin          = plugin;
		this.effectRunner    = effectRunner;
		this.wearableService = wearableService;
		this.tickClock       = tickClock;
		this.random          = random;
	}

	/**
	 * Applies (or stacks onto) {@code victim}'s status for {@code weapon} at the given charge level. The
	 * {@code Status.Cure.Wearable_Trait} wearable trait (a {@code null} trait means no reduction) reduces the
	 * incoming level first — a reduction to zero or below applies nothing and fires no event at all.
	 *
	 * @return {@code true} if a status was actually created or updated — {@code false} if the wearable-trait
	 * 		reduction zeroed the level or {@link WeaponStatusApplyEvent} was cancelled, in which case the caller must
	 * 		not treat this hit as having landed (e.g. skip the potion payload, skip a contagion spread message).
	 */
	public boolean apply(LivingEntity victim, @Nullable LivingEntity shooter, BiologicalWeapon weapon, int level) {
		StatusData statusData = weapon.getBiologicalData().getStatus();

		String wearableTrait  = statusData.getCure().wearableTrait();
		int    sealed         = wearableTrait != null ? wearableService.traitLevel(victim, wearableTrait) : 0;
		int    effectiveLevel = level - sealed;
		if (effectiveLevel <= 0) return false;

		// a player a consumer ruled un-hittable (downed, ...) catches nothing - a contagion spread reaches here
		// without ever passing the raytrace's own eligibility filter (BZ-RT-20). Resolved lazily at each use.
		if (victim instanceof Player player && !CombatEligibility.resolve().canBeHit(player)) return false;

		WeaponStatusApplyEvent event = new WeaponStatusApplyEvent(weapon, shooter, victim, effectiveLevel);
		Bukkit.getPluginManager().callEvent(event);
		if (event.isCancelled()) return false;

		long now       = tickClock.getAsLong();
		UUID victimId  = victim.getUniqueId();
		UUID shooterId = shooter != null ? shooter.getUniqueId() : null;

		ActiveStatus status = active.get(victimId);

		if (status == null) {
			int startingLevel = Math.min(effectiveLevel, statusData.getMaxLevel());
			status = new ActiveStatus(victimId, shooterId, weapon, startingLevel, now,
			                          now + (long) statusData.getDurationPerLevel() * startingLevel);
			active.put(victimId, status);
		} else {
			restack(status, statusData, effectiveLevel, now);
			status.setWeapon(weapon);
			if (shooterId != null) status.setShooterId(shooterId);
			status.setAppliedTick(now);
		}

		if (victim instanceof Player player) {
			ensureBossBar(status, statusData, player, now);
			updateBossBar(status, statusData, now);
		}

		EffectContext ctx = EffectContext.builder().weapon(weapon).source(shooter).victim(victim)
		                                .level(status.getLevel()).build();
		effectRunner.run(weapon, EffectHook.ON_STATUS_APPLY, ctx);

		return true;
	}

	/**
	 * Starts the {@code tick()} timer. Called once, from the {@code WiringConfig} bean's own construction (the
	 * "lifecycle start" the roadmap allows) rather than lazily from {@link #apply} — a lazy start would require a
	 * real Bukkit scheduler the moment the first shot lands, which is exactly what {@link #apply}/{@link #tick} are
	 * meant to stay independent of for testing. Idempotent.
	 */
	public void start() {
		if (timer != null) return;

		timer = new RepeatingTimer(plugin, TICK_INTERVAL, ignored -> tick());
		timer.start(false);
	}

	private void restack(ActiveStatus status, StatusData statusData, int effectiveLevel, long now) {
		switch (statusData.getStacking()) {
			case REFRESH -> status.setExpiryTick(now + (long) statusData.getDurationPerLevel() * status.getLevel());
			case EXTEND -> status.setExpiryTick(
					status.getExpiryTick() + (long) statusData.getDurationPerLevel() * effectiveLevel);
			case ESCALATE -> {
				int escalated = Math.min(status.getLevel() + 1, statusData.getMaxLevel());
				status.setLevel(escalated);
				status.setExpiryTick(now + (long) statusData.getDurationPerLevel() * escalated);
			}
			case IGNORE -> {
				// level/expiry untouched — the hit still landed (appliedTick still advances in the caller) but
				// stacking rules say an already-stacked victim gets no further mechanical change.
			}
		}
	}

	public Optional<ActiveStatus> activeOn(UUID victim) {
		return Optional.ofNullable(active.get(victim));
	}

	/**
	 * The tick-equivalent clock this service reads applications/expiry against — exposed so
	 * {@code WeaponDeathListener} can measure "how long ago" an {@link ActiveStatus#getAppliedTick()} was without
	 * this service also needing to know about death handling.
	 */
	public long currentTick() {
		return tickClock.getAsLong();
	}

	/**
	 * Consumed-item cure (weapons-roadmap.md gate {@code HB} §2.2 "Cure").
	 */
	public void cure(UUID victim, Reason reason) {
		remove(victim, reason);
	}

	/**
	 * Administrative removal — quit/death cleanup, see {@code StatusListener}.
	 */
	public void clear(UUID victim, Reason reason) {
		remove(victim, reason);
	}

	private void remove(UUID victim, Reason reason) {
		ActiveStatus status = active.remove(victim);
		if (status == null) return;
		expire(status, reason);
	}

	/**
	 * One service tick: boss-bar progress/text, ambient particles, a contagion roll, and expiry. Package-visible so
	 * a test can drive it directly against a manual clock, exactly like {@code ChargeController#tick}.
	 *
	 * <p>Iterates a snapshot, not the live map: {@link #rollContagion} can recursively call {@link #apply}, which
	 * inserts a brand-new entry for the newly infected victim — mutating {@link #active} while this method is still
	 * iterating it would throw {@code ConcurrentModificationException}. A newly-spread status simply isn't ticked
	 * until the next cycle.
	 */
	void tick() {
		long now = tickClock.getAsLong();

		for (ActiveStatus status : List.copyOf(active.values())) {
			if (now >= status.getExpiryTick()) {
				active.remove(status.getVictimId());
				expire(status, Reason.EXPIRED);
				continue;
			}

			Player victim = Bukkit.getPlayer(status.getVictimId());
			if (victim == null) continue;

			// A misconfigured ambient particle (e.g. one needing Color data this weapon's YAML never supplies)
			// must not take the whole tick loop down with it — warn once per status, not once per tick.
			try {
				StatusData statusData = status.getWeapon().getBiologicalData().getStatus();
				updateBossBar(status, statusData, now);
				spawnAmbientParticle(status, statusData, victim, now);
				rollContagion(status, statusData, victim, now);
			} catch (Exception exception) {
				if (!status.isTickFailureWarned()) {
					status.setTickFailureWarned(true);
					log.warn("status tick failed for " + status.getVictimId() + ": " + exception.getMessage());
				}
			}
		}
	}

	/**
	 * Resolves the victim by entity, not {@link Bukkit#getPlayer}, so a mob's status (contagion has no species
	 * restriction on the carrier side, and a future non-player-applied status is possible) still fires
	 * {@link WeaponStatusExpireEvent} and {@code ON_STATUS_EXPIRE} instead of silently expiring with a null victim.
	 */
	private void expire(ActiveStatus status, Reason reason) {
		if (status.getBossBar() != null) status.getBossBar().removeAll();

		Entity       entity = Bukkit.getEntity(status.getVictimId());
		LivingEntity victim = entity instanceof LivingEntity living ? living : null;

		// Resolved by entity, not Bukkit#getPlayer, so a mob shooter is found too; an offline shooter stays null.
		Entity       shooterEntity = status.getShooterId() != null ? Bukkit.getEntity(status.getShooterId()) : null;
		LivingEntity shooter       = shooterEntity instanceof LivingEntity living ? living : null;

		EffectContext ctx = EffectContext.builder().weapon(status.getWeapon()).source(shooter).victim(victim)
		                                .level(status.getLevel()).build();
		effectRunner.run(status.getWeapon(), EffectHook.ON_STATUS_EXPIRE, ctx);

		if (victim != null) {
			Bukkit.getPluginManager().callEvent(new WeaponStatusExpireEvent(status.getWeapon(), victim, reason));
		}
	}

	/**
	 * Graceful shutdown ({@link BeanLifecycle}): every live boss bar is orphaned on the next disable unless removed
	 * here, and the tick timer must stop before the plugin unloads.
	 */
	@Override
	public void onShutdown() {
		for (ActiveStatus status : active.values()) {
			if (status.getBossBar() != null) status.getBossBar().removeAll();
		}
		active.clear();

		if (timer != null) {
			timer.stop();
			timer = null;
		}
	}

	private void ensureBossBar(ActiveStatus status, StatusData statusData, Player player, long now) {
		if (status.getBossBar() != null) return;

		BarColor color = parseEnum(BarColor.class, statusData.getBossBar().color(), BarColor.WHITE);
		BarStyle style  = parseEnum(BarStyle.class, statusData.getBossBar().style(), BarStyle.SOLID);

		BossBar bar = Bukkit.createBossBar(formatBossBarText(statusData, status, now), color, style);
		bar.addPlayer(player);
		status.setBossBar(bar);
	}

	private void updateBossBar(ActiveStatus status, StatusData statusData, long now) {
		BossBar bar = status.getBossBar();
		if (bar == null) return;

		double totalDuration = (double) statusData.getDurationPerLevel() * status.getLevel();
		double progress       = totalDuration <= 0 ? 0
		                                            : clamp01((status.getExpiryTick() - now) / totalDuration);

		bar.setProgress(progress);
		bar.setTitle(formatBossBarText(statusData, status, now));
		// Re-derived like the title: a re-stack by a different weapon swaps statusData (BZ-EF-03). Only set on a
		// change - CraftBossBar resends a style packet on every setColor/setStyle, and this runs every status tick.
		BarColor color = parseEnum(BarColor.class, statusData.getBossBar().color(), BarColor.WHITE);
		BarStyle style = parseEnum(BarStyle.class, statusData.getBossBar().style(), BarStyle.SOLID);
		if (bar.getColor() != color) bar.setColor(color);
		if (bar.getStyle() != style) bar.setStyle(style);
	}

	private String formatBossBarText(StatusData statusData, ActiveStatus status, long now) {
		long   secondsLeft = Math.max(0, (status.getExpiryTick() - now + 19) / 20);
		String name        = statusData.getName();
		String icon        = statusData.getIcon() != null ? statusData.getIcon() : "";

		String text = statusData.getBossBar().text()
		                        .replace("%icon%", icon)
		                        .replace("%status%", name)
		                        .replace("%level%", String.valueOf(status.getLevel()))
		                        .replace("%seconds%", String.valueOf(secondsLeft));

		return BartizanChatUtil.color(text);
	}

	private void spawnAmbientParticle(ActiveStatus status, StatusData statusData, LivingEntity victim, long now) {
		String particleName = statusData.getAmbientParticle();
		int    interval     = statusData.getAmbientInterval();
		if (particleName == null || interval <= 0 || now - status.getLastAmbientTick() < interval) return;
		status.setLastAmbientTick(now);

		Particle particle = XParticle.of(particleName).map(XParticle::get).orElse(null);
		if (particle == null) return;

		Location location = victim.getLocation().add(0, 1, 0);
		if (location.getWorld() == null) return;

		location.getWorld().spawnParticle(particle, location, 6, 0.3, 0.5, 0.3, 0.01,
		                                  particleData(particle, statusData.getAmbientColor()));
	}

	/**
	 * Builds the version-specific particle payload {@code spawnParticle} needs for {@code particle}'s data type —
	 * {@code DustOptions} for the DUST family, a bare {@link Color} for {@code Color}-typed particles (e.g.
	 * {@code ENTITY_EFFECT} on 1.20.5+, which throws on a null data object rather than tolerating it), or
	 * {@code null} for every other particle. Shares its hex parsing with {@link ParticleHookEffect}.
	 */
	@Nullable
	private static Object particleData(Particle particle, @Nullable String hex) {
		if (particle.getDataType() == Particle.DustOptions.class) return ParticleHookEffect.dustOptions(hex);
		if (particle.getDataType() == Color.class) return ParticleHookEffect.parseColor(hex);
		return null;
	}

	/**
	 * Rolls a contagion spread once the interval has elapsed. Skips a target already tracked in {@link #active} —
	 * re-applying to an already-infected player would re-fire {@code ON_STATUS_APPLY}/{@code Message_Spread} even
	 * under {@code Stacking: ignore}, where the hit has no mechanical effect anyway. {@code Message_Spread} is sent
	 * only when {@link #apply} actually landed — never for a sealed-immune bystander.
	 */
	private void rollContagion(ActiveStatus status, StatusData statusData, Player carrier, long now) {
		StatusData.ContagionData contagion = statusData.getContagion();
		if (contagion == null || contagion.interval() <= 0 || now - status.getLastContagionTick() < contagion.interval()) {
			return;
		}
		status.setLastContagionTick(now);
		if (random.nextDouble() >= contagion.chance()) return;

		int    spreadLevel = Math.max(1, status.getLevel() - contagion.levelDrop());
		Player shooter     = status.getShooterId() != null ? Bukkit.getPlayer(status.getShooterId()) : null;

		double radius   = contagion.radius();
		double radiusSq = radius * radius;

		for (Entity nearby : carrier.getNearbyEntities(radius, radius, radius)) {
			if (!(nearby instanceof Player target)) continue;
			if (active.containsKey(target.getUniqueId())) continue;
			if (carrier.getLocation().distanceSquared(target.getLocation()) > radiusSq) continue;

			boolean applied = apply(target, shooter, status.getWeapon(), spreadLevel);

			if (applied && shooter != null && statusData.getMessageSpread() != null) {
				shooter.sendMessage(BartizanChatUtil.color(statusData.getMessageSpread()
				                                                     .replace("%victim%", target.getName())
				                                                     .replace("%carrier%", carrier.getName())));
			}
		}
	}

	private static double clamp01(double value) {
		return Math.max(0, Math.min(1, value));
	}

	private static <T extends Enum<T>> T parseEnum(Class<T> type, @Nullable String value, T fallback) {
		if (value == null) return fallback;

		try {
			return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException exception) {
			return fallback;
		}
	}

}
