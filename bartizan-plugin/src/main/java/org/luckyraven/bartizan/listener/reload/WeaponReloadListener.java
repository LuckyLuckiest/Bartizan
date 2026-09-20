package org.luckyraven.bartizan.listener.reload;

import lombok.RequiredArgsConstructor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.luckyraven.keystone.bean.autowire.AutowireTarget;
import org.luckyraven.keystone.bean.listener.ListenerHandler;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.api.weapon.dto.EffectHook;
import org.luckyraven.bartizan.api.weapon.dto.HudData;
import org.luckyraven.bartizan.effect.EffectContext;
import org.luckyraven.bartizan.effect.EffectRunner;
import org.luckyraven.bartizan.weapon.WeaponService;
import org.luckyraven.bartizan.api.event.WeaponReloadCompleteEvent;
import org.luckyraven.bartizan.api.event.WeaponReloadStageEvent;
import org.luckyraven.bartizan.api.event.WeaponReloadStartEvent;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@ListenerHandler
@AutowireTarget({WeaponService.class, EffectRunner.class})
@RequiredArgsConstructor
public class WeaponReloadListener implements Listener {

	private final WeaponService weaponService;
	private final EffectRunner  effectRunner;
	private final Set<UUID>     reloadingPlayers = new HashSet<>();

	@EventHandler
	public void onReloadStart(WeaponReloadStartEvent event) {
		reloadingPlayers.add(event.getPlayer().getUniqueId());

		// gate HJ: refresh the item so a Skins.Reload skin actually shows while reloading.
		weaponService.persistHeldWeapon(event.getWeapon(), event.getPlayer());

		EffectContext ctx = EffectContext.builder().weapon(event.getWeapon()).source(event.getPlayer()).build();
		effectRunner.run(event.getWeapon(), EffectHook.ON_RELOAD_START, ctx);

		if (isReloadItemCooldownEnabled(event.getWeapon())) {
			// weapons-roadmap.md gate HO: the remaining duration, not the full one — a resumed reload must not
			// leave the overlay replaying time already spent on stages skipped by the resume.
			long ticks = event.getWeapon().reloadRemainingDurationTicks();
			if (ticks > 0) event.getPlayer().setCooldown(event.getWeapon().getMaterial(), (int) ticks);
		}
	}

	@EventHandler
	public void onReloadStage(WeaponReloadStageEvent event) {
		EffectContext ctx = EffectContext.builder().weapon(event.getWeapon()).source(event.getPlayer()).build();
		effectRunner.run(event.getWeapon(), EffectHook.ON_RELOAD_STAGE, ctx);
	}

	@EventHandler
	public void onReloadEnd(WeaponReloadCompleteEvent event) {
		reloadingPlayers.remove(event.getPlayer().getUniqueId());

		// gate HJ: refresh the item so the Skins.Reload skin clears - runs whether the reload finished normally
		// or was swap-cancelled, since isReloading() is already false by the time either variant of this event
		// fires.
		weaponService.persistHeldWeapon(event.getWeapon(), event.getPlayer());

		// A swap-cancelled reload (InstantReload/NumberedReload#stopReloading) also raises this event so state
		// stays consistent, but ON_RELOAD_CANCEL (below, via onHeldSlotChange) is the feedback hook for that case
		// — running ON_RELOAD_END too would double-fire the reload-complete cue.
		if (event.isInterrupted()) {
			// The cooldown overlay was set for the full reload duration at start; a swap-cancelled reload must not
			// leave the player staring at a cooldown that will never finish counting down on its own.
			if (isReloadItemCooldownEnabled(event.getWeapon())) {
				event.getPlayer().setCooldown(event.getWeapon().getMaterial(), 0);
			}
			return;
		}

		EffectContext ctx = EffectContext.builder().weapon(event.getWeapon()).source(event.getPlayer()).build();
		effectRunner.run(event.getWeapon(), EffectHook.ON_RELOAD_END, ctx);
	}

	private boolean isReloadItemCooldownEnabled(Weapon weapon) {
		HudData hud = weapon.getHudData();
		return hud != null && hud.isReloadItemCooldown();
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onHeldSlotChange(PlayerItemHeldEvent event) {
		Player player = event.getPlayer();

		if (!reloadingPlayers.contains(player.getUniqueId())) return;

		ItemStack heldItem = player.getInventory().getItemInMainHand();
		Weapon    weapon   = weaponService.validateAndGetWeapon(player, heldItem);

		if (weapon == null) return;

		boolean wasReloading = weapon.isReloading();
		weapon.stopReloading();

		if (wasReloading) {
			EffectContext ctx = EffectContext.builder().weapon(weapon).source(player).build();
			effectRunner.run(weapon, EffectHook.ON_RELOAD_CANCEL, ctx);
		}
	}

	@EventHandler
	public void onPlayerQuit(PlayerQuitEvent event) {
		reloadingPlayers.remove(event.getPlayer().getUniqueId());
	}

}
