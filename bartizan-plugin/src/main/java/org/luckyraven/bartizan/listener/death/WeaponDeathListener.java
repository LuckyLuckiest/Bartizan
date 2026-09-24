package org.luckyraven.bartizan.listener.death;

import lombok.CustomLog;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.luckyraven.bartizan.api.event.WeaponEntityDamageEvent;
import org.luckyraven.bartizan.api.event.WeaponEntityDamageEvent.DamageKind;
import org.luckyraven.bartizan.api.event.WeaponKillEntityEvent;
import org.luckyraven.bartizan.api.weapon.BiologicalWeapon;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.api.weapon.dto.EffectHook;
import org.luckyraven.bartizan.effect.EffectContext;
import org.luckyraven.bartizan.effect.EffectRunner;
import org.luckyraven.bartizan.file.BartizanMessages;
import org.luckyraven.bartizan.raytrace.FatalDamageAttribution;
import org.luckyraven.bartizan.status.ActiveStatus;
import org.luckyraven.bartizan.status.StatusEffectService;
import org.luckyraven.bartizan.util.BartizanChatUtil;
import org.luckyraven.bartizan.weapon.WeaponManager;
import org.luckyraven.keystone.bean.listener.ListenerHandler;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Bartizan owns the weapon death message (bartizan.md §1.6(4)) — replaces the deleted
 * {@code W/death/WeaponDeathMessageContributor.java}, which plugged into Gangland's now-deleted
 * {@code DeathMessageContributor} seam.
 *
 * <p><b>Design note (orchestrator update received mid-B17, after the gate-GG review):</b> §1.6(4)'s original text
 * said the throwable-name side table should be "written by {@code ThrowableAction} through an injected reference
 * (not a static)". That is no longer possible: B13 already deleted {@code ThrowableAction}'s
 * {@code pendingKillerWeapon}/{@code pendingVehicleExplosionDamage} static maps and replaced their one reader
 * (this listener) with {@link WeaponEntityDamageEvent#weaponName()}/{@link WeaponEntityDamageEvent#kind()} — there
 * is no longer any reference to inject. Instead this listener carries its own {@code @EventHandler} on
 * {@link WeaponEntityDamageEvent}, recording {@code victimUuid -> weaponName} with a short TTL, and reads that map
 * in its {@link PlayerDeathEvent} handler. Since gate {@code HA}, {@code WeaponRaytracerImpl}'s default damage
 * pipeline also fires this event with {@code DamageKind.DIRECT} on every gun hit, so {@link #onWeaponEntityDamage}
 * only records a claim for {@code DamageKind.EXPLOSION}/{@code DamageKind.FIRE} — otherwise a gun hit would
 * override the killer's actually-held weapon for the whole {@link #THROWABLE_CLAIM_TTL_MS} window. (BZ-EV-19) A
 * {@code FIRE} claim is only ever consulted when the victim's last damage cause is the burn itself — see
 * {@link #claimedWeaponName}.
 *
 * <p>{@code EventPriority.HIGH} is deliberate (§1.6(4)): Gangland's {@code PlayerDeathListener.onPlayerDeath} runs
 * at {@code EventPriority.LOWEST}, so this handler runs <b>after</b> it and this class's
 * {@code setDeathMessage} wins whenever a weapon actually claims the kill. When nothing claims it, this listener
 * returns without touching the message, leaving Gangland's own death message in place.
 */
@CustomLog
@ListenerHandler
public class WeaponDeathListener implements Listener {

	/**
	 * How long a recorded throwable kill stays claimable. "A few seconds" per the orchestrator's update — long
	 * enough to cover the delay between a thrown explosive detonating and the victim's {@link PlayerDeathEvent}
	 * (fall damage, fire ticks, or a laggy server tick can all separate the two), short enough that an unrelated
	 * death minutes later never picks up a stale entry.
	 */
	private static final long THROWABLE_CLAIM_TTL_MS = 5000L;

	private final WeaponManager       weaponManager;
	private final EffectRunner        effectRunner;
	private final StatusEffectService statusService;

	/** {@code victimUuid -> (weaponName, recordedAtMillis)}. Private to this listener — never a static. */
	private final Map<UUID, RecordedKill> recentThrowableKills = new ConcurrentHashMap<>();

	public WeaponDeathListener(WeaponManager weaponManager, EffectRunner effectRunner,
	                           StatusEffectService statusService) {
		this.weaponManager = weaponManager;
		this.effectRunner  = effectRunner;
		this.statusService = statusService;
	}

	/**
	 * Records which weapon claimed a non-projectile hit on a player, so the {@link PlayerDeathEvent} handler below
	 * can attribute the kill even though the killer may have switched items since throwing (the same rationale the
	 * deleted {@code ThrowableAction.pendingKillerWeapon} map existed for).
	 */
	@EventHandler
	public void onWeaponEntityDamage(WeaponEntityDamageEvent event) {
		// m1: nothing else in this listener runs on a schedule, so a victim entry that never gets claimed by a
		// PlayerDeathEvent (the target survived, or dies later with no killer to attribute it to) would otherwise
		// sit in the map forever. Piggyback the sweep on the only other event this listener already receives
		// rather than adding a repeating Timer for what is, at steady state, a handful of entries.
		recentThrowableKills.values().removeIf(this::isExpired);

		DamageKind kind = event.kind();
		if (kind != DamageKind.EXPLOSION && kind != DamageKind.FIRE) return;
		if (!(event.getEntity() instanceof Player victim)) return;

		recentThrowableKills.put(victim.getUniqueId(),
				new RecordedKill(event.weaponName(), kind, System.currentTimeMillis()));
	}

	/**
	 * Ported from the deleted {@code WeaponDeathMessageContributor.resolve} (`:29-45`) + Gangland's own
	 * {@code PlayerDeathListener.buildDeathMessage} (`:198-223`) template-application step, merged into one
	 * handler now that Bartizan is not a {@code DeathMessageContributor} plugged into Gangland's builder.
	 */
	@EventHandler(priority = EventPriority.HIGH)
	public void onPlayerDeath(PlayerDeathEvent event) {
		Player victim = event.getEntity();

		// M1: an earlier LOWEST-priority listener (Gangland's own PlayerDeathListener) nulls the death message for
		// an NPC victim, an NPC killer, or a duplicate event, and that decision must never be overridden here.
		// Standalone (no Gangland present) the vanilla death message is non-null, so the weapon path below still
		// runs unchanged.
		if (event.getDeathMessage() == null) return;

		// m1: removed unconditionally, BEFORE the killer == null check below. The entry is recorded (in
		// onWeaponEntityDamage) for any player victim of a WeaponEntityDamageEvent regardless of whether this
		// death ever gets an attributable killer — leaving the remove after the early return meant a death with
		// no killer (environmental finish, self-detonation) left the entry in the map permanently.
		RecordedKill recorded = recentThrowableKills.remove(victim.getUniqueId());

		Player killer = victim.getKiller();

		// BZ-EV-21: Player#getKiller() names the last player to land ANY hit within Bukkit's own ~5s
		// last-hurt-by-player window, not whoever actually dealt the fatal blow — a killer != null here does not
		// mean their hit was lethal. When the victim's own last damage cause is the status DoT itself
		// (POISON/WITHER), the status's shooter is asked first, the same as the no-killer case below; only when
		// that declines (no active status, outside its window, shooter offline, or vetoed) do we fall through to
		// the killer-based path.
		boolean statusMayHaveKilled = killer == null || isStatusDamageCause(victim.getLastDamageCause());
		if (statusMayHaveKilled && creditStatusKill(event, victim)) {
			return;
		}

		if (killer == null) {
			return;
		}

		// BZ-EV-19: the weapon actually dealing the fatal blow, when it is known — set synchronously by
		// WeaponRaytracerImpl around the living.damage() call that is, right now, still on this thread's stack
		// triggering this very PlayerDeathEvent. Takes priority over both the recorded claim below and the killer's
		// currently-held item because it names the true fatal weapon regardless of what the killer has swapped to.
		String throwableName = FatalDamageAttribution.get();
		if (throwableName == null) {
			throwableName = claimedWeaponName(recorded, victim);
		}

		Weapon weapon = throwableName != null ? weaponManager.getWeaponTemplate(throwableName) : null;

		if (weapon == null && throwableName == null) {
			ItemStack heldItem = killer.getInventory().getItemInMainHand();
			weapon = weaponManager.validateAndGetWeapon(killer, heldItem);
		}

		// Nothing claims this kill — leave Gangland's own death message (set at EventPriority.LOWEST) untouched.
		if (weapon == null && throwableName == null) return;

		String template = weapon != null ? weapon.pickDeathMessage().orElse(null) : null;
		if (template == null) template = pickRandomGlobalMessage(BartizanMessages.DEAD_USING_WEAPON.toStringList());
		if (template == null) return;

		String itemName = weapon != null ? weapon.getDisplayName() : throwableName;

		// Fire the kill event before crediting the death message — a listener that cancels it should see the kill
		// go unclaimed, leaving Gangland's own (LOWEST-priority) vanilla message in place.
		WeaponKillEntityEvent killEvent = new WeaponKillEntityEvent(weapon, killer, victim);
		Bukkit.getPluginManager().callEvent(killEvent);
		if (killEvent.isCancelled()) {
			return;
		}

		if (weapon != null) {
			EffectContext ctx = EffectContext.builder().weapon(weapon).source(killer).victim(victim).build();
			effectRunner.run(weapon, EffectHook.ON_KILL, ctx);
		}

		event.setDeathMessage(BartizanChatUtil.color(template.replace("%killer%", killer.getName())
		                                                     .replace("%victim%", victim.getName())
		                                                     .replace("%item%", itemName)));
	}

	/**
	 * Poison/wither death kill credit (weapons-roadmap.md gate {@code HB} §2.2): a death with no attributable
	 * killer, or one whose last damage cause is the status DoT itself (BZ-EV-21), still credits the shooter of an
	 * active biological status when the last application landed within {@code Status.Kill_Credit_Window} ticks and
	 * that shooter is still online.
	 *
	 * @return {@code true} if this credited the kill (fired the event, uncancelled, and set the death message) —
	 * 		callers use this to decide whether the killer-based path below should still run.
	 */
	private boolean creditStatusKill(PlayerDeathEvent event, Player victim) {
		Optional<ActiveStatus> statusOptional = statusService.activeOn(victim.getUniqueId());
		if (statusOptional.isEmpty()) return false;

		ActiveStatus status = statusOptional.get();
		if (status.getShooterId() == null) return false;

		BiologicalWeapon weapon = status.getWeapon();
		long             window = weapon.getBiologicalData().getStatus().getKillCreditWindow();
		if (statusService.currentTick() - status.getAppliedTick() > window) return false;

		Player shooter = Bukkit.getPlayer(status.getShooterId());
		if (shooter == null || !shooter.isOnline()) return false;

		String template = weapon.pickDeathMessage().orElse(null);
		if (template == null) template = pickRandomGlobalMessage(BartizanMessages.DEAD_USING_WEAPON.toStringList());
		if (template == null) return false;

		WeaponKillEntityEvent killEvent = new WeaponKillEntityEvent(weapon, shooter, victim);
		Bukkit.getPluginManager().callEvent(killEvent);
		if (killEvent.isCancelled()) return false;

		EffectContext ctx = EffectContext.builder().weapon(weapon).source(shooter).victim(victim).build();
		effectRunner.run(weapon, EffectHook.ON_KILL, ctx);

		event.setDeathMessage(BartizanChatUtil.color(template.replace("%killer%", shooter.getName())
		                                                     .replace("%victim%", victim.getName())
		                                                     .replace("%item%", weapon.getDisplayName())));
		return true;
	}

	/**
	 * BZ-EV-21: {@code true} when the victim's last recorded damage was the status DoT itself, independent of
	 * {@code Player#getKiller()}'s own, much looser ~5s last-hurt-by-player tracking.
	 */
	private static boolean isStatusDamageCause(@Nullable EntityDamageEvent lastDamage) {
		if (lastDamage == null) return false;

		DamageCause cause = lastDamage.getCause();
		return cause == DamageCause.POISON || cause == DamageCause.WITHER;
	}

	private boolean isExpired(RecordedKill recorded) {
		return System.currentTimeMillis() - recorded.recordedAtMillis() > THROWABLE_CLAIM_TTL_MS;
	}

	/**
	 * BZ-EV-19: resolves a still-live recorded claim to the weapon name it should credit, or {@code null} if none
	 * applies. An {@code EXPLOSION} claim applies unconditionally (as before this fix) — its
	 * {@code WeaponEntityDamageEvent} fires before the fatal {@code living.damage()} call, so it is already known to
	 * be the actual killing blow whenever it survives to this point. A {@code FIRE} claim only applies when the
	 * victim's last damage cause is the ongoing burn itself: the spray hit that lit them may have happened seconds
	 * ago and well before a later, unrelated finishing blow, which must still credit whatever the killer swung/fired
	 * last instead.
	 */
	@Nullable
	private String claimedWeaponName(@Nullable RecordedKill recorded, Player victim) {
		if (recorded == null || isExpired(recorded)) return null;
		if (recorded.kind() == DamageKind.EXPLOSION) return recorded.weaponName();
		if (recorded.kind() == DamageKind.FIRE && isFireDeath(victim)) return recorded.weaponName();
		return null;
	}

	private static boolean isFireDeath(Player victim) {
		EntityDamageEvent lastDamage = victim.getLastDamageCause();
		if (lastDamage == null) return false;

		DamageCause cause = lastDamage.getCause();
		return cause == DamageCause.FIRE_TICK || cause == DamageCause.FIRE;
	}

	@Nullable
	private static String pickRandomGlobalMessage(@Nullable List<String> messages) {
		if (messages == null || messages.isEmpty()) return null;
		return messages.get(ThreadLocalRandom.current().nextInt(messages.size()));
	}

	private record RecordedKill(String weaponName, DamageKind kind, long recordedAtMillis) {
	}

}
