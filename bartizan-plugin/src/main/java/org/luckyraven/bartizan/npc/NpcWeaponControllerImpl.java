package org.luckyraven.bartizan.npc;

import lombok.CustomLog;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;
import org.luckyraven.bartizan.api.event.WeaponShootEvent;
import org.luckyraven.bartizan.api.npc.NpcWeaponController;
import org.luckyraven.bartizan.api.raytrace.WeaponRaytracer;
import org.luckyraven.bartizan.api.weapon.GunWeapon;
import org.luckyraven.bartizan.api.weapon.SelectiveFire;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.api.weapon.dto.EffectHook;
import org.luckyraven.bartizan.effect.EffectContext;
import org.luckyraven.bartizan.effect.EffectRunner;
import org.luckyraven.bartizan.raytrace.WeaponShooting;
import org.luckyraven.keystone.item.ItemBuilder;
import org.luckyraven.keystone.timer.SequenceTimer;

/**
 * Bartizan's sole {@link NpcWeaponController} — NPC firing cadence, ported <b>line by line</b> from
 * {@code cops-n-crooks/npc/NpcCombatDelegate.java} per bartizan.md §1.6(8) (do not re-derive the arithmetic).
 * Only the {@code performGanglandWeaponAttack}/{@code performSingleShot}/{@code performBurstFire}/
 * {@code performAutoShot}/{@code fireSingleRound}/{@code triggerReload}/{@code refreshHeldItem}/
 * {@code canAttack}/{@code scaleCooldown}/{@code decrementAttackCooldown} slice of that file is ported — facing
 * ({@code faceTarget}/{@code applyAimError}), melee and vanilla-ranged fallback, and reaction-time-on-target-switch
 * stay in {@code AbstractNpc}/{@code NpcCombatDelegate} (Gangland/Keystone side); this class is only ever consulted
 * once the owning NPC has already decided to use its held Bartizan weapon.
 *
 * <p><b>{@code aimErrorDegrees} is accepted and stored but has no effect on this path</b> — verified in the
 * original: {@code applyAimError} is called only from the vanilla bow/crossbow facing helpers, never from
 * {@code fireSingleRound}. Kept in the constructor because the interface signature (PICK.md) fixes it; never wire
 * it into the gun path (bartizan.md §1.6(8)).
 *
 * <p><b>Cadence in server ticks (0.6.0).</b> Keystone 1.13 calls {@link #tick(int)} once per AI tick with the server
 * ticks elapsed, already divided by the NPC's {@code AbstractNpc#setFireRateScale} (the consumer's per-tier fire-rate
 * multiplier), so every cooldown here counts real server ticks. A scale equal to the NPC's AI tick rate hands this
 * 1 per AI tick - exactly the 0.5.x cadence; the default 1.0 fires up to {@code aiTickRate} times faster (migration.md
 * §16). {@link #tick()} still takes one tick off for an older Keystone that never calls {@code tick(int)}.
 *
 * <p><b>Burst re-aim (0.6.0).</b> SINGLE and AUTO fire right after Keystone's {@code faceTarget}, but a BURST's later
 * rounds land ticks after it while shooter and target move. {@link #performBurstFire} stores the target and the
 * shooter's facing minus the ideal eye-to-eye direction: Keystone has just applied the difficulty aim error, so that
 * offset IS the aim error. Each round ({@link #reaim()}) faces the stored target's current eyes plus the same offset,
 * so a moving NPC keeps tracking without shooting any straighter; {@code aimErrorDegrees} stays unused.
 *
 * <p>The two Bukkit-touching side effects — an actual shot ({@link #fireRound}) and burst scheduling
 * ({@link #scheduleBurst}) — are package-private hooks so {@code NpcWeaponCadenceTest} can stub them and exercise
 * the pure cadence arithmetic (attack-cooldown scaling, the busy/one-trigger gate, {@link #tick()}) with no live
 * Bukkit server and no real timer (bartizan.md B20 watch-out).
 */
@CustomLog
public class NpcWeaponControllerImpl implements NpcWeaponController {

	private final JavaPlugin    plugin;
	private final LivingEntity  shooter;
	private final Weapon        weapon;
	private final double        fireRateMultiplier;
	private final double        aimErrorDegrees;
	private final EffectRunner  effectRunner;

	/** Server ticks remaining before the next {@link #tryFire} may act; see {@link #tick(int)}. */
	private int attackCooldown;

	/** The target of the latest burst and the aim error Keystone applied to it; read by {@link #reaim()}. */
	private LivingEntity burstTarget;
	private Vector       burstAimOffset = new Vector();

	public NpcWeaponControllerImpl(JavaPlugin plugin, LivingEntity shooter, Weapon weapon,
	                               double fireRateMultiplier, double aimErrorDegrees, EffectRunner effectRunner) {
		this.plugin             = plugin;
		this.shooter            = shooter;
		this.weapon             = weapon;
		this.fireRateMultiplier = fireRateMultiplier;
		this.aimErrorDegrees    = aimErrorDegrees;
		this.effectRunner       = effectRunner;
	}

	@Override
	public boolean isRanged() {
		return weapon instanceof GunWeapon;
	}

	/**
	 * Ported from {@code NpcCombatDelegate.canAttack} (`:105-111`): busy while the cooldown has not elapsed, or
	 * while the held weapon is reloading.
	 */
	@Override
	public boolean isBusy() {
		return attackCooldown > 0 || weapon.isReloading();
	}

	/** Drives Keystone's RELOADING squad signal and the reload far-edge fallback (Keystone 1.13+). */
	@Override
	public boolean isReloading() {
		return weapon.isReloading();
	}

	/**
	 * Ported from {@code NpcCombatDelegate.performGanglandWeaponAttack} (`:175-191`): dispatches on the weapon's
	 * current {@link SelectiveFire}, defaulting to {@code AUTO} when unset — same as the original's
	 * {@code mode == null ? AUTO : mode}.
	 */
	@Override
	public boolean tryFire(LivingEntity target) {
		if (!(weapon instanceof GunWeapon gun)) return false;
		if (isBusy()) return false;

		if (weapon.isBroken() || weapon.isMagazineEmpty()) {
			triggerReload();
			return false;
		}

		SelectiveFire mode = gun.getCurrentSelectiveFire();
		if (mode == null) mode = SelectiveFire.AUTO;

		return switch (mode) {
			case SINGLE -> performSingleShot(gun);
			case BURST -> performBurstFire(gun, target);
			case AUTO -> performAutoShot(gun);
		};
	}

	/** Ported from {@code NpcCombatDelegate.performSingleShot} (`:260-270`) — one trigger per shot. */
	private boolean performSingleShot(GunWeapon gun) {
		if (!fireRound(gun)) return false;

		int perShot  = Math.max(gun.getProjectileData().getPerShot(), 1);
		int cooldown = Math.max(gun.getProjectileData().getCooldown(), 1);
		attackCooldown = scaleCooldown(Math.max(perShot * cooldown, 5));

		if (weapon.isMagazineEmpty()) {
			triggerReload();
		}

		return true;
	}

	/** Ported from {@code NpcCombatDelegate.performAutoShot} (`:272-281`) — hold-to-fire. */
	private boolean performAutoShot(GunWeapon gun) {
		if (!fireRound(gun)) return false;

		int cooldown = gun.getProjectileData().getCooldown();
		attackCooldown = scaleCooldown(Math.max(cooldown, 5));

		if (weapon.isMagazineEmpty()) {
			triggerReload();
		}

		return true;
	}

	/** Ported from {@code NpcCombatDelegate.performBurstFire} (`:283-306`) — one trigger per burst. */
	private boolean performBurstFire(GunWeapon gun, LivingEntity target) {
		if (plugin == null) return false;

		int perShot  = Math.max(gun.getProjectileData().getPerShot(), 1);
		int cooldown = Math.max(gun.getProjectileData().getCooldown(), 1);

		int totalBurstTicks = perShot * cooldown + cooldown;
		attackCooldown = scaleCooldown(Math.max(totalBurstTicks, 5));

		// Keystone's faceTarget has just applied the aim error, so facing minus ideal IS that error (class javadoc).
		Vector ideal = ideal(target);
		burstTarget    = target;
		burstAimOffset = ideal == null ? new Vector() : shooter.getEyeLocation().getDirection().subtract(ideal);

		scheduleBurst(gun, perShot, cooldown);

		return true;
	}

	/**
	 * Bukkit-touching burst scheduling, ported from {@code NpcCombatDelegate.performBurstFire} (`:292-306`)
	 * verbatim: a {@link SequenceTimer} firing {@code perShot} rounds, interval {@code 0} for the first round and
	 * {@code cooldown} thereafter, started async-false (house rule {@code feedback_repeating_timer_async} — this
	 * touches Bukkit entity/weapon state). Extracted as its own method (rather than inlined in
	 * {@link #performBurstFire}) so {@code NpcWeaponCadenceTest} can stub it — see the class javadoc.
	 */
	void scheduleBurst(GunWeapon gun, int perShot, int cooldown) {
		SequenceTimer burstTimer = new SequenceTimer(plugin, 1L, 1L);

		for (int i = 0; i < perShot; i++) {
			int interval = i == 0 ? 0 : cooldown;

			burstTimer.addIntervalTaskPair(interval, timer -> burstRound(gun, timer));
		}

		burstTimer.start(false);
	}

	/**
	 * One scheduled round of {@link #scheduleBurst}. Package-private so {@code NpcWeaponCadenceTest} pins the
	 * dead-shooter guard without a live scheduler.
	 */
	void burstRound(GunWeapon gun, SequenceTimer timer) {
		// bug docket BZ-NU-04: the shooter may have died or been destroy()'d between rounds of this same burst -
		// stop here instead of firing against a dead/removed entity. Safe to call from inside the body:
		// SequenceTimer runs it outside its monitor (see that class's javadoc).
		if (isShooterGone()) {
			timer.stop();
			return;
		}
		if (!reaim()) {
			timer.stop();
			return;
		}

		fireRound(gun);

		if (weapon.isMagazineEmpty()) {
			triggerReload();
		}
	}

	/**
	 * True once the shooter has died or otherwise become invalid since this burst was scheduled (bug docket
	 * BZ-NU-04). Checked at the top of every scheduled round in {@link #scheduleBurst} - Keystone's
	 * {@code AbstractNpc.destroy()} calls {@link #onDestroy()} then despawns/destroys the entity in the same call,
	 * before any pending burst interval elapses, so a still-running round has to notice the dead/removed shooter
	 * itself rather than rely on {@link #onDestroy()} to have cancelled anything. {@code Entity#isValid()} is
	 * {@code false} for both a dead entity and a despawned/removed one, so this single check covers death and
	 * every destroy path. Package-private (rather than inlined in the scheduling lambda) so
	 * {@code NpcWeaponCadenceTest} can pin it without a live Bukkit scheduler — see the class javadoc.
	 */
	boolean isShooterGone() {
		return !shooter.isValid();
	}

	/**
	 * Faces the burst's target where it is now, off by the burst's stored aim error (class javadoc). {@code false}
	 * once the target is gone or dead, which ends the burst. Package-private for {@code NpcWeaponCadenceTest}.
	 */
	boolean reaim() {
		LivingEntity target = burstTarget;
		if (target == null || !target.isValid() || target.isDead()) return false;

		Vector ideal = ideal(target);
		if (ideal == null) return true;   // coincident eyes: nothing to face, keep the current rotation
		Vector direction = ideal.add(burstAimOffset);
		if (direction.lengthSquared() < 1.0E-12) return true;

		Location facing = shooter.getLocation().setDirection(direction.normalize());
		shooter.setRotation(facing.getYaw(), facing.getPitch());
		return true;
	}

	/** Unit vector from the shooter's eyes to the target's, or {@code null} when they coincide. */
	private Vector ideal(LivingEntity target) {
		Vector between = target.getEyeLocation().toVector().subtract(shooter.getEyeLocation().toVector());
		return between.lengthSquared() < 1.0E-12 ? null : between.normalize();
	}

	/**
	 * Bukkit-touching single-round fire, ported from {@code NpcCombatDelegate.fireSingleRound} (`:309-341`)
	 * verbatim: broken/empty magazine reloads and returns {@code false}; {@code consumeShot()}; a
	 * {@link WeaponShootEvent} that, if cancelled, refunds the ammunition and returns {@code false}; otherwise
	 * fires through the registered {@link WeaponRaytracer}, plays the weapon's shot sound, and refreshes the held
	 * item. Extracted as its own method so {@code NpcWeaponCadenceTest} can stub it — see the class javadoc.
	 */
	boolean fireRound(GunWeapon gun) {
		if (weapon.isBroken() || weapon.isMagazineEmpty()) {
			triggerReload();
			return false;
		}

		boolean consumed = weapon.consumeShot();
		if (!consumed) {
			triggerReload();
			return false;
		}

		WeaponShootEvent event = new WeaponShootEvent(weapon, shooter);
		Bukkit.getPluginManager().callEvent(event);

		if (event.isCancelled()) {
			weapon.addAmmunition(1);
			return false;
		}

		RegisteredServiceProvider<WeaponRaytracer> registration =
				Bukkit.getServicesManager().getRegistration(WeaponRaytracer.class);
		if (registration != null) {
			WeaponShooting.fire(plugin, registration.getProvider(), shooter, gun, effectRunner);
		}

		EffectContext ctx = EffectContext.shot(weapon, shooter, shooter.getEyeLocation().getDirection()).build();
		effectRunner.run(weapon, EffectHook.ON_SHOOT, ctx);

		refreshHeldItem();
		return true;
	}

	/** Ported from {@code NpcCombatDelegate.triggerReload} (`:193-198`). */
	@Override
	public void triggerReload() {
		if (plugin == null || weapon == null) return;
		if (weapon.isReloading()) return;
		if (weapon.getReloadData() == null) return;
		weapon.reload(plugin, null, false);
	}

	/** Ported from {@code NpcCombatDelegate.refreshHeldItem} (`:142-157`) — skips {@code Material.AIR}. */
	@Override
	public void refreshHeldItem() {
		if (weapon == null) return;

		EntityEquipment equipment = shooter.getEquipment();
		if (equipment == null) return;

		ItemStack current = equipment.getItemInMainHand();
		if (current.getType() == Material.AIR) return;

		ItemBuilder builder = new ItemBuilder(current);
		weapon.updateWeaponData(builder);
		equipment.setItemInMainHand(builder.build());
	}

	@Override
	public void onDestroy() {
		// No standing timer or listener registration to release: this hook fires (and the entity despawns/
		// destroys) before any pending burst interval elapses, so cancelling a timer here would be too late
		// anyway - each scheduled round checks isShooterGone() itself and stops the SequenceTimer the moment it
		// finds a dead/removed shooter (bug docket BZ-NU-04).
	}

	/** Ported from {@code NpcCombatDelegate.decrementAttackCooldown} (`:113-115`) — one decrement per NPC tick. */
	@Override
	public void tick() {
		if (attackCooldown > 0) attackCooldown--;
	}

	/**
	 * Takes the server ticks elapsed since the last AI tick off the cooldown (Keystone 1.13+). Keystone has already
	 * divided them by the NPC's fire-rate scale and carries the fraction, so 0 is a legal value and subtracts nothing.
	 */
	@Override
	public void tick(int elapsedServerTicks) {
		attackCooldown = Math.max(0, attackCooldown - Math.max(0, elapsedServerTicks));
	}

	/** Ported from {@code NpcCombatDelegate.scaleCooldown} (`:364-367`). */
	private int scaleCooldown(int baseCooldown) {
		int scaled = (int) Math.round(baseCooldown * fireRateMultiplier);
		return Math.max(scaled, 5);
	}

}
