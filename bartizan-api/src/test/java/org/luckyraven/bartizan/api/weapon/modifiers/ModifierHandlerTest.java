package org.luckyraven.bartizan.api.weapon.modifiers;

import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.testsupport.BukkitRegistryFixture;
import org.luckyraven.bartizan.api.weapon.dto.ModifiersData;
import org.luckyraven.bartizan.api.weapon.modifiers.action.ArmorPiercingModifier;
import org.luckyraven.bartizan.api.weapon.modifiers.action.FlatDamageModifier;
import org.luckyraven.bartizan.api.weapon.modifiers.action.PenetrationModifier;
import org.luckyraven.bartizan.api.weapon.ProjectileState;
import org.luckyraven.bartizan.api.support.WeaponFixtures;
import org.luckyraven.bartizan.api.weapon.GunWeapon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pure-math coverage for {@link ModifierHandler} (weapons.md W17 — Modifiers: penetration, ricochet, block break,
 * tracer, AP, flat damage). No live Bukkit server is needed: {@code LivingEntity}/{@code AttributeInstance}/
 * {@code Block} are wide Bukkit interfaces mocked with Mockito per {@code TESTING.md} §6, and {@code Material}/
 * {@code Attribute} are plain enums resolvable with only the API jar on the classpath.
 */
@DisplayName("ModifierHandler — armor piercing, flat damage, penetration gating")
class ModifierHandlerTest {

	@BeforeAll
	static void bootstrapBukkitRegistry() {
		// Subject code reaches Material.isAir() / an XSeries registry lookup — see the fixture javadoc.
		BukkitRegistryFixture.install();
	}

	@Test
	@DisplayName("calculateArmorPiercingDamage returns baseDamage unchanged when no ArmorPiercing modifier is configured")
	void calculateArmorPiercingDamage_noModifier_returnsBaseDamage() {
		GunWeapon weapon = WeaponFixtures.gunWeapon(30, 1);
		weapon.setModifiersData(new ModifiersData());
		LivingEntity target = mock(LivingEntity.class);

		double result = ModifierHandler.calculateArmorPiercingDamage(20.0, target, weapon);

		assertEquals(20.0, result);
	}

	@Test
	@DisplayName("calculateArmorPiercingDamage: full diamond (armor 20, toughness 8), bypass 0.5 — after "
			+ "Minecraft's REAL (toughness-aware) armor formula re-applies, the target lands the intended "
			+ "piercing-adjusted damage (BZ-RT-16: a flat-model division overcorrected here to ~48, not ~16)")
	void calculateArmorPiercingDamage_diamondArmorWithToughness_landsIntendedDamage() {
		GunWeapon weapon = WeaponFixtures.gunWeapon(30, 1);
		ModifiersData modifiers = new ModifiersData();
		modifiers.setArmorPiercing(new ArmorPiercingModifier(0.5)); // bypasses half the target's armor
		weapon.setModifiersData(modifiers);

		LivingEntity target = mockTarget(20.0, 8.0);

		double returnedToVanilla = ModifierHandler.calculateArmorPiercingDamage(20.0, target, weapon);
		double actualDamageDealt = vanillaDamageAfterArmor(returnedToVanilla, 20.0, 8.0);

		// Intended: baseDamage=20, effectiveArmor=10, toughness=8 -> vanilla(20, 10, 8) = 16.0.
		assertEquals(16.0, actualDamageDealt, 0.01,
				"the flat-model division formula landed ~48 here, roughly 3x the intended amount");
	}

	@Test
	@DisplayName("calculateArmorPiercingDamage: iron armor (armor 15, toughness 0), bypass 0.5 — real formula "
			+ "round-trips to the intended damage the same way at low toughness (BZ-RT-16)")
	void calculateArmorPiercingDamage_ironArmorNoToughness_landsIntendedDamage() {
		GunWeapon weapon = WeaponFixtures.gunWeapon(30, 1);
		ModifiersData modifiers = new ModifiersData();
		modifiers.setArmorPiercing(new ArmorPiercingModifier(0.5)); // bypasses half the target's armor
		weapon.setModifiersData(modifiers);

		LivingEntity target = mockTarget(15.0, 0.0);

		double returnedToVanilla = ModifierHandler.calculateArmorPiercingDamage(10.0, target, weapon);
		double actualDamageDealt = vanillaDamageAfterArmor(returnedToVanilla, 15.0, 0.0);

		// Intended: baseDamage=10, effectiveArmor=7.5, toughness=0 -> vanilla(10, 7.5, 0) = 9.0.
		assertEquals(9.0, actualDamageDealt, 0.01);
	}

	/** Mocks a target whose ARMOR and ARMOR_TOUGHNESS attribute instances resolve independently. */
	private static LivingEntity mockTarget(double armor, double toughness) {
		LivingEntity target = mock(LivingEntity.class);

		AttributeInstance armorInstance = mock(AttributeInstance.class);
		when(armorInstance.getValue()).thenReturn(armor);
		when(target.getAttribute(Attribute.GENERIC_ARMOR)).thenReturn(armorInstance);

		AttributeInstance toughnessInstance = mock(AttributeInstance.class);
		when(toughnessInstance.getValue()).thenReturn(toughness);
		when(target.getAttribute(Attribute.GENERIC_ARMOR_TOUGHNESS)).thenReturn(toughnessInstance);

		return target;
	}

	/**
	 * Minecraft's real post-1.9 armor formula, independently re-implemented here (not a call into production
	 * code) so this test actually pins vanilla behaviour rather than re-asserting whatever
	 * {@code ModifierHandler} computes internally.
	 */
	private static double vanillaDamageAfterArmor(double damage, double armor, double toughness) {
		double f = 2.0 + toughness / 4.0;
		double g = Math.max(armor * 0.2, Math.min(armor - damage / f, 20.0));
		return damage * (1.0 - g / 25.0);
	}

	@Test
	@DisplayName("calculateArmorPiercingDamage returns baseDamage when the target has no armor attribute instance")
	void calculateArmorPiercingDamage_noAttributeInstance_returnsBaseDamage() {
		GunWeapon weapon = WeaponFixtures.gunWeapon(30, 1);
		ModifiersData modifiers = new ModifiersData();
		modifiers.setArmorPiercing(new ArmorPiercingModifier(0.5));
		weapon.setModifiersData(modifiers);

		LivingEntity target = mock(LivingEntity.class); // getAttribute(...) defaults to null
		double result = ModifierHandler.calculateArmorPiercingDamage(20.0, target, weapon);

		assertEquals(20.0, result);
	}

	@Test
	@DisplayName("applyFlatDamage adds the configured bonus, or leaves damage unchanged when absent")
	void applyFlatDamage_addsBonusOrPassesThrough() {
		GunWeapon weapon = WeaponFixtures.gunWeapon(30, 1);

		ModifiersData withoutFlat = new ModifiersData();
		weapon.setModifiersData(withoutFlat);
		assertEquals(10.0, ModifierHandler.applyFlatDamage(10.0, weapon));

		ModifiersData withFlat = new ModifiersData();
		withFlat.setFlatDamage(new FlatDamageModifier(2.5));
		weapon.setModifiersData(withFlat);
		assertEquals(12.5, ModifierHandler.applyFlatDamage(10.0, weapon));
	}

	@Test
	@DisplayName("handleEntityPenetration increments the counter, applies the reduction, and reports whether budget remains")
	void handleEntityPenetration_incrementsAndReports() {
		GunWeapon weapon = WeaponFixtures.gunWeapon(30, 1);
		ModifiersData modifiers = new ModifiersData();
		modifiers.setPenetration(new PenetrationModifier(0, 2, 0.25));
		weapon.setModifiersData(modifiers);

		ProjectileState state = new ProjectileState(weapon, 100.0);

		assertTrue(ModifierHandler.handleEntityPenetration(state));
		assertEquals(1, state.getEntitiesPenetrated());
		assertEquals(75.0, state.getCurrentDamage(), 0.0001);

		// Second penetration reaches the entity budget (2) and there is no block budget (0), so the ray must stop.
		assertFalse(ModifierHandler.handleEntityPenetration(state));
		assertEquals(2, state.getEntitiesPenetrated());
	}

	@Test
	@DisplayName("handleEntityPenetration returns false immediately once canPenetrateEntity is already exhausted")
	void handleEntityPenetration_alreadyExhausted_returnsFalseWithoutMutating() {
		GunWeapon weapon = WeaponFixtures.gunWeapon(30, 1);
		ModifiersData modifiers = new ModifiersData();
		modifiers.setPenetration(new PenetrationModifier(0, 1, 0.25));
		weapon.setModifiersData(modifiers);

		ProjectileState state = new ProjectileState(weapon, 100.0);
		state.setEntitiesPenetrated(1); // already at budget

		assertFalse(ModifierHandler.handleEntityPenetration(state));
		assertEquals(1, state.getEntitiesPenetrated(), "an exhausted budget must not increment further");
	}

	@Test
	@DisplayName("handleBlockPenetration refuses non-penetrable materials even with budget remaining")
	void handleBlockPenetration_nonPenetrableMaterial_refuses() {
		GunWeapon weapon = WeaponFixtures.gunWeapon(30, 1);
		ModifiersData modifiers = new ModifiersData();
		modifiers.setPenetration(new PenetrationModifier(3, 0, 0.1));
		weapon.setModifiersData(modifiers);

		ProjectileState state = new ProjectileState(weapon, 100.0);
		Block solidStone = mock(Block.class);
		when(solidStone.getType()).thenReturn(Material.STONE);

		assertFalse(ModifierHandler.handleBlockPenetration(state, solidStone));
		assertEquals(0, state.getBlocksPenetrated());
	}

	@Test
	@DisplayName("handleBlockPenetration accepts glass/pane/leaves/fence-style thin blocks and applies the reduction")
	void handleBlockPenetration_penetrableMaterial_accepts() {
		GunWeapon weapon = WeaponFixtures.gunWeapon(30, 1);
		ModifiersData modifiers = new ModifiersData();
		modifiers.setPenetration(new PenetrationModifier(3, 0, 0.2));
		weapon.setModifiersData(modifiers);

		ProjectileState state = new ProjectileState(weapon, 100.0);
		Block glass = mock(Block.class);
		when(glass.getType()).thenReturn(Material.GLASS);

		assertTrue(ModifierHandler.handleBlockPenetration(state, glass));
		assertEquals(1, state.getBlocksPenetrated());
		assertEquals(80.0, state.getCurrentDamage(), 0.0001);
	}

}
