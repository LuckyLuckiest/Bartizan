package org.luckyraven.bartizan.hud;

import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.api.weapon.dto.AmmunitionData;
import org.luckyraven.bartizan.api.weapon.dto.HudData;
import org.luckyraven.bartizan.file.BartizanSettings;
import org.luckyraven.bartizan.weapon.WeaponService;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers {@link HudService} (weapons-roadmap.md gate {@code HD}): {@link HudService#tick()} is driven directly,
 * same shape as {@code StatusEffectServiceTest} — {@code Bukkit.getOnlinePlayers}/{@code createBossBar} are
 * statically mocked rather than standing up a real scheduler/server.
 */
@DisplayName("HudService")
class HudServiceTest {

	@BeforeAll
	static void primeMoneySymbol() throws ReflectiveOperationException {
		// WeaponPlaceholders.resolve() -> BartizanChatUtil.color() substitutes %money_symbol%, which is only ever
		// set by BartizanSettings#init() - not available in a plain unit test (same trap StatusEffectServiceTest
		// documents).
		Field field = BartizanSettings.class.getDeclaredField("moneySymbol");
		field.setAccessible(true);
		field.set(null, "$");
	}

	private final WeaponService weaponService = mock(WeaponService.class);
	private final HudService    service       = new HudService(mock(JavaPlugin.class), weaponService);

	private static Player player(ItemStack heldItem) {
		Player          player    = mock(Player.class);
		PlayerInventory inventory = mock(PlayerInventory.class);
		when(player.getUniqueId()).thenReturn(UUID.randomUUID());
		when(player.getInventory()).thenReturn(inventory);
		when(inventory.getItemInMainHand()).thenReturn(heldItem);
		return player;
	}

	private static Weapon hudWeapon(HudData hud, int magCapacity, int maxMagCapacity) {
		Weapon weapon = mock(Weapon.class);
		when(weapon.getHudData()).thenReturn(hud);
		when(weapon.isReloading()).thenReturn(false);
		// WeaponPlaceholders.resolve() unconditionally reads getDisplayName() for %weapon% (String.replace
		// evaluates its replacement argument even when the placeholder is absent from the template) - a null
		// display name would NPE there before the boss bar title is ever built.
		when(weapon.getDisplayName()).thenReturn("Test Weapon");

		AmmunitionData ammunitionData = mock(AmmunitionData.class);
		when(ammunitionData.getMaxMagCapacity()).thenReturn(maxMagCapacity);
		when(weapon.getAmmunitionData()).thenReturn(ammunitionData);
		when(weapon.getCurrentMagCapacity()).thenReturn(magCapacity);

		return weapon;
	}

	@Test
	@DisplayName("tick creates a boss bar for a held HUD weapon and sets its progress from the ammo fraction")
	void tick_createsBossBar_withAmmoProgress() {
		HudData.BossBarData bossBarData = new HudData.BossBarData("&6Test", BarColor.YELLOW, BarStyle.SEGMENTED_10);
		HudData              hud        = new HudData(null, bossBarData, false);
		Weapon               weapon     = hudWeapon(hud, 15, 30);

		ItemStack item   = mock(ItemStack.class);
		Player    player = player(item);
		when(weaponService.validateAndGetWeapon(player, item)).thenReturn(weapon);

		BossBar bar = mock(BossBar.class);
		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
			bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(player));
			bukkit.when(() -> Bukkit.createBossBar(any(), any(), any())).thenReturn(bar);

			service.tick();
		}

		verify(bar).addPlayer(player);
		verify(bar).setProgress(0.5);
	}

	@Test
	@DisplayName("holstering a HUD weapon removes the boss bar on the next tick")
	void tick_holsteringHudWeapon_removesBossBar() {
		HudData.BossBarData bossBarData = new HudData.BossBarData("&6Test", BarColor.YELLOW, BarStyle.SEGMENTED_10);
		HudData              hud        = new HudData(null, bossBarData, false);
		Weapon               weapon     = hudWeapon(hud, 15, 30);

		ItemStack item   = mock(ItemStack.class);
		Player    player = player(item);
		when(weaponService.validateAndGetWeapon(player, item)).thenReturn(weapon);

		BossBar bar = mock(BossBar.class);
		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
			bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(player));
			bukkit.when(() -> Bukkit.createBossBar(any(), any(), any())).thenReturn(bar);

			service.tick(); // creates the bar

			// holstered: the player no longer holds any (recognisable) weapon
			when(weaponService.validateAndGetWeapon(player, item)).thenReturn(null);

			service.tick();
		}

		verify(bar).removeAll();
	}

	@Test
	@DisplayName("switching between two HUD weapons refreshes the reused boss bar's colour and style")
	void tick_switchingHudWeapons_refreshesColorAndStyle() {
		HudData.BossBarData firstBarData  = new HudData.BossBarData("&6First", BarColor.YELLOW, BarStyle.SEGMENTED_10);
		HudData.BossBarData secondBarData = new HudData.BossBarData("&6Second", BarColor.RED, BarStyle.SOLID);
		Weapon              firstWeapon   = hudWeapon(new HudData(null, firstBarData, false), 15, 30);
		Weapon              secondWeapon  = hudWeapon(new HudData(null, secondBarData, false), 15, 30);

		ItemStack item   = mock(ItemStack.class);
		Player    player = player(item);

		BossBar bar = mock(BossBar.class);
		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
			bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(player));
			bukkit.when(() -> Bukkit.createBossBar(any(), any(), any())).thenReturn(bar);

			when(weaponService.validateAndGetWeapon(player, item)).thenReturn(firstWeapon);
			service.tick(); // creates the bar with the first weapon's colour/style

			when(weaponService.validateAndGetWeapon(player, item)).thenReturn(secondWeapon);
			service.tick(); // reuses the same bar - must pick up the second weapon's colour/style
		}

		verify(bar).setColor(BarColor.RED);
		verify(bar).setStyle(BarStyle.SOLID);
	}

	@Test
	@DisplayName("BZ-HU-02: a boss bar reused for a new Player instance under the same uuid (e.g. a fresh session "
			+ "after a rejoin) re-adds the current session's player, not just whichever session first created it")
	void tick_reusedBarUnderSameUuid_addsCurrentPlayerInstance() {
		HudData.BossBarData bossBarData = new HudData.BossBarData("&6Test", BarColor.YELLOW, BarStyle.SEGMENTED_10);
		HudData              hud        = new HudData(null, bossBarData, false);
		Weapon                weapon    = hudWeapon(hud, 15, 30);

		UUID      sharedId     = UUID.randomUUID();
		ItemStack item         = mock(ItemStack.class);
		Player    firstSession = player(item);
		when(firstSession.getUniqueId()).thenReturn(sharedId);
		when(weaponService.validateAndGetWeapon(firstSession, item)).thenReturn(weapon);

		BossBar bar = mock(BossBar.class);
		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
			bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(firstSession));
			bukkit.when(() -> Bukkit.createBossBar(any(), any(), any())).thenReturn(bar);

			service.tick(); // creates the bar, adds firstSession

			// A second Player instance under the SAME uuid - a fresh session object after a quit/rejoin, reusing
			// the bar that survived in HudService's map (see HudShotRefreshListener/BZ-HU-02 for how that happens).
			Player secondSession = player(item);
			when(secondSession.getUniqueId()).thenReturn(sharedId);
			when(weaponService.validateAndGetWeapon(secondSession, item)).thenReturn(weapon);

			bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(secondSession));
			service.tick(); // reuses the same bar object

			verify(bar).addPlayer(secondSession);
		}
	}

}
