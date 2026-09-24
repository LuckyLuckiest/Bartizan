package org.luckyraven.bartizan.listener.selective;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.luckyraven.keystone.item.ItemBuilder;
import org.luckyraven.keystone.bean.autowire.AutowireTarget;
import org.luckyraven.keystone.bean.listener.ListenerHandler;
import org.luckyraven.keystone.util.ActionBarManager;
import org.luckyraven.keystone.util.ChatUtil;
import org.luckyraven.bartizan.api.raytrace.WeaponRaytracer;
import org.luckyraven.bartizan.api.weapon.GunWeapon;
import org.luckyraven.bartizan.api.weapon.SelectiveFire;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.api.weapon.dto.EffectHook;
import org.luckyraven.bartizan.api.weapon.dto.HandlingData;
import org.luckyraven.bartizan.api.weapon.dto.ScopeData;
import org.luckyraven.bartizan.api.weapon.dto.ScopeType;
import org.luckyraven.bartizan.effect.EffectContext;
import org.luckyraven.bartizan.effect.EffectRunner;
import org.luckyraven.bartizan.scope.SpyglassScopeTask;
import org.luckyraven.bartizan.weapon.CircumstanceRules;
import org.luckyraven.bartizan.weapon.WeaponService;
import org.luckyraven.bartizan.weapon.action.GunFireDispatcher;
import org.luckyraven.bartizan.api.event.WeaponChangeSelectiveFireEvent;

import java.util.UUID;

/**
 * Sneak + {@code F} still cycles {@code Selective_Fire} (unchanged). Not sneaking + {@code F} is otherwise a no-op
 * - except while a {@code Scope.Type: spyglass} weapon is scoped in (weapons-roadmap.md gate {@code HP}): the
 * client swallows left-click while the spyglass use is active, so {@code F} becomes the fire key instead. AUTO
 * hands off to {@link SpyglassScopeTask#startAutoFire}, which keeps firing until the scope-out poll stops it;
 * SINGLE/BURST fires immediately through {@link GunFireDispatcher} - the same path a trigger click uses.
 */
@ListenerHandler
@AutowireTarget({WeaponService.class, WeaponRaytracer.class, EffectRunner.class, SpyglassScopeTask.class})
public class WeaponSelectiveFireChangeListener implements Listener {

	private final JavaPlugin        plugin;
	private final WeaponService     weaponService;
	private final WeaponRaytracer   raytracer;
	private final EffectRunner      effectRunner;
	private final SpyglassScopeTask spyglassScopeTask;

	public WeaponSelectiveFireChangeListener(JavaPlugin plugin, WeaponService weaponService, WeaponRaytracer raytracer,
	                                         EffectRunner effectRunner, SpyglassScopeTask spyglassScopeTask) {
		this.plugin            = plugin;
		this.weaponService     = weaponService;
		this.raytracer         = raytracer;
		this.effectRunner      = effectRunner;
		this.spyglassScopeTask = spyglassScopeTask;
	}

	@EventHandler
	public void onSwapHand(PlayerSwapHandItemsEvent event) {
		Player    player = event.getPlayer();
		ItemStack item   = player.getInventory().getItemInMainHand();
		Weapon    weapon = weaponService.validateAndGetWeapon(player, item);

		// Information.Cancel.Swap_Hands (default false): block F outright while holding this weapon, regardless
		// of sneaking or whether it has Selective_Fire configured at all.
		if (weapon != null && cancelsSwapHands(weapon)) {
			event.setCancelled(true);
			return;
		}

		// Scope.Type: spyglass (weapons-roadmap.md gate HP): F fires while scoped instead of swapping hands.
		// Sneak + F still changes selective fire - checked below, unchanged.
		if (weapon instanceof GunWeapon gunWeapon && !player.isSneaking() && isSpyglassScoped(weapon)) {
			event.setCancelled(true);
			fireScoped(gunWeapon, player, item);
			return;
		}

		// check if the player is shifting
		if (!player.isSneaking()) return;

		// check if the player is holding a weapon with selective fire configured
		if (weapon == null) return;
		if (weapon.getCurrentSelectiveFire() == null) return;

		var newEvent = new WeaponChangeSelectiveFireEvent(weapon);
		Bukkit.getPluginManager().callEvent(newEvent);

		if (newEvent.isCancelled()) return;

		// change the selective fire of the weapon and cancel opening the inventory
		event.setCancelled(true);

		weapon.setCurrentSelectiveFire(
				weapon.getCurrentSelectiveFire().getNextState(weapon.getAllowedSelectiveFires()));

		// update the weapon data
		ItemBuilder itemBuilder = weaponService.getHeldWeaponItem(player);

		if (itemBuilder == null) return;

		weapon.updateWeaponData(itemBuilder, player);
		weapon.updateWeapon(player, itemBuilder, player.getInventory().getHeldItemSlot());

		ActionBarManager.send(player, "&6Selective Fire > &e" +
		                              ChatUtil.capitalize(weapon.getCurrentSelectiveFire().name().toLowerCase()));
	}

	/**
	 * Unscopes whatever weapon is about to leave the main hand on every path through {@link #onSwapHand} that lets
	 * the swap proceed uncancelled - not sneaking, no {@code Selective_Fire} configured, or a cancelled
	 * {@link WeaponChangeSelectiveFireEvent} (bug docket BZ-EV-09). Without this, a {@code Scope.Type: SLOWNESS}
	 * weapon (the default - any gun with no {@code Scope.Type: spyglass} key, e.g. the shipped {@code awp.yml})
	 * left the player permanently slowed and the weapon stuck "scoped" once it moved off-hand, with no cleanup
	 * path until they manually re-selected it. Registered at {@link EventPriority#MONITOR} and keyed off
	 * {@code event.isCancelled()} directly, so it covers every current AND future uncancelled exit with one guard
	 * instead of one patched into each. {@code Weapon#unScope} is a no-op unless the weapon is actually scoped, so
	 * this never affects an unscoped weapon or the {@code Cancel.Swap_Hands}/spyglass-fire paths that cancel the
	 * event themselves (the weapon never actually leaves the main hand on those).
	 */
	@EventHandler(priority = EventPriority.MONITOR)
	public void onSwapHandScopeCleanup(PlayerSwapHandItemsEvent event) {
		if (event.isCancelled()) return;

		Player player = event.getPlayer();
		Weapon weapon = weaponService.validateAndGetWeapon(player, player.getInventory().getItemInMainHand());

		if (weapon != null) {
			weapon.unScope(player, false);
		}
	}

	private boolean cancelsSwapHands(Weapon weapon) {
		HandlingData handling = weapon.getHandlingData();
		return handling != null && handling.getCancel().swapHands();
	}

	private boolean isSpyglassScoped(Weapon weapon) {
		ScopeData scopeData = weapon.getScopeData();
		return scopeData != null && scopeData.isScoped() && scopeData.getType() == ScopeType.SPYGLASS;
	}

	/**
	 * // ponytail: no held-trigger watchdog of its own - F is a discrete key press, not a held button Spigot keeps
	 * // re-firing, so it only needs the fire-rate gate below, not WeaponInteract's full press/release tracking.
	 */
	private void fireScoped(GunWeapon weapon, Player player, ItemStack item) {
		if (weapon.isReloading()) return;

		HandlingData.Circumstance denied = CircumstanceRules.firstDenied(player, weapon);
		if (denied != null) {
			EffectContext ctx = EffectContext.builder().weapon(weapon).source(player).denyReason(denied.key()).build();
			effectRunner.run(weapon, EffectHook.ON_DENY, ctx);
			return;
		}

		if (weapon.getCurrentSelectiveFire() == SelectiveFire.AUTO) {
			spyglassScopeTask.startAutoFire(weapon, player, item);
			return;
		}

		// Projectile.Cooldown fire-rate gate, shared with WeaponInteract's RMB click path through
		// GunFireDispatcher (weapons-roadmap.md gate HP review) - without it, mashing F fires every press with no
		// rate limit at all, draining the magazine and overlapping BURST sequences.
		UUID weaponUuid = weapon.getUuid();
		if (GunFireDispatcher.isLocked(weaponUuid)) return;
		GunFireDispatcher.lock(weaponUuid, GunFireDispatcher.lockTicksFor(weapon));

		GunFireDispatcher.shoot(plugin, weaponService, weapon, raytracer, effectRunner, player);
	}

}
