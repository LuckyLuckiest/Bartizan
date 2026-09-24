package org.luckyraven.bartizan.listener;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.luckyraven.keystone.bean.autowire.AutowireTarget;
import org.luckyraven.keystone.bean.listener.ListenerHandler;
import org.luckyraven.bartizan.api.combat.CombatEligibility;
import org.luckyraven.keystone.timer.CountdownTimer;
import org.luckyraven.keystone.timer.RepeatingTimer;
import org.luckyraven.bartizan.api.weapon.SelectiveFire;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.api.weapon.dto.EffectHook;
import org.luckyraven.bartizan.api.weapon.dto.HandlingData;
import org.luckyraven.bartizan.weapon.CircumstanceRules;
import org.luckyraven.bartizan.weapon.ScopeToggle;
import org.luckyraven.bartizan.weapon.WeaponService;
import org.luckyraven.bartizan.api.weapon.dto.ScopeData;
import org.luckyraven.bartizan.api.weapon.dto.ScopeType;
import org.luckyraven.bartizan.effect.EffectContext;
import org.luckyraven.bartizan.effect.EffectRunner;
import org.luckyraven.bartizan.fire.PluginFireRegistry;
import org.luckyraven.bartizan.api.raytrace.WeaponRaytracer;
import org.luckyraven.bartizan.scope.SpyglassScopeTask;
import org.luckyraven.bartizan.weapon.action.BeamAction;
import org.luckyraven.bartizan.api.weapon.BeamWeapon;
import org.luckyraven.bartizan.api.weapon.modifiers.BlockDamageManager;
import org.luckyraven.bartizan.status.StatusEffectService;
import org.luckyraven.bartizan.weapon.action.BiologicalAction;
import org.luckyraven.bartizan.weapon.action.ChargeController;
import org.luckyraven.bartizan.api.weapon.BiologicalWeapon;
import org.luckyraven.bartizan.weapon.action.FullAutoTask;
import org.luckyraven.bartizan.weapon.action.GunFireDispatcher;
import org.luckyraven.bartizan.api.weapon.GunWeapon;
import org.luckyraven.bartizan.weapon.action.IncendiaryAction;
import org.luckyraven.bartizan.api.weapon.IncendiaryWeapon;
import org.luckyraven.bartizan.weapon.action.MeleeAction;
import org.luckyraven.bartizan.api.weapon.MeleeWeapon;
import org.luckyraven.bartizan.weapon.action.ThrowableAction;
import org.luckyraven.bartizan.api.weapon.ThrowableWeapon;
import org.luckyraven.bartizan.util.EmptyMagSoundGate;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

@ListenerHandler
@AutowireTarget({WeaponService.class, WeaponRaytracer.class, PluginFireRegistry.class, CombatEligibility.class,
                EffectRunner.class, BlockDamageManager.class, StatusEffectService.class, SpyglassScopeTask.class})
public class WeaponInteract implements Listener {

	/**
	 * Floor on the press-lock window, in ticks. Slightly larger than the AUTO watchdog's 3-tick idle window so that
	 * very fast-cooldown SINGLE/BURST weapons still swallow the entire packet stream Spigot fires while RMB is held.
	 */
	private static final long MIN_PRESS_LOCK_TICKS = 4L;

	/**
	 * Length of one Minecraft tick in real-time milliseconds. Used to convert tick-denominated cooldowns to wall-clock
	 * deadlines for the press lock map (Spigot has no portable {@code Server#getCurrentTick}).
	 */
	private static final long MILLIS_PER_TICK = 50L;

	/**
	 * Dedup window for the two events Spigot delivers when a player left-clicks an entity with a melee weapon: the
	 * USE_ENTITY/ATTACK packet fires {@link EntityDamageByEntityEvent} and the companion swing-arm packet fires
	 * {@link PlayerInteractEvent} with {@code LEFT_CLICK_AIR}. Both reach this listener and would otherwise both call
	 * {@link MeleeAction#activate(Player)}, doubling the damage and the resulting kill credit. 150ms (≈3 ticks) is
	 * comfortably wider than the worst-case packet-delivery split between the two events while staying tight enough not
	 * to throttle real follow-up swings, which are gated by the configured weapon cooldown anyway.
	 */
	private static final long MELEE_DEDUP_WINDOW_MS = 150L;

	private final JavaPlugin         plugin;
	private final WeaponService      weaponService;
	private final WeaponRaytracer    raytracer;
	private final PluginFireRegistry fireRegistry;
	private final CombatEligibility  combatEligibility;
	private final EffectRunner       effectRunner;
	private final BlockDamageManager blockDamageManager;
	private final StatusEffectService statusService;
	private final SpyglassScopeTask  spyglassScopeTask;

	private final Map<UUID, AtomicReference<WeaponData>> continuousFire;
	/**
	 * {@code Information.Equip_Delay} gate — dedicated map, seeded in {@link #equip} and cleared on weapon
	 * swap. Kept separate from {@link GunFireDispatcher}'s shared fire-rate map: that map also carries the per-shot
	 * fire cooldown, rewritten on every shot by {@link #engagePressHoldWatchdog(UUID, long)}, so sharing it here
	 * would refuse the scope toggle for the whole fire-rate window after every shot, not just after an equip.
	 */
	private final Map<UUID, Long>                        equipDelayUntil;
	/**
	 * SINGLE/BURST held-trigger gate. After a SINGLE/BURST shot fires, an entry is inserted here for the weapon and a
	 * release-detection watchdog is scheduled. While the entry exists, all incoming RMB events for the weapon are
	 * dropped — this is what enforces one-shot-per-press despite Spigot's repeated PlayerInteractEvent stream while RMB
	 * is held. The watchdog removes the entry once it observes a quiet event window (one full
	 * {@link #MIN_PRESS_LOCK_TICKS}-tick cycle without the {@link WeaponData#shooting} flag being refreshed by an
	 * incoming event), which signals that RMB has actually been released and the trigger should be re-armed for the
	 * next press.
	 *
	 * <p>This is orthogonal to {@link GunFireDispatcher}'s shared fire-rate gate (weapons-roadmap.md gate {@code HP}
	 * review — moved out of this class so {@code WeaponSelectiveFireChangeListener}'s scoped F fire goes through the
	 * same map): that gate enforces the weapon's natural fire rate across separate presses, while this map enforces
	 * "one shot per press" across the lifetime of a single hold.
	 */
	private final Map<UUID, AtomicReference<WeaponData>> pressHoldState;
	/**
	 * Per-weapon release callbacks invoked by the {@link #continuousFire} watchdog when it detects RMB has been
	 * released. Used by charge-then-release weapons (biological) to fire once the player lets go of RMB.
	 */
	private final Map<UUID, Runnable>                    releaseCallbacks;
	private final Map<UUID, FullAutoTask>                autoTasks;
	private final Map<UUID, RepeatingTimer>              activeTasks;
	private final Map<UUID, Long>                        meleeCooldowns;
	/**
	 * Per-weapon timestamp of the last melee swing that was actually fired, in milliseconds. Used to dedup the
	 * companion {@link PlayerInteractEvent}/{@link EntityDamageByEntityEvent} pair Spigot delivers for a single
	 * left-click on an entity — the two events can land in the same tick or in adjacent ticks depending on packet
	 * order, so a tick-based dedup window is unreliable. Anything within {@link #MELEE_DEDUP_WINDOW_MS} of the last
	 * recorded swing is treated as the second half of the same click and dropped.
	 */
	private final Map<UUID, Long>                        lastMeleeSwingMs;

	public WeaponInteract(JavaPlugin plugin, WeaponService weaponService, WeaponRaytracer raytracer,
	                      PluginFireRegistry fireRegistry, CombatEligibility combatEligibility,
	                      EffectRunner effectRunner, BlockDamageManager blockDamageManager,
	                      StatusEffectService statusService, SpyglassScopeTask spyglassScopeTask) {
		this.plugin             = plugin;
		this.weaponService      = weaponService;
		this.raytracer          = raytracer;
		this.fireRegistry       = fireRegistry;
		this.combatEligibility  = combatEligibility;
		this.effectRunner       = effectRunner;
		this.blockDamageManager = blockDamageManager;
		this.statusService      = statusService;
		this.spyglassScopeTask  = spyglassScopeTask;
		this.continuousFire     = new ConcurrentHashMap<>();
		this.equipDelayUntil    = new ConcurrentHashMap<>();
		this.pressHoldState     = new ConcurrentHashMap<>();
		this.releaseCallbacks   = new ConcurrentHashMap<>();
		this.autoTasks          = new ConcurrentHashMap<>();
		this.activeTasks        = new ConcurrentHashMap<>();
		this.meleeCooldowns     = new ConcurrentHashMap<>();
		this.lastMeleeSwingMs   = new ConcurrentHashMap<>();
	}

	@EventHandler
	public void onPlayerInteract(PlayerInteractEvent event) {
		Player    player = event.getPlayer();
		ItemStack item   = event.getItem();
		Weapon    weapon = weaponService.validateAndGetWeapon(player, item);

		boolean leftClick = event.getAction() == Action.LEFT_CLICK_AIR || event.getAction() == Action.LEFT_CLICK_BLOCK;
		boolean rightClick = event.getAction() == Action.RIGHT_CLICK_AIR ||
		                     event.getAction() == Action.RIGHT_CLICK_BLOCK;

		// A Material: CROSSBOW weapon carries the gate-HP aim-pose arrow (Weapon#applyCrossbowChargedProjectile),
		// which makes the item a LOADED crossbow as far as the client/server are concerned - any right-click that
		// reaches an exit below without denying the vanilla item use (isDead/!canBeHit, sneak + right-click on a
		// left_click-trigger weapon, right-click during Equip_Delay, scoped-but-Level:-0) would otherwise fire a
		// real vanilla arrow. DENY for every weapon-tagged item, including one that no longer resolves (its type was
		// removed from config - BZ-EV-16); the spyglass branch below still overrides it with ALLOW (last write wins).
		if (rightClick && (weapon != null || weaponService.getHeldWeaponName(item) != null)) {
			event.setUseItemInHand(Event.Result.DENY);
		}

		if (weapon == null) return;

		// Off-hand weapons are inert (Dual_Wield is Missing, gate HN): an OFF_HAND use threw an off-hand grenade
		// while ThrowableAction took the item from the main hand, and a gun in each hand fired both on one click
		// (BZ-EV-11, BZ-EV-12). The vanilla use stays denied above.
		if (event.getHand() == EquipmentSlot.OFF_HAND) return;

		if (player.isDead() || !combatEligibility.canBeHit(player)) return;

		// Shoot.Trigger: left_click (guns only) swaps which click fires and which toggles scope. Every other WM
		// trigger type is deliberately unsupported.
		// A right click on an entity arrives as PlayerInteractEntityEvent, which never fires a left_click gun (see
		// onPlayerInteractWithEntity); the USE_ITEM that follows it lands here and scopes.
		boolean gunLeftTrigger = weapon instanceof GunWeapon && isLeftClickTrigger(weapon);
		boolean scopeClick     = gunLeftTrigger ? rightClick : leftClick;
		boolean fireClick      = gunLeftTrigger ? leftClick : rightClick;

		// scope toggle for any weapon type that has a scope configured
		ScopeData scopeData     = weapon.getScopeData();
		boolean   spyglassScope = scopeData != null && scopeData.getType() == ScopeType.SPYGLASS;
		// Type: spyglass scopes even at Level: 0 - the vanilla use is the point, the extra SLOWNESS is optional.
		// No scope configured: the click falls through to the weapon's own action (BZ-EV-03, a plain melee swing).
		boolean   validateScope = scopeData != null && (scopeData.getLevel() > 0 || spyglassScope);

		if (scopeClick && !player.isSneaking() && validateScope && !weapon.isReloading() &&
		    !isEquipDelayActive(weapon.getUuid())) {
			event.setUseInteractedBlock(Event.Result.DENY);
			// Type: spyglass lets the vanilla use through instead of swallowing it - the client drives its own
			// zoom/raised-arm/movement-slowdown/scope-overlay off that use, no packets or NMS involved (weapons-
			// roadmap.md gate HP). Every other scope keeps DENY, today's behaviour.
			event.setUseItemInHand(spyglassScope ? Event.Result.ALLOW : Event.Result.DENY);

			boolean scopingIn = ScopeToggle.apply(weapon, player, weaponService, effectRunner);

			// Scope-out for a spyglass weapon is driven by SpyglassScopeTask's isHandRaised() poll, not a second
			// click - the client won't send another PlayerInteractEvent while the item is in use.
			if (scopingIn && spyglassScope) spyglassScopeTask.register(player, weapon);

			return;
		}

		// dispatch non-GUN types before any gun-specific logic
		if (!(weapon instanceof GunWeapon gunWeapon)) {
			handleNonGunInteract(event, player, weapon, leftClick, rightClick);
			return;
		}

		// no interruption while the weapon is reloading — but Shoot.Circumstance.Reloading: deny must still fire
		// ON_DENY on the press that triggered it, so the circumstance check runs BEFORE this early return. When
		// Reloading is unconfigured (or every configured circumstance is satisfied), firstDenied is null and the
		// early return stays exactly as silent as before.
		if (gunWeapon.isReloading()) {
			if (fireClick) {
				HandlingData.Circumstance denied = CircumstanceRules.firstDenied(player, gunWeapon);
				if (denied != null) fireDeny(gunWeapon, player, denied.key());
			}
			event.setCancelled(true);
			return;
		}

		if (!fireClick) return;

		// cancel block interaction
		event.setUseInteractedBlock(Event.Result.DENY);
		event.setUseItemInHand(Event.Result.DENY);

		SelectiveFire selectiveFire = gunWeapon.getCurrentSelectiveFire();


		if (selectiveFire == SelectiveFire.AUTO) {
			// handle the AUTO mode with full auto task
			shootFullAuto(gunWeapon, player, item);
		} else {
			// handle the BURST and SINGLE modes
			shootOtherModes(gunWeapon, player);
		}
	}

	@EventHandler
	public void onBlockPlace(BlockPlaceEvent event) {
		if (!cancelsBreakBlocks(event.getPlayer())) return;

		event.setCancelled(true);
	}

	@EventHandler
	public void onBlockBreak(BlockBreakEvent event) {
		if (!cancelsBreakBlocks(event.getPlayer())) return;

		event.setCancelled(true);
	}

	/**
	 * {@code Information.Cancel.Arm_Swing} (default {@code false}): cancels the left-click arm-swing animation
	 * while holding a weapon configured with the flag.
	 */
	@EventHandler
	public void onPlayerAnimation(PlayerAnimationEvent event) {
		if (event.getAnimationType() != PlayerAnimationType.ARM_SWING) return;

		Player    player = event.getPlayer();
		ItemStack item   = player.getInventory().getItemInMainHand();
		if (!weaponService.isWeapon(item)) return;

		Weapon weapon = weaponService.validateAndGetWeapon(player, item);
		if (weapon == null) return;

		HandlingData handling = weapon.getHandlingData();
		if (handling != null && handling.getCancel().armSwing()) {
			event.setCancelled(true);
		}
	}

	@EventHandler
	public void onPlayerInteractWithEntity(PlayerInteractEntityEvent event) {
		Player    player = event.getPlayer();
		ItemStack item   = player.getInventory().getItemInMainHand();

		if (!weaponService.isWeapon(item)) return;
		event.setCancelled(true);

		if (player.isDead() || !combatEligibility.canBeHit(player)) return;

		Weapon weapon = weaponService.validateAndGetWeapon(player, item);
		if (weapon == null) return;

		// non-GUN types: no right-click-on-entity behavior
		if (!(weapon instanceof GunWeapon gunWeapon)) return;

		// Shoot.Trigger: left_click - right click is the scope input, never a shot (BZ-EV-14). Not scoped here: the
		// client follows up with a USE_ITEM that reaches onPlayerInteract as RIGHT_CLICK_AIR and scopes there, and
		// cycleScope toggles, so scoping in both handlers would scope straight back out.
		if (isLeftClickTrigger(gunWeapon)) return;

		if (gunWeapon.isReloading()) {
			return;
		}

		// ignore the actions since this event is for right click interactions with entity

		SelectiveFire selectiveFire = gunWeapon.getCurrentSelectiveFire();

		if (selectiveFire == SelectiveFire.AUTO) {
			shootFullAuto(gunWeapon, player, item);
		} else {
			shootOtherModes(gunWeapon, player);
		}
	}

	@EventHandler(priority = EventPriority.LOWEST)
	public void onEntityDamage(EntityDamageByEntityEvent event) {
		if (!(event.getDamager() instanceof Player player)) return;

		// Drain the per-action pendingDamage sets first, regardless of which guard returns below.
		// MeleeAction adds its target UUID before calling target.damage(...) inside the raytracer's
		// impactHandler; the inner re-fired event then short-circuits at the raytracer flag check
		// without ever reaching a removal call below, leaving the UUID stranded in the static set
		// and consuming the next legitimate hit on that entity. Removing here ensures the sets
		// always drain in lock-step with the inner event they were added for.
		UUID    targetUuid         = event.getEntity().getUniqueId();
		boolean wasMeleeRefire     = MeleeAction.pendingDamage.remove(targetUuid);
		boolean wasThrowableRefire = ThrowableAction.pendingDamage.remove(targetUuid);
		boolean wasIncendRefire    = IncendiaryAction.pendingDamage.remove(targetUuid);

		// Allow damage that was applied programmatically by the unified raytracer (gun hitscan,
		// stepped slow projectiles, etc.). Without this guard the raytracer's living.damage(...)
		// call would be cancelled below as if it were a vanilla fist punch with a gun in hand.
		if (WeaponRaytracer.isRaytraceDamageInProgress()) return;

		// Same allowance for damage applied programmatically by MeleeAction / ThrowableAction /
		// IncendiaryAction themselves outside the raytracer flag window.
		if (wasMeleeRefire || wasThrowableRefire || wasIncendRefire) return;

		ItemStack item = player.getInventory().getItemInMainHand();
		if (!weaponService.isWeapon(item)) return;

		// For melee weapons: cancel the vanilla attack and trigger MeleeAction directly.
		// When the player clicks on an entity the client sends an attack-entity packet, which
		// fires EntityDamageByEntityEvent but does NOT always fire PlayerInteractEvent.
		// Running MeleeAction here ensures damage is applied regardless of which events arrive.
		// tryClaimMeleeSwing prevents double-damage when the companion PlayerInteractEvent also
		// fires for the same click (see MELEE_DEDUP_WINDOW_MS).
		Weapon weapon = weaponService.validateAndGetWeapon(player, item);
		if (weapon instanceof MeleeWeapon melee) {
			event.setCancelled(true);
			if (tryClaimMeleeSwing(melee.getUuid())) {
				boolean hit = new MeleeAction(melee, raytracer, meleeCooldowns, effectRunner).activate(player);
				if (hit) melee.applyOnHitDurability(player, player.getInventory().getHeldItemSlot());
			}
			return;
		}

		// cancel the default Minecraft attack damage for all other weapon types.
		event.setCancelled(true);
	}

	@EventHandler
	public void onWeaponHeld(PlayerItemHeldEvent event) {
		Player player = event.getPlayer();

		// previous slot: run cleanup + ON_HOLSTER when it held a weapon
		ItemStack previousItem   = player.getInventory().getItem(event.getPreviousSlot());
		Weapon    previousWeapon = weaponService.validateAndGetWeapon(player, previousItem);

		if (previousWeapon != null) holster(player, previousWeapon);

		// new slot: ON_EQUIP when it holds a weapon
		ItemStack newItem   = player.getInventory().getItem(event.getNewSlot());
		Weapon    newWeapon = weaponService.validateAndGetWeapon(player, newItem);

		if (newWeapon != null) equip(player, newWeapon);
	}

	/**
	 * The F key moves a weapon into or out of the main hand without a {@link PlayerItemHeldEvent}, so it gets the
	 * same holster/equip handling here - without it an F swap skipped {@code Information.Equip_Delay} (BZ-EV-15).
	 * MONITOR + ignoreCancelled: a swap that {@code WeaponSelectiveFireChangeListener} cancels (selective-fire
	 * change, spyglass fire, {@code Cancel.Swap_Hands}) moves nothing.
	 */
	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onSwapHands(PlayerSwapHandItemsEvent event) {
		Player player = event.getPlayer();

		// getOffHandItem() is the item leaving the main hand, getMainHandItem() the one entering it
		Weapon outgoing = weaponService.validateAndGetWeapon(player, event.getOffHandItem());
		if (outgoing != null) holster(player, outgoing);

		Weapon incoming = weaponService.validateAndGetWeapon(player, event.getMainHandItem());
		if (incoming != null) equip(player, incoming);
	}

	/**
	 * A weapon left the main hand: cleanup + ON_HOLSTER.
	 */
	private void holster(Player player, Weapon previousWeapon) {
		UUID weaponUuid = previousWeapon.getUuid();

		// unscoped and reset recoil for any weapon type
		previousWeapon.unScope(player, true);
		previousWeapon.getRecoil().resetRecoilPattern();

		// drop the SINGLE/BURST press lock, equip-delay gate and held-trigger gate so the new selection starts
		// on a clean trigger (applies to both gun and incendiary weapons, all of which share these maps)
		GunFireDispatcher.unlock(weaponUuid);
		equipDelayUntil.remove(weaponUuid);
		pressHoldState.remove(weaponUuid);

		// drop the melee dedup timestamp so swapping weapons doesn't carry stale gating across selections
		lastMeleeSwingMs.remove(weaponUuid);

		if (previousWeapon instanceof GunWeapon) {
			// cancel any active auto fire
			FullAutoTask autoTask = autoTasks.get(weaponUuid);
			if (autoTask != null) {
				autoTask.stop();
			}
		}

		// cancel active incendiary / biological tasks
		RepeatingTimer activeTask = activeTasks.remove(weaponUuid);
		if (activeTask != null) {
			activeTask.stop();
		}

		// drop any pending biological release callback so the charge dies with the swap
		releaseCallbacks.remove(weaponUuid);
		continuousFire.remove(weaponUuid);

		EffectContext holsterCtx = EffectContext.builder().weapon(previousWeapon).source(player).build();
		effectRunner.run(previousWeapon, EffectHook.ON_HOLSTER, holsterCtx);
	}

	/**
	 * A weapon entered the main hand: ON_EQUIP + the {@code Information.Equip_Delay} gate.
	 */
	private void equip(Player player, Weapon newWeapon) {
		EffectContext equipCtx = EffectContext.builder().weapon(newWeapon).source(player).build();
		effectRunner.run(newWeapon, EffectHook.ON_EQUIP, equipCtx);

		// Information.Equip_Delay: seed the dedicated gate — isPressGated (firing) and the scope-toggle branch
		// in onPlayerInteract both consult it via isEquipDelayActive.
		HandlingData handling = newWeapon.getHandlingData();
		if (handling != null && handling.getEquipDelay() > 0) {
			equipDelayUntil.put(newWeapon.getUuid(),
			                   System.currentTimeMillis() + handling.getEquipDelay() * MILLIS_PER_TICK);
		}
	}

	/**
	 * Records that a melee swing for {@code weaponUuid} is being processed and reports whether the caller should
	 * actually fire it. Returns {@code false} if a swing for this weapon was already claimed within the
	 * {@link #MELEE_DEDUP_WINDOW_MS} window — that is the second half of the same left-click being delivered via the
	 * companion {@link PlayerInteractEvent}/{@link EntityDamageByEntityEvent}, and we must not fire it twice.
	 */
	private boolean tryClaimMeleeSwing(UUID weaponUuid) {
		long now  = System.currentTimeMillis();
		Long last = lastMeleeSwingMs.get(weaponUuid);
		if (last != null && now - last < MELEE_DEDUP_WINDOW_MS) {
			return false;
		}
		lastMeleeSwingMs.put(weaponUuid, now);
		return true;
	}

	private void handleNonGunInteract(PlayerInteractEvent event, Player player, Weapon weapon, boolean leftClick,
	                                  boolean rightClick) {
		event.setUseInteractedBlock(Event.Result.DENY);
		event.setUseItemInHand(Event.Result.DENY);

		// block all actions while reloading
		if (weapon.isReloading()) return;

		if (weapon instanceof ThrowableWeapon throwable) {
			if (rightClick) handleThrowablePress(throwable, player);
		} else if (weapon instanceof MeleeWeapon melee) {
			if (leftClick) {
				if (tryClaimMeleeSwing(melee.getUuid())) {
					boolean hit = new MeleeAction(melee, raytracer, meleeCooldowns, effectRunner).activate(player);
					if (hit) melee.applyOnHitDurability(player, player.getInventory().getHeldItemSlot());
				}
			}
		} else if (weapon instanceof IncendiaryWeapon incendiary) {
			if (!rightClick) return;

			IncendiaryAction action = new IncendiaryAction(plugin, weaponService, incendiary, raytracer, fireRegistry,
			                                               effectRunner);

			SelectiveFire mode = incendiary.getCurrentSelectiveFire();
			if (mode == SelectiveFire.AUTO) {
				handleIncendiaryAuto(incendiary, action, player);
			} else {
				handleIncendiaryPress(incendiary, action, player);
			}
		} else if (weapon instanceof BiologicalWeapon biological) {
			if (rightClick) handleBiologicalCharge(biological, player);
		} else if (weapon instanceof BeamWeapon beam) {
			if (rightClick) handleBeamCharge(beam, player);
		}
	}

	/**
	 * Charge-then-release trigger for beam weapons — mirrors {@link #handleBiologicalCharge}. The charge-preview
	 * draw ({@link BeamAction#previewTick}) is wired in as the {@link ChargeController}'s
	 * {@link ChargeController.TickListener}, so it lives and dies with the same charge timer instead of a second
	 * one this listener would have to remember to stop.
	 */
	private void handleBeamCharge(BeamWeapon weapon, Player player) {
		BeamAction       action     = new BeamAction(plugin, weapon, raytracer, weaponService, effectRunner,
		                                             blockDamageManager);
		ChargeController controller = new ChargeController(plugin, weapon, weapon.getCharge(), effectRunner,
		                                                   activeTasks, level -> action.fire(player, level),
		                                                   action::previewTick);

		handleChargeHold(weapon.getUuid(), player, () -> startCharge(weapon, player, controller),
		                 () -> controller.release(player));
	}

	/**
	 * Charge-then-release trigger for biological weapons — wires a {@link ChargeController} up to
	 * {@link BiologicalAction#fire(Player, int)} and hands it to the category-agnostic {@link #handleChargeHold}.
	 * The empty-magazine gate runs only on the press that would actually start a charge (mirrors the old
	 * {@code BiologicalAction#start}, which only ran on the same press).
	 */
	private void handleBiologicalCharge(BiologicalWeapon weapon, Player player) {
		BiologicalAction action     = new BiologicalAction(weapon, raytracer, effectRunner, statusService,
		                                                   weaponService);
		ChargeController controller = new ChargeController(plugin, weapon, weapon.getBiologicalData().getCharge(),
		                                                   effectRunner, activeTasks, level -> action.fire(player, level));

		handleChargeHold(weapon.getUuid(), player, () -> startCharge(weapon, player, controller),
		                 () -> controller.release(player));
	}

	/**
	 * Empty-magazine gate shared by the biological and beam charge-then-release triggers — both only touch
	 * {@link Weapon} members, so one helper covers both weapon categories.
	 */
	private boolean startCharge(Weapon weapon, Player player, ChargeController controller) {
		if (weapon.getAmmunitionData() != null && weapon.isMagazineEmpty()) {
			EmptyMagSoundGate.play(plugin, player, weapon, effectRunner);
			return false;
		}
		return controller.start(player);
	}

	/**
	 * Category-agnostic charge-then-release trigger for weapons whose fire action is a charge-and-release cycle
	 * (biological now; beam at gate {@code HC}). The first RMB press invokes {@code start}; subsequent RMB presses
	 * (Spigot fires them while RMB is held) keep the {@link WeaponData#shooting} flag refreshed. When the watchdog
	 * detects the player has released RMB, it invokes {@code release}.
	 */
	private void handleChargeHold(UUID weaponUuid, Player player, BooleanSupplier start, Runnable release) {
		AtomicReference<WeaponData> existing = continuousFire.get(weaponUuid);
		if (existing != null) {
			existing.get().shooting = true;
			return;
		}

		// Information.Equip_Delay gates a charge start like every other first press (BZ-EV-17)
		if (isEquipDelayActive(weaponUuid)) return;

		if (!start.getAsBoolean()) return;

		WeaponData freshWeaponData = new WeaponData();
		freshWeaponData.shooting = true;
		AtomicReference<WeaponData> ref = new AtomicReference<>(freshWeaponData);
		continuousFire.put(weaponUuid, ref);

		releaseCallbacks.put(weaponUuid, release);

		// Release-detection watchdog. Must be synchronous: when the player releases RMB, this fires the release
		// callback, which raytracer-calls — getNearbyEntities is main-thread only.
		new RepeatingTimer(plugin, 4L, time -> {
			AtomicReference<WeaponData> stillShooting = continuousFire.get(weaponUuid);
			if (stillShooting == null) {
				time.stop();
				releaseCallbacks.remove(weaponUuid);
				return;
			}
			if (!stillShooting.get().shooting) {
				time.stop();
				continuousFire.remove(weaponUuid);
				Runnable callback = releaseCallbacks.remove(weaponUuid);
				if (callback != null) callback.run();
				return;
			}
			stillShooting.get().shooting = false;
		}).start(false);
	}

	/**
	 * Single-press trigger for throwable weapons. Mirrors {@link #shootOtherModes(GunWeapon, Player)} and
	 * {@link #handleIncendiaryPress}: one throw per RMB press, gated by the same per-weapon held-trigger watchdog plus
	 * the cooldown gate. Throwables don't expose a configured per-shot cooldown, so the lock window is always
	 * {@link #MIN_PRESS_LOCK_TICKS} — without this gate, holding RMB on a grenade chain-fires throws every time Spigot
	 * resends a held-RMB PlayerInteractEvent.
	 */
	private void handleThrowablePress(ThrowableWeapon weapon, Player player) {
		UUID weaponUuid = weapon.getUuid();

		if (isPressGated(weaponUuid)) return;

		engagePressHoldWatchdog(weaponUuid, MIN_PRESS_LOCK_TICKS);

		new ThrowableAction(plugin, weapon, fireRegistry, effectRunner).activate(player);
	}

	/**
	 * SINGLE/BURST press-lock trigger for incendiary weapons. Mirrors {@link #shootOtherModes(GunWeapon, Player)}: one
	 * cone burst per RMB press, gated by the same per-weapon held-trigger watchdog plus the cooldown gate. The lock
	 * window is the larger of the incendiary's tick rate and {@link #MIN_PRESS_LOCK_TICKS}.
	 */
	private void handleIncendiaryPress(IncendiaryWeapon weapon, IncendiaryAction action, Player player) {
		UUID weaponUuid = weapon.getUuid();

		if (isPressGated(weaponUuid)) return;

		long lockTicks = Math.max(weapon.getIncendiaryData().getTickRate(), MIN_PRESS_LOCK_TICKS);
		engagePressHoldWatchdog(weaponUuid, lockTicks);

		action.fireOnce(player);
	}

	/**
	 * AUTO-mode trigger for incendiary weapons. Spawns a {@link RepeatingTimer} that calls
	 * {@link IncendiaryAction#fireOnce(Player)} every tick-rate ticks while RMB is held, plus a watchdog that cancels
	 * the loop a few ticks after the player releases RMB. The release-detection mechanism reuses
	 * {@link #continuousFire} — the {@code shooting} flag is set on every {@link PlayerInteractEvent} and cleared by
	 * the watchdog.
	 */
	private void handleIncendiaryAuto(IncendiaryWeapon weapon, IncendiaryAction action, Player player) {
		UUID weaponUuid = weapon.getUuid();

		EmptyMagSoundGate.refresh(weaponUuid);

		AtomicReference<WeaponData> existing = continuousFire.get(weaponUuid);
		if (existing != null) {
			// already running — just refresh the held flag so the watchdog doesn't kill it
			existing.get().shooting = true;
			return;
		}

		// The first spray fires inside the event that pulled the trigger; the loop below (a RepeatingTimer skips
		// its first scheduled run) continues the cadence from the next tick-rate boundary. Empty or broken: nothing
		// to loop over. Information.Equip_Delay gates the first spray like every other first press (BZ-EV-17).
		if (isEquipDelayActive(weaponUuid) || !action.fireOnce(player)) return;

		WeaponData freshWeaponData = new WeaponData();
		freshWeaponData.shooting = true;
		AtomicReference<WeaponData> ref = new AtomicReference<>(freshWeaponData);
		continuousFire.put(weaponUuid, ref);

		int tickRate = Math.max(1, weapon.getIncendiaryData().getTickRate());
		RepeatingTimer sprayLoop = new RepeatingTimer(plugin, tickRate, time -> {
			if (!action.fireOnce(player)) {
				time.stop();
				activeTasks.remove(weaponUuid);
				continuousFire.remove(weaponUuid);
			}
		});
		sprayLoop.start(false);
		activeTasks.put(weaponUuid, sprayLoop);

		// release-detection watchdog — mirrors the AUTO gun pattern
		new RepeatingTimer(plugin, tickRate + 3L, time -> {
			AtomicReference<WeaponData> stillShooting = continuousFire.get(weaponUuid);
			if (stillShooting == null) {
				time.stop();
				return;
			}
			if (!stillShooting.get().shooting) {
				time.stop();
				continuousFire.remove(weaponUuid);
				RepeatingTimer running = activeTasks.remove(weaponUuid);
				if (running != null) running.stop();
				return;
			}
			stillShooting.get().shooting = false;
		}).start(true);
	}

	/**
	 * SINGLE/BURST press gate. Returns {@code true} if the current RMB event should be dropped — either because the
	 * held-trigger watchdog is still tracking RMB-held state from a prior shot (in which case the held flag is
	 * refreshed so the watchdog knows RMB is still down), or because the per-shot cooldown has not expired yet. Returns
	 * {@code false} if the press is genuine and the caller should fire — in which case the caller MUST follow up with
	 * {@link #engagePressHoldWatchdog(UUID, long)} so the next held-RMB event is correctly suppressed.
	 */
	private boolean isPressGated(UUID weaponUuid) {
		AtomicReference<WeaponData> held = pressHoldState.get(weaponUuid);
		if (held != null) {
			// already in hold state — refresh the flag so the watchdog knows RMB is still down
			held.get().shooting = true;
			EmptyMagSoundGate.refresh(weaponUuid);
			return true;
		}

		// cooldown gate (rapid release-and-press faster than the weapon's natural fire rate) - shared with
		// GunFireDispatcher's scoped F fire path (weapons-roadmap.md gate HP review) - plus Information.Equip_Delay
		// so a fresh equip can't skip its own delay window on the first press.
		return GunFireDispatcher.isLocked(weaponUuid) || isEquipDelayActive(weaponUuid);
	}

	/**
	 * {@code Information.Equip_Delay}: {@code true} while a recently-equipped weapon's delay window (seeded in
	 * {@link #equip}) hasn't elapsed yet. Read-only — unlike {@link #isPressGated(UUID)} this never mutates
	 * {@link #pressHoldState}, so it is safe to call from the scope-toggle branch without side effects. Backed by
	 * its own {@link #equipDelayUntil} map, not {@link GunFireDispatcher}'s shared fire-rate gate — see that field's
	 * javadoc.
	 */
	private boolean isEquipDelayActive(UUID weaponUuid) {
		return isLockActive(equipDelayUntil, weaponUuid);
	}

	private boolean isLockActive(Map<UUID, Long> lockMap, UUID weaponUuid) {
		Long lockedUntil = lockMap.get(weaponUuid);
		return lockedUntil != null && System.currentTimeMillis() < lockedUntil;
	}

	private boolean isLeftClickTrigger(Weapon weapon) {
		HandlingData handling = weapon.getHandlingData();
		return handling != null && handling.getTrigger() == HandlingData.Trigger.LEFT_CLICK;
	}

	/**
	 * {@code Information.Cancel.Break_Blocks} (default {@code true} — today's behaviour before gate {@code HE}).
	 */
	private boolean cancelsBreakBlocks(Player player) {
		ItemStack item = player.getInventory().getItemInMainHand();
		if (!weaponService.isWeapon(item)) return false;

		Weapon weapon = weaponService.validateAndGetWeapon(player, item);
		if (weapon == null) return false;

		HandlingData handling = weapon.getHandlingData();
		return handling == null || handling.getCancel().breakBlocks();
	}

	private void fireDeny(Weapon weapon, Player player, String reasonKey) {
		EffectContext ctx = EffectContext.builder().weapon(weapon).source(player).denyReason(reasonKey).build();
		effectRunner.run(weapon, EffectHook.ON_DENY, ctx);
	}

	/**
	 * Records the per-shot cooldown deadline and starts the held-trigger watchdog for the given weapon. Must be called
	 * immediately after a SINGLE/BURST shot fires. The watchdog reads the {@link WeaponData#shooting} flag every
	 * {@link #MIN_PRESS_LOCK_TICKS} ticks and clears the weapon's entry once it observes one full cycle without a
	 * refresh — indicating the player has released RMB and the trigger should be re-armed for the next press. Until the
	 * entry is cleared, {@link #isPressGated(UUID)} will continue to drop incoming events for this weapon.
	 */
	private void engagePressHoldWatchdog(UUID weaponUuid, long lockTicks) {
		GunFireDispatcher.lock(weaponUuid, lockTicks);

		WeaponData freshWeaponData = new WeaponData();
		freshWeaponData.shooting = true;
		AtomicReference<WeaponData> ref = new AtomicReference<>(freshWeaponData);
		pressHoldState.put(weaponUuid, ref);

		// release-detection watchdog. Pure flag-flipping + map mutation, so safe to run async.
		new RepeatingTimer(plugin, MIN_PRESS_LOCK_TICKS, time -> {
			AtomicReference<WeaponData> stillHeld = pressHoldState.get(weaponUuid);
			if (stillHeld == null) {
				// already cleared (e.g., by weapon swap)
				time.stop();
				return;
			}
			if (!stillHeld.get().shooting) {
				// no PlayerInteractEvent refreshed the flag in the last cycle — RMB has been released. Re-arm the
				// trigger so the next press fires a fresh shot.
				time.stop();
				pressHoldState.remove(weaponUuid);
				return;
			}
			stillHeld.get().shooting = false;
		}).start(true);
	}

	/**
	 * Press-locked SINGLE/BURST trigger.
	 *
	 * <p>Spigot fires {@link PlayerInteractEvent} repeatedly while the client holds RMB on a weapon item — there is no
	 * first-class "edge press" signal. To make SINGLE/BURST behave as one-shot-per-press despite this, we run a
	 * release-detection watchdog ({@link #engagePressHoldWatchdog(UUID, long)}) that drops every event arriving while a
	 * previous trigger pull is still "held". The watchdog only clears its entry once it observes a quiet tick window
	 * (the held-RMB packet stream has stopped), at which point the trigger is re-armed for the next genuine press.
	 *
	 * <p>The cooldown gate ({@link GunFireDispatcher#isLocked}/{@link GunFireDispatcher#lock}) is preserved as an
	 * orthogonal rate limiter that prevents firing faster than the weapon's natural fire rate even when the player
	 * release-and-re-presses RMB rapidly — shared with {@code WeaponSelectiveFireChangeListener}'s scoped F fire
	 * (weapons-roadmap.md gate {@code HP} review) so mashing either input is rate-limited identically.
	 */
	private void shootOtherModes(GunWeapon weapon, Player player) {
		UUID weaponUuid = weapon.getUuid();

		if (isPressGated(weaponUuid)) return;

		// Shoot.Circumstance: evaluated once per genuine press. Engage the same held-trigger watchdog on a denial
		// so a held RMB doesn't re-fire ON_DENY every tick until release — matches the "once per press" contract.
		HandlingData.Circumstance denied = CircumstanceRules.firstDenied(player, weapon);
		if (denied != null) {
			engagePressHoldWatchdog(weaponUuid, MIN_PRESS_LOCK_TICKS);
			fireDeny(weapon, player, denied.key());
			return;
		}

		long lockTicks = GunFireDispatcher.lockTicksFor(weapon);

		engagePressHoldWatchdog(weaponUuid, lockTicks);

		// fire one shot (SINGLE) or one burst sequence (BURST). The inner SequenceTimer in shoot()
		// already spaces individual burst rounds by projectileCooldown.
		shoot(player, weapon);

		// recoil is reset when the lock window expires so the next press starts on a fresh pattern
		new CountdownTimer(plugin, 0L, 0L, lockTicks, null, null,
		                   timer -> weapon.getRecoil().resetRecoilPattern()).start(false);
	}

	private void shootFullAuto(GunWeapon weapon, Player player, ItemStack item) {
		UUID weaponUuid = weapon.getUuid();
		if (!autoTasks.containsKey(weaponUuid)) {
			// Information.Equip_Delay / Shoot.Circumstance: only checked on the press that would start a fresh
			// burst. Gated by the same press-hold watchdog shootOtherModes uses, so a denial doesn't re-fire
			// ON_DENY on every repeated PlayerInteractEvent Spigot sends while RMB stays held — the entry only
			// clears once the watchdog observes RMB has actually been released.
			if (isPressGated(weaponUuid)) return;

			HandlingData.Circumstance denied = CircumstanceRules.firstDenied(player, weapon);
			if (denied != null) {
				engagePressHoldWatchdog(weaponUuid, MIN_PRESS_LOCK_TICKS);
				fireDeny(weapon, player, denied.key());
				return;
			}

			var autoTask = new FullAutoTask(plugin, weaponService, weapon, raytracer, player, item,
			                                () -> {
												autoTasks.remove(weaponUuid);
												continuousFire.remove(weaponUuid);
											}, effectRunner);

			autoTasks.put(weaponUuid, autoTask);

			WeaponData freshWeaponData = new WeaponData();
			freshWeaponData.shooting = true;
			AtomicReference<WeaponData> weaponDataAtomicReference = new AtomicReference<>(freshWeaponData);

			continuousFire.put(weaponUuid, weaponDataAtomicReference);

			// Schedule first (so a cancel() from inside run() has a task id to cancel), then fire the first round
			// synchronously: index 0 of the cadence table is always a shot, and the scheduled ticks continue from
			// index 1 on the next tick. Previously the task's initial delay was the full cooldown, so every fresh
			// AUTO press waited that long before its first round.
			autoTask.start(false);
			autoTask.run();

			// watchdog timer for AUTO mode
			long watchdog = weapon.getProjectileData().getCooldown() + 2L;
			new RepeatingTimer(plugin, watchdog, time -> {
				AtomicReference<WeaponData> stillShooting = continuousFire.get(weaponUuid);

				if (stillShooting == null) {
					time.stop();
					weapon.getRecoil().resetRecoilPattern();
					return;
				}

				if (!stillShooting.get().shooting) {
					FullAutoTask task = autoTasks.get(weaponUuid);

					if (task != null) {
						task.cancel();
					}

					time.stop();
					continuousFire.remove(weaponUuid);
					weapon.getRecoil().resetRecoilPattern();
					return;
				}

				stillShooting.get().shooting = false;
			}).start(true);
		} else {
			AtomicReference<WeaponData> weaponData = continuousFire.get(weaponUuid);

			if (weaponData != null) {
				weaponData.get().shooting = true;
				EmptyMagSoundGate.refresh(weaponUuid);
			}
		}
	}

	/**
	 * The first round fires inside the event that pulled the trigger; a BURST's remaining rounds are spaced by the
	 * projectile cooldown. Delegates to {@link GunFireDispatcher} so a trigger click and
	 * {@code WeaponSelectiveFireChangeListener}'s scoped {@code F} fire (weapons-roadmap.md gate {@code HP}) share
	 * the exact same dispatch instead of two copies of it.
	 */
	private void shoot(Player player, GunWeapon weapon) {
		GunFireDispatcher.shoot(plugin, weaponService, weapon, raytracer, effectRunner, player);
	}

	/**
	 * Held-RMB shared state. The {@link #shooting} flag is set to {@code true} on every RMB {@link PlayerInteractEvent}
	 * while a fire-task is active and cleared by a watchdog after one idle cycle; this is how the listener detects RMB
	 * release without a first-class "edge release" signal from Spigot.
	 *
	 * <ul>
	 *   <li>AUTO uses this via {@link #continuousFire} to know when to stop the {@link FullAutoTask}.
	 *   <li>SINGLE/BURST uses this via {@link #pressHoldState} to know when the trigger should be re-armed for the
	 *       next press (the cooldown gate {@link GunFireDispatcher#isLocked} alone is not sufficient — once it
	 *       lapses mid-hold, the next held-RMB event would otherwise fire a second shot).
	 *   <li>Biological charge-then-release uses this via {@link #continuousFire} to know when to fire the charged
	 *       shot.
	 * </ul>
	 */
	private static class WeaponData {

		private boolean shooting;

	}

}
