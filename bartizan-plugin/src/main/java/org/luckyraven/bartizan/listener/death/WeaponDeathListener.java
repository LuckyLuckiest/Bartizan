package org.luckyraven.bartizan.listener.death;

import lombok.CustomLog;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.luckyraven.bartizan.api.event.WeaponEntityDamageEvent;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.file.BartizanMessages;
import org.luckyraven.bartizan.util.BartizanChatUtil;
import org.luckyraven.bartizan.weapon.WeaponManager;
import org.luckyraven.keystone.bean.listener.ListenerHandler;

import java.util.List;
import java.util.Map;
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
 * in its {@link PlayerDeathEvent} handler. Per the orchestrator: {@code WeaponEntityDamageEvent} is currently only
 * ever fired with {@code DamageKind.EXPLOSION} (both of {@code ThrowableAction}'s two fire sites, B13's row) — this
 * listener does not assume any other kind is live; it simply records whatever kind arrives.
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

	private final WeaponManager weaponManager;

	/** {@code victimUuid -> (weaponName, recordedAtMillis)}. Private to this listener — never a static. */
	private final Map<UUID, RecordedKill> recentThrowableKills = new ConcurrentHashMap<>();

	public WeaponDeathListener(WeaponManager weaponManager) {
		this.weaponManager = weaponManager;
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

		if (!(event.getEntity() instanceof Player victim)) return;

		recentThrowableKills.put(victim.getUniqueId(), new RecordedKill(event.weaponName(), System.currentTimeMillis()));
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
		if (killer == null) return;

		// A recorded throwable claim takes priority over whatever the killer currently holds — they may have
		// switched items since throwing.
		String throwableName = null;
		if (recorded != null && !isExpired(recorded)) {
			throwableName = recorded.weaponName();
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

		event.setDeathMessage(BartizanChatUtil.color(template.replace("%killer%", killer.getName())
		                                                     .replace("%victim%", victim.getName())
		                                                     .replace("%item%", itemName)));
	}

	private boolean isExpired(RecordedKill recorded) {
		return System.currentTimeMillis() - recorded.recordedAtMillis() > THROWABLE_CLAIM_TTL_MS;
	}

	@Nullable
	private static String pickRandomGlobalMessage(@Nullable List<String> messages) {
		if (messages == null || messages.isEmpty()) return null;
		return messages.get(ThreadLocalRandom.current().nextInt(messages.size()));
	}

	private record RecordedKill(String weaponName, long recordedAtMillis) {
	}

}
