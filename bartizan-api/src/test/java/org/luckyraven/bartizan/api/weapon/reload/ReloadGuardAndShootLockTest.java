package org.luckyraven.bartizan.api.weapon.reload;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.ammo.Ammunition;
import org.luckyraven.bartizan.api.weapon.MeleeWeapon;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.api.weapon.WeaponType;
import org.luckyraven.bartizan.api.weapon.dto.AmmunitionData;
import org.luckyraven.bartizan.api.weapon.dto.MeleeData;
import org.luckyraven.bartizan.api.weapon.dto.ReloadData;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

/**
 * Covers gate {@code HG} review finding 2: {@link Reload#reload} must claim the {@code reloading} flag
 * synchronously, and {@link Reload#endReloading(Player, boolean)}'s {@code Shoot_Delay_After_Reload} gate.
 *
 * <p>The double-reload guard is exercised through a bare counting {@link Reload} subclass rather than {@link
 * InstantReload}/{@link NumberedReload} so the test isn't coupled to either concrete type's {@code
 * SequenceTimer}/action-bar plumbing — only the base class's guard in {@link Reload#reload} is under test here.
 */
@DisplayName("Reload — double-reload guard / Shoot_Delay_After_Reload")
class ReloadGuardAndShootLockTest {

	@Test
	@DisplayName("reload(): calling it twice before startReloading ever runs starts only one reload")
	void reload_calledTwiceBeforeStartReloadingRuns_startsOnlyOneReload() {
		CountingReload reload = countingReload(false);
		JavaPlugin     plugin = mock(JavaPlugin.class);
		Player         player = mock(Player.class);

		reload.reload(plugin, player, true);
		reload.reload(plugin, player, true);

		assertEquals(1, reload.executeCount);
		assertTrue(reload.isReloading());
	}

	@Test
	@DisplayName("reload(): an executeReload abort that never starts must resetReloading() so the next reload() "
	             + "call isn't permanently blocked")
	void executeReload_earlyAbort_resetsFlagForNextReload() {
		CountingReload reload = countingReload(true);
		JavaPlugin     plugin = mock(JavaPlugin.class);
		Player         player = mock(Player.class);

		reload.reload(plugin, player, true);
		assertFalse(reload.isReloading(), "an abort before startReloading must not leave the flag stuck");

		reload.reload(plugin, player, true);
		assertEquals(2, reload.executeCount, "the flag reset must let a following reload() call through");
	}

	@Test
	@DisplayName("endReloading: interrupted=false sets the shoot lock, interrupted=true does not")
	void endReloading_setsShootLock_onlyWhenNotInterrupted() {
		ReloadData reloadData = ReloadData.builder().cooldown(20).type(ReloadType.getType("instant"))
		                                  .shootDelayAfterReload(40).build();
		Ammunition    ammo    = mock(Ammunition.class);
		MeleeWeapon   weapon  = weaponWith(reloadData, new AmmunitionData(List.of(ammo), 6, 1, 1));
		InstantReload reload  = new InstantReload(weapon, ammo);
		Player        player  = mock(Player.class);

		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
			bukkit.when(Bukkit::getPluginManager).thenReturn(mock(PluginManager.class));

			reload.endReloading(player, true); // interrupted — no shoot lock
			assertFalse(weapon.isShootLocked());

			reload.endReloading(player, false); // completed — shoot lock engaged
			assertTrue(weapon.isShootLocked());
		}
	}

	private static CountingReload countingReload(boolean abortWithoutStarting) {
		Ammunition  ammo   = mock(Ammunition.class);
		MeleeWeapon weapon = weaponWith(
				ReloadData.builder().cooldown(20).type(ReloadType.getType("instant")).build(),
				new AmmunitionData(List.of(ammo), 6, 1, 1));

		CountingReload reload = new CountingReload(weapon, ammo);
		reload.abortWithoutStarting = abortWithoutStarting;
		return reload;
	}

	private static MeleeWeapon weaponWith(ReloadData reloadData, AmmunitionData ammunitionData) {
		MeleeData melee = new MeleeData(8.0, 3.0, 10, 0.5);
		return new MeleeWeapon(UUID.randomUUID(), "test_knife", "&fTest Knife", WeaponType.MELEE, Material.IRON_HOE,
		                       0, (short) 50, List.of(), false, null, melee, reloadData, ammunitionData);
	}

	/** Minimal {@link Reload} stand-in that never touches a real timer — see class javadoc. */
	private static class CountingReload extends Reload {

		int     executeCount;
		boolean abortWithoutStarting;

		CountingReload(Weapon weapon, Ammunition ammunition) {
			super(weapon, ammunition);
		}

		@Override
		public void stopReloading() {
			// unused in this test
		}

		@Override
		protected void executeReload(JavaPlugin plugin, Player player, boolean removeAmmunition) {
			executeCount++;

			// Mirrors the real subclasses: executeReload only *schedules* the SequenceTimer interval-0 task that
			// calls startReloading — it does not call it synchronously — so `reloading` stays exactly whatever
			// Reload#reload's own guard set it to. abortWithoutStarting simulates an early-abort path (e.g. "not
			// enough ammo") that returns without the timer ever being started.
			if (abortWithoutStarting) {
				resetReloading();
			}
		}
	}

}
