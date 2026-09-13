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
import org.luckyraven.bartizan.effect.EffectContext;
import org.luckyraven.bartizan.effect.EffectRunner;
import org.luckyraven.bartizan.weapon.WeaponService;
import org.luckyraven.bartizan.api.event.WeaponReloadCompleteEvent;
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

		EffectContext ctx = EffectContext.builder().weapon(event.getWeapon()).source(event.getPlayer()).build();
		effectRunner.run(event.getWeapon(), EffectHook.ON_RELOAD_START, ctx);
	}

	@EventHandler
	public void onReloadEnd(WeaponReloadCompleteEvent event) {
		reloadingPlayers.remove(event.getPlayer().getUniqueId());

		EffectContext ctx = EffectContext.builder().weapon(event.getWeapon()).source(event.getPlayer()).build();
		effectRunner.run(event.getWeapon(), EffectHook.ON_RELOAD_END, ctx);
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
