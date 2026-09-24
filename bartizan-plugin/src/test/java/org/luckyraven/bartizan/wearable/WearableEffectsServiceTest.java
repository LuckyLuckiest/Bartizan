package org.luckyraven.bartizan.wearable;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.testsupport.BukkitRegistryFixture;
import org.luckyraven.bartizan.api.weapon.dto.EffectHook;
import org.luckyraven.bartizan.api.weapon.dto.EffectSpec;
import org.luckyraven.bartizan.api.weapon.dto.EffectsData;
import org.luckyraven.bartizan.api.wearable.Wearable;
import org.luckyraven.bartizan.effect.EffectContext;
import org.luckyraven.bartizan.effect.EffectRunner;
import org.mockito.MockedStatic;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers {@link WearableEffectsService} (weapons-roadmap.md gate {@code HL}, §5): {@link WearableEffectsService#tick()}
 * is driven directly, same shape as {@code HudServiceTest}/{@code StatusEffectServiceTest} — {@code Bukkit.
 * getOnlinePlayers} is statically mocked rather than standing up a real scheduler.
 */
@DisplayName("WearableEffectsService")
class WearableEffectsServiceTest {

	@BeforeAll
	static void bootstrapBukkitRegistry() {
		BukkitRegistryFixture.install();
	}

	/** Resolves worn items by identity instead of real item NBT — same seam {@code WearableServiceTest} uses. */
	private static class FakeWearableService extends WearableService {

		private final Map<ItemStack, Wearable> byItem = new HashMap<>();

		ItemStack wear(EntityEquipment equipment, EquipmentSlot slot, Wearable wearable) {
			ItemStack item = mock(ItemStack.class);
			when(item.getType()).thenReturn(wearable.getMaterial());
			when(equipment.getItem(slot)).thenReturn(item);
			byItem.put(item, wearable);
			return item;
		}

		@Override
		@Nullable
		public Wearable resolveWearable(@Nullable ItemStack item) {
			return byItem.get(item);
		}
	}

	private static EntityEquipment emptyEquipment() {
		EntityEquipment equipment = mock(EntityEquipment.class);
		for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
				EquipmentSlot.FEET}) {
			ItemStack air = mock(ItemStack.class);
			when(air.getType()).thenReturn(Material.AIR);
			when(equipment.getItem(slot)).thenReturn(air);
		}
		return equipment;
	}

	private static Player playerWithEquipment(EntityEquipment equipment) {
		Player player = mock(Player.class);
		when(player.getUniqueId()).thenReturn(UUID.randomUUID());
		when(player.getEquipment()).thenReturn(equipment);
		return player;
	}

	private static Wearable wearable(String key, List<String> effectsWhileWorn, EffectsData effects) {
		return Wearable.builder()
		               .material(Material.IRON_CHESTPLATE)
		               .wearableKey(key)
		               .name("&7" + key)
		               .traits(Map.of())
		               .effectsWhileWorn(effectsWhileWorn)
		               .effects(effects)
		               .temporary(false)
		               .build();
	}

	private static EffectsData effectsWithHook(EffectHook hook) {
		EffectsData data = EffectsData.empty();
		data.put(hook, List.of(new EffectSpec("sound", Map.of())));
		return data;
	}

	// tick()'s Effects_While_Worn path ultimately constructs a real org.bukkit.potion.PotionEffect, which needs a
	// live PotionEffectType from Bukkit's registry — unavailable in this plain unit test, and (confirmed by
	// BiologicalActionTest's own class javadoc) not fakeable either: even mock(PotionEffectType.class) crashes its
	// own <clinit>. shouldApplyWornEffects/isSkipSentinel are extracted out of WearableEffectsService specifically
	// so the cadence arithmetic and the skip-sentinel check are still pinned directly, without needing one.

	@Test
	@DisplayName("shouldApplyWornEffects is true every 4th tick (0, 4, 8, ...), false otherwise")
	void shouldApplyWornEffects_everyFourthTick() {
		assertEquals(true, WearableEffectsService.shouldApplyWornEffects(0));
		assertEquals(false, WearableEffectsService.shouldApplyWornEffects(1));
		assertEquals(false, WearableEffectsService.shouldApplyWornEffects(2));
		assertEquals(false, WearableEffectsService.shouldApplyWornEffects(3));
		assertEquals(true, WearableEffectsService.shouldApplyWornEffects(4));
		assertEquals(true, WearableEffectsService.shouldApplyWornEffects(8));
	}

	@Test
	@DisplayName("isSkipSentinel is true only for a raw amplifier of exactly -1")
	void isSkipSentinel_onlyRawAmplifierNegativeOne() {
		assertEquals(true, WearableEffectsService.isSkipSentinel("SLOWNESS-60--1"));
		assertEquals(false, WearableEffectsService.isSkipSentinel("SLOWNESS-60-1"));
		assertEquals(false, WearableEffectsService.isSkipSentinel("SLOWNESS-60-0"));
		assertEquals(false, WearableEffectsService.isSkipSentinel("REGENERATION--1-0"), "duration, not amplifier, "
				+ "is the negative field here - must not be mistaken for the skip sentinel");
		assertEquals(false, WearableEffectsService.isSkipSentinel("not a token at all"));
	}

	@Test
	@DisplayName("first seeing a worn wearable fires its On_Equip hook")
	void tick_newlyWornWearable_firesOnEquip() {
		FakeWearableService    service        = new FakeWearableService();
		EffectRunner           effectRunner   = mock(EffectRunner.class);
		WearableEffectsService effectsService = new WearableEffectsService(mock(JavaPlugin.class), service,
		                                                                  effectRunner);

		EffectsData     effects   = effectsWithHook(EffectHook.ON_EQUIP);
		EntityEquipment equipment = emptyEquipment();
		Wearable        vest      = wearable("vest", List.of(), effects);
		service.wear(equipment, EquipmentSlot.CHEST, vest);
		service.register("vest", vest);
		Player player = playerWithEquipment(equipment);

		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
			bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(player));
			effectsService.tick();
		}

		verify(effectRunner).run(eq(effects), eq("&7vest"), eq(EffectHook.ON_EQUIP), any(EffectContext.class));
	}

	@Test
	@DisplayName("no longer seeing a previously-worn wearable fires its On_Unequip hook")
	void tick_removedWearable_firesOnUnequip() {
		FakeWearableService    service        = new FakeWearableService();
		EffectRunner           effectRunner   = mock(EffectRunner.class);
		WearableEffectsService effectsService = new WearableEffectsService(mock(JavaPlugin.class), service,
		                                                                  effectRunner);

		EffectsData     effects   = effectsWithHook(EffectHook.ON_UNEQUIP);
		EntityEquipment equipment = emptyEquipment();
		Wearable        vest      = wearable("vest", List.of(), effects);
		service.wear(equipment, EquipmentSlot.CHEST, vest);
		service.register("vest", vest);
		Player player = playerWithEquipment(equipment);

		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
			bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(player));
			effectsService.tick(); // equips

			ItemStack air = mock(ItemStack.class);
			when(air.getType()).thenReturn(Material.AIR);
			when(equipment.getItem(EquipmentSlot.CHEST)).thenReturn(air);

			effectsService.tick(); // unequips
		}

		verify(effectRunner).run(eq(effects), eq("&7vest"), eq(EffectHook.ON_UNEQUIP), any(EffectContext.class));
	}

	@Test
	@DisplayName("remove() drops the worn-key snapshot so a rejoin diffs against nothing")
	void remove_dropsSnapshot() {
		FakeWearableService    service        = new FakeWearableService();
		EffectRunner           effectRunner   = mock(EffectRunner.class);
		WearableEffectsService effectsService = new WearableEffectsService(mock(JavaPlugin.class), service,
		                                                                  effectRunner);

		EffectsData     effects   = effectsWithHook(EffectHook.ON_EQUIP);
		EntityEquipment equipment = emptyEquipment();
		Wearable        vest      = wearable("vest", List.of(), effects);
		service.wear(equipment, EquipmentSlot.CHEST, vest);
		service.register("vest", vest);

		Player player = playerWithEquipment(equipment);
		UUID   id      = player.getUniqueId();

		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
			bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(player));
			effectsService.tick(); // equips once

			effectsService.remove(id);

			effectsService.tick(); // must equip again - not a no-op re-seen key
		}

		verify(effectRunner, times(2)).run(eq(effects), eq("&7vest"), eq(EffectHook.ON_EQUIP), any(EffectContext.class));
	}

	@Test
	@DisplayName("a tick that throws for one player doesn't stop the rest")
	void tick_onePlayerThrows_othersStillTick() {
		FakeWearableService    service        = new FakeWearableService();
		EffectRunner           effectRunner   = mock(EffectRunner.class);
		WearableEffectsService effectsService = new WearableEffectsService(mock(JavaPlugin.class), service,
		                                                                  effectRunner);

		Player broken = mock(Player.class);
		when(broken.getUniqueId()).thenReturn(UUID.randomUUID());
		when(broken.getEquipment()).thenThrow(new RuntimeException("boom"));

		EffectsData     effects   = effectsWithHook(EffectHook.ON_EQUIP);
		EntityEquipment equipment = emptyEquipment();
		Wearable        vest      = wearable("vest", List.of(), effects);
		service.wear(equipment, EquipmentSlot.CHEST, vest);
		service.register("vest", vest);
		Player fine = playerWithEquipment(equipment);

		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
			bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(broken, fine));
			effectsService.tick();
		}

		verify(effectRunner).run(eq(effects), eq("&7vest"), eq(EffectHook.ON_EQUIP), any(EffectContext.class));
	}

	@Test
	@DisplayName("BZ-WE-10: a dropped grant's currently active effect is 'still the worn grant' only when amplifier "
			+ "matches and duration is still inside the re-apply window")
	void isStillTheWornGrant_matchesAmplifierAndDurationWindow() {
		// The wearable's own instance, freshly re-applied or partway through its 220-tick window: removable.
		assertEquals(true, WearableEffectsService.isStillTheWornGrant(1, 220, 1));
		assertEquals(true, WearableEffectsService.isStillTheWornGrant(1, 1, 1));

		// A stronger effect of the same type from an unrelated source (BZ-WE-10's own scenario: hazmat_chest
		// grants SLOWNESS 1, a splash potion or BiologicalAction hit lands SLOWNESS 2 on top) - must be left alone.
		assertEquals(false, WearableEffectsService.isStillTheWornGrant(2, 220, 1));

		// A much longer (or effectively infinite) effect of the SAME amplifier from another source outlives the
		// worn re-apply window on its own, so the duration bound alone tells them apart without needing
		// PotionEffect.isInfinite() (unavailable on the 1.16.5 compile floor).
		assertEquals(false, WearableEffectsService.isStillTheWornGrant(1, 1200, 1));
	}

	@Test
	@DisplayName("an amplifier of -1 skips that Effects_While_Worn token entirely")
	void tick_negativeOneAmplifier_skipsToken() {
		FakeWearableService    service        = new FakeWearableService();
		EffectRunner           effectRunner   = mock(EffectRunner.class);
		WearableEffectsService effectsService = new WearableEffectsService(mock(JavaPlugin.class), service,
		                                                                  effectRunner);

		EntityEquipment equipment = emptyEquipment();
		service.wear(equipment, EquipmentSlot.CHEST,
		            wearable("vest", List.of("SLOWNESS-60--1"), EffectsData.empty()));
		Player player = playerWithEquipment(equipment);

		try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
			bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(player));
			effectsService.tick();
		}

		verify(player, never()).addPotionEffect(any());
	}

}
