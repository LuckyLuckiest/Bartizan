package org.luckyraven.bartizan.wearable;

import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.testsupport.BukkitRegistryFixture;
import org.luckyraven.bartizan.api.wearable.Wearable;

import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins {@code WearableService}'s damage math (weapons-roadmap.md gate {@code HL}, §4; reverted by gate {@code HL}
 * review, §2 back to the pre-{@code HL} per-slot formula): {@code applyWearableReduction} combines each piece's
 * own {@code Base_Damage_Reduction} + its OWN {@code reinforced}/{@code bulletproof} level + its enchantment bonus,
 * caps that at 90 % for the slot, and stacks slots multiplicatively; an active Set's own
 * {@code reinforced}/{@code bulletproof} bonus folds in afterwards as one further, separate "virtual slot"
 * discount (never merged into a worn piece's own level). Every other trait ({@code toughened}, {@code sealed},
 * {@code insulated}, {@code fire_resistant}) is instead summed body-wide via {@code resolveTraitLevels} (piece
 * levels + an active set bonus, capped per {@link Wearable#traitMaxLevel(String)}) by its own reader. Uses a
 * {@link FakeWearableService} subclass that resolves worn items by identity instead of real NBT —
 * {@code resolveWearable}'s own item→key resolution is already covered elsewhere ({@code Wearable} tests), this
 * class is only about the arithmetic once a {@link Wearable} is resolved.
 */
@DisplayName("WearableService")
class WearableServiceTest {

	@BeforeAll
	static void bootstrapBukkitRegistry() {
		BukkitRegistryFixture.install();
	}

	/** Resolves worn items by identity instead of real item NBT, so tests need no live {@code ItemBuilder}/PDC. */
	private static class FakeWearableService extends WearableService {

		private final Map<ItemStack, Wearable> byItem = new HashMap<>();

		void wear(EntityEquipment equipment, EquipmentSlot slot, Wearable wearable) {
			ItemStack item = mock(ItemStack.class);
			when(item.getType()).thenReturn(wearable.getMaterial());
			when(equipment.getItem(slot)).thenReturn(item);
			byItem.put(item, wearable);
		}

		@Override
		@Nullable
		public Wearable resolveWearable(@Nullable ItemStack item) {
			return byItem.get(item);
		}
	}

	private static LivingEntity targetWithEquipment(EntityEquipment equipment) {
		LivingEntity entity = mock(LivingEntity.class);
		when(entity.getEquipment()).thenReturn(equipment);
		return entity;
	}

	/**
	 * Real {@code EntityEquipment#getItem} never returns {@code null} (an empty slot is an AIR stack) — a bare
	 * {@code mock(EntityEquipment.class)} does return {@code null} for every unstubbed slot, which NPEs on
	 * {@code item.getType()} the moment a test only stubs the one or two slots it cares about. This pre-stubs all
	 * four armour slots to AIR; {@link FakeWearableService#wear} then overrides just the slots a test wears.
	 */
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

	private static Wearable wearable(double baseReduction, Map<String, Integer> traits) {
		return wearable(baseReduction, traits, null);
	}

	private static Wearable wearable(double baseReduction, Map<String, Integer> traits, @Nullable String set) {
		return Wearable.builder()
		               .material(Material.IRON_CHESTPLATE)
		               .wearableKey("test")
		               .baseDamageReduction(baseReduction)
		               .traits(traits)
		               .set(set)
		               .temporary(false)
		               .build();
	}

	@Test
	@DisplayName("a single piece's Base_Damage_Reduction reduces damage by that fraction")
	void applyWearableReduction_singlePieceBaseReduction() {
		FakeWearableService service   = new FakeWearableService();
		EntityEquipment     equipment = emptyEquipment();
		service.wear(equipment, EquipmentSlot.CHEST, wearable(0.10, Map.of()));

		double result = service.applyWearableReduction(100, targetWithEquipment(equipment), false);

		assertEquals(90.0, result, 1e-9);
	}

	@Test
	@DisplayName("two worn pieces' base reductions stack multiplicatively per slot")
	void applyWearableReduction_twoPieces_stackMultiplicatively() {
		FakeWearableService service   = new FakeWearableService();
		EntityEquipment     equipment = emptyEquipment();
		service.wear(equipment, EquipmentSlot.CHEST, wearable(0.10, Map.of()));
		service.wear(equipment, EquipmentSlot.HEAD, wearable(0.10, Map.of()));

		double result = service.applyWearableReduction(100, targetWithEquipment(equipment), false);

		assertEquals(81.0, result, 1e-9, "100 * 0.9 * 0.9");
	}

	@Test
	@DisplayName("reinforced applies per worn piece and stacks multiplicatively, not summed body-wide")
	void applyWearableReduction_reinforcedTrait_appliesPerPiece() {
		FakeWearableService service   = new FakeWearableService();
		EntityEquipment     equipment = emptyEquipment();
		// Two pieces each carrying reinforced 2 - each contributes its OWN 0.10 discount, stacked per slot.
		service.wear(equipment, EquipmentSlot.CHEST, wearable(0, Map.of("reinforced", 2)));
		service.wear(equipment, EquipmentSlot.HEAD, wearable(0, Map.of("reinforced", 2)));

		double result = service.applyWearableReduction(100, targetWithEquipment(equipment), false);

		// (1 - 0.10) * (1 - 0.10) * 100 = 81 - NOT summed to 4 * 0.05 = 0.20 applied once (== 80), which was the
		// gate HL regression this pins against (Opus review, §2).
		assertEquals(81.0, result, 1e-9);
	}

	@Test
	@DisplayName("bulletproof only contributes for projectile damage")
	void applyWearableReduction_bulletproof_onlyForProjectiles() {
		FakeWearableService service   = new FakeWearableService();
		EntityEquipment     equipment = emptyEquipment();
		service.wear(equipment, EquipmentSlot.CHEST, wearable(0, Map.of("bulletproof", 1)));

		double generic    = service.applyWearableReduction(100, targetWithEquipment(equipment), false);
		double projectile = service.applyWearableReduction(100, targetWithEquipment(equipment), true);

		assertEquals(100.0, generic, 1e-9, "bulletproof must not reduce non-projectile damage");
		assertEquals(96.0, projectile, 1e-9, "1 * 0.04 = 0.04 discount on the projectile path");
	}

	@Test
	@DisplayName("a single piece's generic reduction is bounded by the 80% getGenericDamageReduction cap, not 90%")
	void applyWearableReduction_singlePiece_genericCappedAtEightyPercent() {
		FakeWearableService service   = new FakeWearableService();
		EntityEquipment     equipment = emptyEquipment();
		// A misconfigured Base_Damage_Reduction above 1.0 clamps at getGenericDamageReduction()'s own 0.80
		// ceiling on the generic path - base+reinforced alone never reaches the slot's outer 0.90 cap.
		service.wear(equipment, EquipmentSlot.CHEST, wearable(0.99, Map.of()));

		double result = service.applyWearableReduction(100, targetWithEquipment(equipment), false);

		assertEquals(20.0, result, 1e-9, "0.80 inner cap: 100 * (1 - 0.80)");
	}

	@Test
	@DisplayName("the projectile path can still reach the slot's outer 90% cap once bulletproof stacks on top")
	void applyWearableReduction_singlePiece_projectileReachesNinetyPercentCap() {
		FakeWearableService service   = new FakeWearableService();
		EntityEquipment     equipment = emptyEquipment();
		service.wear(equipment, EquipmentSlot.CHEST, wearable(0.99, Map.of("bulletproof", 3)));

		double result = service.applyWearableReduction(100, targetWithEquipment(equipment), true);

		// getGenericDamageReduction() caps at 0.80; + bulletproof's max (3 * 0.04 = 0.12) = 0.92, capped by the
		// slot's outer 0.90: 100 * (1 - 0.90).
		assertEquals(10.0, result, 1e-9, "0.90 outer per-slot cap, reached via bulletproof on top of the 80%-capped base");
	}

	@Test
	@DisplayName("police_vest + police_helmet (wearables.yml values) against a projectile hit - the pre-HL numbers")
	void applyWearableReduction_policeVestAndHelmet_projectile_matchesPreHlNumbers() {
		FakeWearableService service   = new FakeWearableService();
		EntityEquipment     equipment = emptyEquipment();
		// wearables.yml: police_vest (Base_Damage_Reduction 0.10, Traits REINFORCED 2 / BULLETPROOF 1).
		service.wear(equipment, EquipmentSlot.CHEST, wearable(0.10, Map.of("reinforced", 2, "bulletproof", 1)));
		// wearables.yml: police_helmet (Base_Damage_Reduction 0.07, Traits REINFORCED 1 / TOUGHENED 1).
		service.wear(equipment, EquipmentSlot.HEAD, wearable(0.07, Map.of("reinforced", 1, "toughened", 1)));

		double result = service.applyWearableReduction(100, targetWithEquipment(equipment), true);

		// vest: min(0.10 + 2*0.05, 0.80) + 1*0.04 = 0.24; helmet: min(0.07 + 1*0.05, 0.80) = 0.12.
		// 100 * (1-0.24) * (1-0.12) = 66.88 - the Opus review's cited "+1.4% damage taken" nerf compared the
		// body-wide formula's 71.145 against this restored number.
		assertEquals(66.88, result, 1e-6);
	}

	@Test
	@DisplayName("the full tactical set (heavy_vest+tactical_helmet+tactical_leggings+tactical_boots) - the pre-HL numbers")
	void applyWearableReduction_fullTacticalSet_projectile_matchesPreHlNumbers() {
		FakeWearableService service   = new FakeWearableService();
		EntityEquipment     equipment = emptyEquipment();
		// wearables.yml: heavy_vest (0.15, REINFORCED 3 / BULLETPROOF 2 / TOUGHENED 1 / LIGHTWEIGHT 1).
		service.wear(equipment, EquipmentSlot.CHEST, wearable(0.15, Map.of("reinforced", 3, "bulletproof", 2)));
		// wearables.yml: tactical_helmet (0.10, REINFORCED 2 / BULLETPROOF 1 / TOUGHENED 1).
		service.wear(equipment, EquipmentSlot.HEAD, wearable(0.10, Map.of("reinforced", 2, "bulletproof", 1)));
		// wearables.yml: tactical_leggings (0.12, REINFORCED 2 / PADDED 1 / TOUGHENED 1).
		service.wear(equipment, EquipmentSlot.LEGS, wearable(0.12, Map.of("reinforced", 2)));
		// wearables.yml: tactical_boots (0.08, REINFORCED 1 / LIGHTWEIGHT 1).
		service.wear(equipment, EquipmentSlot.FEET, wearable(0.08, Map.of("reinforced", 1)));

		double result = service.applyWearableReduction(100, targetWithEquipment(equipment), true);

		// 100 * 0.62 * 0.76 * 0.78 * 0.87 = 31.975632 - the Opus review's cited "+31.7%" nerf compared the
		// body-wide formula's 42.115392 against this restored number.
		assertEquals(31.975632, result, 1e-6);
	}

	@Test
	@DisplayName("the full police set (police_vest+police_helmet+police_leggings+police_boots) - the pre-HL numbers")
	void applyWearableReduction_fullPoliceSet_projectile_matchesPreHlNumbers() {
		FakeWearableService service   = new FakeWearableService();
		EntityEquipment     equipment = emptyEquipment();
		service.wear(equipment, EquipmentSlot.CHEST, wearable(0.10, Map.of("reinforced", 2, "bulletproof", 1)));
		service.wear(equipment, EquipmentSlot.HEAD, wearable(0.07, Map.of("reinforced", 1, "toughened", 1)));
		// wearables.yml: police_leggings (0.08, REINFORCED 1 / TOUGHENED 1).
		service.wear(equipment, EquipmentSlot.LEGS, wearable(0.08, Map.of("reinforced", 1, "toughened", 1)));
		// wearables.yml: police_boots (0.05, REINFORCED 1 / LIGHTWEIGHT 1).
		service.wear(equipment, EquipmentSlot.FEET, wearable(0.05, Map.of("reinforced", 1)));

		double result = service.applyWearableReduction(100, targetWithEquipment(equipment), true);

		assertEquals(52.36704, result, 1e-6);
	}

	@Test
	@DisplayName("an active Set's own reinforced/bulletproof bonus folds in as one extra virtual slot - a set only adds")
	void applyWearableReduction_setBonus_foldsInAsVirtualSlot() {
		FakeWearableService service   = new FakeWearableService();
		EntityEquipment     equipment = emptyEquipment();
		// Two plain pieces (no base reduction, no own traits) worn as a 2-piece Set.
		service.wear(equipment, EquipmentSlot.CHEST, wearable(0, Map.of(), "test_set"));
		service.wear(equipment, EquipmentSlot.HEAD, wearable(0, Map.of(), "test_set"));

		NavigableMap<Integer, WearableService.SetTier> tiers = new TreeMap<>();
		// The Set bonus grants REINFORCED 2 (0.05/level) once 2 pieces are worn - NOT on either piece itself.
		tiers.put(2, new WearableService.SetTier(Map.of("reinforced", 2), java.util.List.of()));
		service.registerSet("test_set", tiers);

		double result = service.applyWearableReduction(100, targetWithEquipment(equipment), false);

		// Each piece contributes 0 on its own; the Set's own reinforced:2 = 0.10 discount applies once, as a
		// third "virtual" slot: 100 * (1 - 0.10) = 90.
		assertEquals(90.0, result, 1e-9);
	}

	@Test
	@DisplayName("with no reactive trait worn, reactive never nullifies the hit (deterministic: no roll happens)")
	void applyWearableReduction_noReactiveTrait_neverNullifies() {
		FakeWearableService service   = new FakeWearableService();
		EntityEquipment     equipment = emptyEquipment();
		service.wear(equipment, EquipmentSlot.CHEST, wearable(0, Map.of()));

		double result = service.applyWearableReduction(100, targetWithEquipment(equipment), false);

		assertEquals(100.0, result, 1e-9);
	}

	@Test
	@DisplayName("a set bonus's trait levels merge into the body-wide total (resolveTraitLevels/traitLevel)")
	void resolveTraitLevels_mergesActiveSetBonus() {
		FakeWearableService service   = new FakeWearableService();
		EntityEquipment     equipment = emptyEquipment();
		service.wear(equipment, EquipmentSlot.CHEST, wearable(0, Map.of("sealed", 1), "hazmat"));
		service.wear(equipment, EquipmentSlot.HEAD, wearable(0, Map.of("sealed", 1), "hazmat"));

		NavigableMap<Integer, WearableService.SetTier> tiers = new TreeMap<>();
		tiers.put(2, new WearableService.SetTier(Map.of("sealed", 1), java.util.List.of()));
		service.registerSet("hazmat", tiers);

		LivingEntity target = targetWithEquipment(equipment);

		// 1 (chest) + 1 (head) + 1 (Pieces_2 set bonus, 2 pieces worn) = 3, capped at sealed's own max of 3.
		assertEquals(3, service.traitLevel(target, "sealed"));
	}

	@Test
	@DisplayName("a set bonus below its Pieces_N threshold contributes nothing")
	void resolveTraitLevels_setBonusBelowThreshold_contributesNothing() {
		FakeWearableService service   = new FakeWearableService();
		EntityEquipment     equipment = emptyEquipment();
		service.wear(equipment, EquipmentSlot.CHEST, wearable(0, Map.of("sealed", 1), "hazmat"));

		NavigableMap<Integer, WearableService.SetTier> tiers = new TreeMap<>();
		tiers.put(2, new WearableService.SetTier(Map.of("sealed", 5), java.util.List.of()));
		service.registerSet("hazmat", tiers);

		LivingEntity target = targetWithEquipment(equipment);

		assertEquals(1, service.traitLevel(target, "sealed"), "only 1 piece worn - Pieces_2 not reached");
	}

	@Test
	@DisplayName("toughened reduces the critical-hit bonus body-wide")
	void reduceCritBonus_toughenedTrait() {
		FakeWearableService service   = new FakeWearableService();
		EntityEquipment     equipment = emptyEquipment();
		service.wear(equipment, EquipmentSlot.CHEST, wearable(0, Map.of("toughened", 2)));

		double result = service.reduceCritBonus(10.0, targetWithEquipment(equipment));

		assertEquals(8.0, result, 1e-9, "2 * 0.10 = 0.20 discount");
	}

	@Test
	@DisplayName("insulated reduces beam/energy damage body-wide")
	void applyInsulatedReduction_insulatedTrait() {
		FakeWearableService service   = new FakeWearableService();
		EntityEquipment     equipment = emptyEquipment();
		service.wear(equipment, EquipmentSlot.CHEST, wearable(0, Map.of("insulated", 2)));

		double result = service.applyInsulatedReduction(100, targetWithEquipment(equipment));

		assertEquals(84.0, result, 1e-9, "2 * 0.08 = 0.16 discount");
	}

	@Test
	@DisplayName("fire_resistant reduces fire ticks body-wide")
	void reduceFireTicks_fireResistantTrait() {
		FakeWearableService service   = new FakeWearableService();
		EntityEquipment     equipment = emptyEquipment();
		service.wear(equipment, EquipmentSlot.CHEST, wearable(0, Map.of("fire_resistant", 2)));

		int result = service.reduceFireTicks(100, targetWithEquipment(equipment));

		assertEquals(50, result, "2 * 0.25 = 0.50 discount");
	}

}
