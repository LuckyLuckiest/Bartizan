package org.luckyraven.bartizan.listener;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerToggleSprintEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.luckyraven.keystone.bean.autowire.AutowireTarget;
import org.luckyraven.keystone.bean.listener.ListenerHandler;
import org.luckyraven.bartizan.api.weapon.SkinState;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.api.weapon.dto.SkinsData;
import org.luckyraven.bartizan.weapon.WeaponService;

/**
 * Refreshes the held weapon's item whenever the player starts/stops sprinting, so a configured
 * {@code Skins.Sprint} (root or on any {@code Named} skin) custom model data / item model actually changes on
 * screen (weapons-roadmap.md gate {@code HJ}). A no-op for any weapon with no {@code Sprint} skin configured
 * anywhere - sprinting toggles far more often than shooting/reloading/scoping, so this listener only pays the
 * rebuild cost when it can actually change what's rendered.
 */
@ListenerHandler
@AutowireTarget({WeaponService.class})
public class WeaponSprintListener implements Listener {

	private final JavaPlugin    plugin;
	private final WeaponService weaponService;

	public WeaponSprintListener(JavaPlugin plugin, WeaponService weaponService) {
		this.plugin        = plugin;
		this.weaponService = weaponService;
	}

	@EventHandler
	public void onToggleSprint(PlayerToggleSprintEvent event) {
		Player    player = event.getPlayer();
		ItemStack item   = player.getInventory().getItemInMainHand();
		Weapon    weapon = weaponService.validateAndGetWeapon(player, item);

		if (weapon == null || !hasSprintSkin(weapon)) return;

		// PlayerToggleSprintEvent fires BEFORE Player#isSprinting() actually flips, so currentSkinState would see
		// the stale value if refreshed synchronously. Deferring one tick lets isSprinting() catch up - this also
		// means a cancelled toggle is naturally skipped, since isSprinting() will simply report unchanged.
		Bukkit.getScheduler().runTask(plugin, () -> weaponService.persistHeldWeapon(weapon, player));
	}

	private boolean hasSprintSkin(Weapon weapon) {
		SkinsData skins = weapon.getSkinsData();
		if (skins == null) return false;

		if (skins.state(SkinState.SPRINT) != null) return true;

		for (String name : skins.namedKeys()) {
			SkinsData.NamedSkin named = skins.named(name);
			if (named != null && named.state(SkinState.SPRINT) != null) return true;
		}

		return false;
	}

}
