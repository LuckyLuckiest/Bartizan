package org.luckyraven.bartizan.api.weapon.reload;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.ammo.Ammunition;
import org.luckyraven.bartizan.api.event.WeaponReloadCompleteEvent;
import org.luckyraven.bartizan.api.support.WeaponFixtures;
import org.luckyraven.bartizan.api.weapon.MeleeWeapon;
import org.luckyraven.bartizan.api.weapon.WeaponType;
import org.luckyraven.bartizan.api.weapon.dto.AmmunitionData;
import org.luckyraven.bartizan.api.weapon.dto.MeleeData;
import org.luckyraven.bartizan.api.weapon.dto.ReloadData;
import org.luckyraven.bartizan.api.weapon.dto.ReloadStagesData;
import org.luckyraven.bartizan.api.weapon.dto.SoundData;
import org.luckyraven.keystone.sound.SoundEffect;
import org.luckyraven.keystone.timer.SequenceTimer;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers weapons-roadmap.md gate {@code HO}'s remaining required tests: resume-window maths (with the elapsed
 * time passed in explicitly rather than slept through), the ammo-safety property that an interrupt only ever
 * keeps rounds already committed at the {@code insert} stage, and that a numbered reload's resume skips
 * {@code open} and continues with the next shell. Driven directly through {@link Reload}/{@link InstantReload}/
 * {@link NumberedReload}'s protected stage-tracking methods, the same way {@link ReloadGuardAndShootLockTest} and
 * {@link ReloadAmmoTypeTest} exercise the base class without a real {@code SequenceTimer}.
 */
@DisplayName("Reload — staged reload / Phase 2 resume (gate HO)")
class ReloadStageResumeTest {

	// --- Resume-window maths -------------------------------------------------------------------------------------

	@Test
	@DisplayName("withinResumeWindow: still inside the window")
	void withinResumeWindow_insideWindow_true() {
		// 60 ticks = 3000ms; interrupted at t=1000, now t=3000 -> 2000ms elapsed, inside the window.
		assertTrue(Reload.withinResumeWindow(1000L, 60, 3000L));
	}

	@Test
	@DisplayName("withinResumeWindow: the window has elapsed")
	void withinResumeWindow_pastWindow_false() {
		// 60 ticks = 3000ms; interrupted at t=1000, now t=5000 -> 4000ms elapsed, past the window.
		assertFalse(Reload.withinResumeWindow(1000L, 60, 5000L));
	}

	@Test
	@DisplayName("withinResumeWindow: Resume_Window: 0 never resumes, however little time has passed")
	void withinResumeWindow_zeroWindow_false() {
		assertFalse(Reload.withinResumeWindow(1000L, 0, 1000L));
	}

	@Test
	@DisplayName("withinResumeWindow: no pending interrupt (<= 0) never resumes")
	void withinResumeWindow_noInterrupt_false() {
		assertFalse(Reload.withinResumeWindow(0L, 60, 1000L));
	}

	// --- Ammo safety: InstantReload -------------------------------------------------------------------------------

	@Test
	@DisplayName("interrupt before the insert commit: consumes nothing, and resume still runs the insert stage")
	void instant_interruptBeforeCommit_consumesNothingAndResumeRepeatsInsert() {
		MeleeWeapon   weapon = weaponWith(instantReloadData());
		InstantReload reload = new InstantReload(weapon, weapon.getAmmunitionData().getAmmoType());
		Player        player = mock(Player.class);

		weapon.setCurrentMagCapacity(0);

		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
			bukkit.when(Bukkit::getPluginManager).thenReturn(mock(PluginManager.class));

			// interrupted mid-"insert", before its commit callback ever ran.
			reload.enterStage(1, 3);
			reload.endReloading(player, true);
		}

		assertEquals(0, weapon.getCurrentMagCapacity(), "nothing was consumed before the commit");
		assertTrue(reload.canResume());
		assertTrue(reload.resumeStageIndex() <= 1, "resume must still run the insert stage, not skip its commit");
	}

	@Test
	@DisplayName("interrupt after the insert commit: keeps the rounds, and resume skips straight to close")
	void instant_interruptAfterCommit_keepsRoundsAndResumeSkipsToClose() {
		MeleeWeapon   weapon = weaponWith(instantReloadData());
		InstantReload reload = new InstantReload(weapon, weapon.getAmmunitionData().getAmmoType());
		Player        player = mock(Player.class);

		weapon.setCurrentMagCapacity(0);

		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
			bukkit.when(Bukkit::getPluginManager).thenReturn(mock(PluginManager.class));

			// the insert callback's own commit action, exactly as InstantReload#executeReload runs it, then the
			// transition into "close" the same callback makes right after.
			weapon.addAmmunition(weapon.getAmmunitionData().getRestore());
			reload.enterStage(2, 3);
			// interrupted mid-"close", after the commit but before endReloading ever ran.
			reload.endReloading(player, true);
		}

		assertEquals(6, weapon.getCurrentMagCapacity(), "the committed rounds are not refunded or discarded");
		assertTrue(reload.canResume());
		assertEquals(2, reload.resumeStageIndex(), "resume must skip open+insert - the commit already happened");
	}

	@Test
	@DisplayName("interrupt before any stage was ever entered offers no resume")
	void instant_interruptBeforeAnyStage_neverResumes() {
		MeleeWeapon   weapon = weaponWith(instantReloadData());
		InstantReload reload = new InstantReload(weapon, weapon.getAmmunitionData().getAmmoType());
		Player        player = mock(Player.class);

		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
			bukkit.when(Bukkit::getPluginManager).thenReturn(mock(PluginManager.class));

			reload.endReloading(player, true);
		}

		assertFalse(reload.canResume());
	}

	@Test
	@DisplayName("Resume_Window: 0 keeps restarting from zero even right after an interrupt")
	void instant_zeroResumeWindow_neverResumes() {
		ReloadData reloadData = ReloadData.builder().cooldown(4).type(ReloadType.getType("instant"))
		                                  .stages(ReloadStagesData.of(0, 0.25, 0.6, 0.15)).build();
		MeleeWeapon   weapon = weaponWith(reloadData);
		InstantReload reload = new InstantReload(weapon, weapon.getAmmunitionData().getAmmoType());
		Player        player = mock(Player.class);

		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
			bukkit.when(Bukkit::getPluginManager).thenReturn(mock(PluginManager.class));

			reload.enterStage(2, 3);
			reload.endReloading(player, true);
		}

		assertFalse(reload.canResume());
	}

	// --- Cascade guard / resume-into-close (gate HO review fixes 1 & 2) --------------------------------------------
	//
	// InstantReload#executeReload drives a real SequenceTimer, and SequenceTimer#run() keeps cascading through
	// queued interval-0 pairs within the same tick (Timer#stop() only cancels the Bukkit task - it doesn't break
	// out of a run() already in progress). Reproducing that cascade against the real Ammunition#buildItem() would
	// need a working Bukkit ItemFactory/ItemMeta (none of this suite's other tests attempt that - they all drive
	// Reload's protected stage-tracking methods directly instead), so these two tests capture the real callbacks
	// InstantReload registers on a MOCKED SequenceTimer (via Mockito's mockConstruction) and invoke them in the
	// exact order the real cascade would, while stubbing Ammunition/PlayerInventory so the abort path is reached
	// without ever building a real ItemStack.

	@Test
	@DisplayName("insert-stage abort (missing ammo) cascades into the same-tick close callback: exactly one "
	             + "interrupted end, not a phantom second successful one")
	void instant_abortInInsert_cascadedCloseCallback_endsExactlyOnceInterrupted() {
		Ammunition mockAmmo = mock(Ammunition.class);
		when(mockAmmo.buildItem(any(), anyInt())).thenReturn(mock(ItemStack.class));

		AmmunitionData ammunitionData = new AmmunitionData(mockAmmo, 6, 1, 6);
		ReloadData     reloadData     = ReloadData.builder().cooldown(4).type(ReloadType.getType("instant")).build();
		MeleeWeapon    weapon         = weaponWith(reloadData, ammunitionData);
		weapon.setSoundData(new SoundData());

		InstantReload   reload    = new InstantReload(weapon, mockAmmo);
		JavaPlugin      plugin    = mock(JavaPlugin.class);
		Player          player    = mock(Player.class);
		PlayerInventory inventory = mock(PlayerInventory.class);
		when(player.getInventory()).thenReturn(inventory);
		// containsAtLeast defaults to false (Mockito) - simulates the magazine item having gone missing mid-reload.

		List<Consumer<SequenceTimer>> callbacks   = new ArrayList<>();
		List<Event>                   firedEvents = new ArrayList<>();

		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
		     MockedStatic<SoundEffect> soundEffect = mockStatic(SoundEffect.class);
		     MockedConstruction<SequenceTimer> timerCtor = mockConstruction(SequenceTimer.class,
				     (timerMock, context) -> doAnswer(invocation -> {
					     callbacks.add(invocation.getArgument(1));
					     return null;
				     }).when(timerMock).addIntervalTaskPair(anyLong(), any()))) {

			PluginManager pluginManager = mock(PluginManager.class);
			doAnswer(invocation -> {
				firedEvents.add(invocation.getArgument(0));
				return null;
			}).when(pluginManager).callEvent(any());
			bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);

			reload.reload(plugin, player, true);

			assertEquals(4, callbacks.size(), "a fresh reload registers anchor + open + insert + close");

			SequenceTimer timerMock = timerCtor.constructed().get(0);
			callbacks.get(0).accept(timerMock); // anchor: startReloading + enterStage(open)
			callbacks.get(1).accept(timerMock); // open -> insert transition
			callbacks.get(2).accept(timerMock); // insert: missing ammo -> stopReloading(); abort
			callbacks.get(3).accept(timerMock); // cascaded close callback, same tick as the abort
		}

		List<WeaponReloadCompleteEvent> completions = firedEvents.stream()
				.filter(WeaponReloadCompleteEvent.class::isInstance)
				.map(WeaponReloadCompleteEvent.class::cast)
				.toList();

		assertEquals(1, completions.size(), "exactly one WeaponReloadCompleteEvent, not a phantom second "
		             + "successful one from the cascaded close callback");
		assertTrue(completions.get(0).isInterrupted());
		assertFalse(reload.isReloading());
	}

	@Test
	@DisplayName("resume recorded at/past the close stage starts a fresh reload from open, consuming the "
	             + "magazine exactly once")
	void instant_resumeAtCloseStage_startsFreshReloadFromOpen_consumesMagazineOnce() {
		Ammunition mockAmmo     = mock(Ammunition.class);
		ItemStack  magazineItem = mock(ItemStack.class);
		when(mockAmmo.buildItem(any(), anyInt())).thenReturn(magazineItem);

		AmmunitionData ammunitionData = new AmmunitionData(mockAmmo, 6, 1, 6);
		ReloadData     reloadData     = ReloadData.builder().cooldown(4).type(ReloadType.getType("instant"))
		                                          .unloadAmmoOnReload(true).build();
		MeleeWeapon    weapon         = weaponWith(reloadData, ammunitionData);
		weapon.setSoundData(new SoundData());

		InstantReload   reload    = new InstantReload(weapon, mockAmmo);
		JavaPlugin      plugin    = mock(JavaPlugin.class);
		Player          player    = mock(Player.class);
		PlayerInventory inventory = mock(PlayerInventory.class);
		when(player.getInventory()).thenReturn(inventory);
		when(inventory.containsAtLeast(any(), anyInt())).thenReturn(true);
		when(inventory.getContents()).thenReturn(new ItemStack[0]);

		weapon.setCurrentMagCapacity(0);

		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
			bukkit.when(Bukkit::getPluginManager).thenReturn(mock(PluginManager.class));

			// the original attempt's insert callback already committed (its own commit action, exactly as
			// InstantReload#executeReload runs it), then got interrupted mid-"close", waiting to finish.
			weapon.addAmmunition(ammunitionData.getRestore());
			reload.enterStage(2, 3);
			reload.endReloading(player, true);
		}
		assertTrue(reload.canResume());
		assertEquals(2, reload.resumeStageIndex());

		List<Consumer<SequenceTimer>> callbacks = new ArrayList<>();

		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
		     MockedStatic<SoundEffect> soundEffect = mockStatic(SoundEffect.class);
		     MockedConstruction<SequenceTimer> timerCtor = mockConstruction(SequenceTimer.class,
				     (timerMock, context) -> doAnswer(invocation -> {
					     callbacks.add(invocation.getArgument(1));
					     return null;
				     }).when(timerMock).addIntervalTaskPair(anyLong(), any()))) {

			bukkit.when(Bukkit::getPluginManager).thenReturn(mock(PluginManager.class));

			// reloading again, within the resume window.
			reload.reload(plugin, player, true);

			assertEquals(4, callbacks.size(), "a stage-2 (close) resume must restart the full open/insert/close "
			             + "sequence, not just the bare close wait that restores nothing");

			SequenceTimer timerMock = timerCtor.constructed().get(0);
			callbacks.get(0).accept(timerMock); // anchor
			callbacks.get(1).accept(timerMock); // open
			callbacks.get(2).accept(timerMock); // insert - the only place the magazine is consumed
			callbacks.get(3).accept(timerMock); // close - completes
		}

		verify(inventory, times(1)).removeItem(magazineItem);
		assertEquals(6, weapon.getCurrentMagCapacity(), "Unload_Ammo_On_Reload zeroed the magazine expecting "
		             + "the fresh restart's insert stage to re-fill it, not leave the gun empty");
		assertFalse(reload.isReloading());
	}

	// --- NumberedReload: resume skips open, continues with the next shell ------------------------------------------

	@Test
	@DisplayName("stageCountFor: open + one insert per shell + close")
	void numbered_stageCountFor_derivesFromShellCount() {
		assertEquals(7, NumberedReload.stageCountFor(5));
		assertEquals(2, NumberedReload.stageCountFor(0));
	}

	@Test
	@DisplayName("resume after shell 1 committed: skips open and continues with shell 2")
	void numbered_resumeAfterFirstShell_skipsOpenContinuesWithNextShell() {
		MeleeWeapon    weapon = weaponWith(instantReloadData());
		NumberedReload reload = new NumberedReload(weapon, weapon.getAmmunitionData().getAmmoType(), 1);
		Player         player = mock(Player.class);

		int stageCount = NumberedReload.stageCountFor(6);

		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
			bukkit.when(Bukkit::getPluginManager).thenReturn(mock(PluginManager.class));

			// shell 1 (stage index 1) committed, then the callback moves current on to stage 2 (waiting on shell 2)
			// exactly as NumberedReload#executeReload's per-shell loop does.
			reload.enterStage(2, stageCount);
			reload.endReloading(player, true);
		}

		assertTrue(reload.canResume());
		assertEquals(2, reload.resumeStageIndex());
		assertEquals(1, NumberedReload.previouslyCommittedFor(reload.resumeStageIndex()),
		            "exactly shell 1 had committed - resume continues with shell 2, not a full restart");
	}

	@Test
	@DisplayName("interrupt before shell 1 ever committed: nothing to skip, full restart")
	void numbered_interruptBeforeFirstShell_fullRestart() {
		MeleeWeapon    weapon = weaponWith(instantReloadData());
		NumberedReload reload = new NumberedReload(weapon, weapon.getAmmunitionData().getAmmoType(), 1);
		Player         player = mock(Player.class);

		int stageCount = NumberedReload.stageCountFor(6);

		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
			bukkit.when(Bukkit::getPluginManager).thenReturn(mock(PluginManager.class));

			// still waiting on shell 1 when interrupted.
			reload.enterStage(1, stageCount);
			reload.endReloading(player, true);
		}

		assertEquals(1, reload.resumeStageIndex());
		assertEquals(0, NumberedReload.previouslyCommittedFor(reload.resumeStageIndex()));
	}

	// ---------------------------------------------------------------------------------------------------------------

	private static ReloadData instantReloadData() {
		return WeaponFixtures.instantReload();
	}

	private static MeleeWeapon weaponWith(ReloadData reloadData) {
		Ammunition     ammo           = WeaponFixtures.ammo("test_ammo");
		AmmunitionData ammunitionData = new AmmunitionData(ammo, 6, 1, 6);
		return weaponWith(reloadData, ammunitionData);
	}

	private static MeleeWeapon weaponWith(ReloadData reloadData, AmmunitionData ammunitionData) {
		MeleeData melee = new MeleeData(8.0, 3.0, 10, 0.5);
		return new MeleeWeapon(UUID.randomUUID(), "test_knife", "&fTest Knife", WeaponType.MELEE, Material.IRON_HOE,
		                       0, (short) 50, List.of(), false, null, melee, reloadData, ammunitionData);
	}

}
