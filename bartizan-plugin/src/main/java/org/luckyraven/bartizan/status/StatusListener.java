package org.luckyraven.bartizan.status;

import com.cryptomorin.xseries.XMaterial;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.luckyraven.bartizan.api.event.WeaponStatusExpireEvent.Reason;
import org.luckyraven.bartizan.api.weapon.dto.StatusData;
import org.luckyraven.keystone.bean.listener.ListenerHandler;

import java.util.Optional;

/**
 * Bukkit-facing status lifecycle: quit/death cleanup and the consumed-item cure (weapons-roadmap.md gate
 * {@code HB}, §2.2 "Cure"/"Cleanup"). Registered like {@code WeaponDeathListener}/{@code WeaponReloadListener} via
 * {@code @ListenerHandler} scanning + constructor injection.
 */
@ListenerHandler
public class StatusListener implements Listener {

	private final StatusEffectService statusService;

	public StatusListener(StatusEffectService statusService) {
		this.statusService = statusService;
	}

	@EventHandler
	public void onPlayerQuit(PlayerQuitEvent event) {
		statusService.clear(event.getPlayer().getUniqueId(), Reason.QUIT);
	}

	/**
	 * {@code MONITOR}: {@code WeaponDeathListener} still needs to read the active status (its poison/wither
	 * kill-credit path) while it handles the same death, so this clears strictly after that has run.
	 */
	@EventHandler(priority = EventPriority.MONITOR)
	public void onPlayerDeath(PlayerDeathEvent event) {
		statusService.clear(event.getEntity().getUniqueId(), Reason.DEATH);
	}

	@EventHandler
	public void onItemConsume(PlayerItemConsumeEvent event) {
		Player player = event.getPlayer();

		Optional<ActiveStatus> status = statusService.activeOn(player.getUniqueId());
		if (status.isEmpty()) return;

		StatusData data = status.get().getWeapon().getBiologicalData().getStatus();
		ItemStack  item = event.getItem();

		boolean cures = data.getCure().items().stream()
		                    .anyMatch(name -> XMaterial.matchXMaterial(name).map(x -> x.isSimilar(item)).orElse(false));
		if (cures) statusService.cure(player.getUniqueId(), Reason.CURED);
	}

}
