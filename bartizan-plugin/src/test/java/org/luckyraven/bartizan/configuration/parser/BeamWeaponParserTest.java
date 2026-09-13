package org.luckyraven.bartizan.configuration.parser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luckyraven.bartizan.api.weapon.BeamWeapon;
import org.luckyraven.bartizan.api.weapon.WeaponType;
import org.luckyraven.bartizan.api.weapon.dto.BeamData;
import org.luckyraven.bartizan.api.weapon.dto.ChargeData;
import org.luckyraven.bartizan.api.weapon.dto.EffectHook;
import org.luckyraven.bartizan.api.weapon.dto.EffectSpec;
import org.luckyraven.bartizan.api.weapon.dto.EffectsData;
import org.luckyraven.bartizan.api.weapon.dto.ModifiersData;
import org.luckyraven.bartizan.api.weapon.modifiers.action.PenetrationModifier;
import org.luckyraven.keystone.persistence.config.ConfigDocument;
import org.luckyraven.keystone.persistence.config.ConfigParser;
import org.luckyraven.keystone.persistence.config.ConfigReport;
import org.luckyraven.keystone.persistence.config.MappingNode;
import org.luckyraven.keystone.persistence.config.NodeReader;
import org.luckyraven.keystone.persistence.config.Severity;

import java.io.StringReader;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link BeamWeaponParser}: {@code Shoot.Beam.*} defaults and full parsing, {@code Pierce} lowering to a
 * {@link PenetrationModifier}, {@code Impact} lowering to {@code On_Beam_Fire} effects, and the {@code Mode}
 * warning (weapons-roadmap.md gate {@code HC}, §3.1).
 */
@DisplayName("BeamWeaponParser")
class BeamWeaponParserTest {

	private static final Path FIXTURE = Path.of("weapon.yml");

	private ConfigReport report;

	@Test
	@DisplayName("only Category: beam + Charge given -> Beam.* all default")
	void defaultsWhenOnlyChargeGiven() {
		NodeReader shoot = shootReaderFor("""
				Shoot:
				   Charge:
				      Time_Per_Level: 8
				      Max_Level: 4
				""");

		BeamData data = BeamWeaponParser.parseBeam(shoot, report);

		assertEquals(60.0, data.getRange());
		assertEquals(0.6, data.getWidth());
		assertEquals(1, data.getAmmoPerLevel());
		assertEquals(-1, data.getPierce().entities());
		assertEquals(0, data.getPierce().blocks());
		assertEquals(0.85, data.getPierce().damageMultiplierPerTarget());
		assertEquals(6.0, data.getDamage().base());
		assertEquals(5.0, data.getDamage().perLevel());
		assertEquals(4.0, data.getDamage().head());
		assertEquals(0.8, data.getDamage().knockback());
		assertEquals(0, data.getDamage().fireTicks());
		assertEquals("END_ROD", data.getPreview().particle());
		assertEquals(1.5, data.getPreview().lengthPerLevel());
		assertEquals(2, data.getPreview().interval());
		assertFalse(data.getPreview().guideLine());
		assertEquals("DUST", data.getRender().coreParticle());
		assertEquals("#66CCFF", data.getRender().coreColor());
		assertEquals("END_ROD", data.getRender().glowParticle());
		assertEquals(0.25, data.getRender().thickness());
		assertEquals(0.25, data.getRender().step());
		assertEquals(8, data.getRender().duration());
		assertFalse(data.isScorchBlocks());
		assertFalse(report.hasErrors());
	}

	@Test
	@DisplayName("full Beam: block parses every key")
	void fullBlockParses() {
		NodeReader shoot = shootReaderFor("""
				Shoot:
				   Beam:
				      Mode: burst
				      Range: 40.0
				      Width: 0.8
				      Ammo_Per_Level: 2
				      Pierce:
				         Entities: 3
				         Blocks: 1
				         Damage_Multiplier_Per_Target: 0.5
				      Damage:
				         Base: 10.0
				         Per_Level: 2.0
				         Head: 6.0
				         Knockback: 1.2
				         Fire_Ticks: 40
				      Preview:
				         Particle: CRIT
				         Length_Per_Level: 2.0
				         Interval: 3
				         Guide_Line: true
				      Render:
				         Core_Particle: FLAME
				         Core_Color: "#FF0000"
				         Glow_Particle: SMOKE
				         Thickness: 0.4
				         Step: 0.5
				         Duration: 12
				      Impact:
				         Particle: LAVA
				         Sound: ENTITY_GENERIC_EXPLODE
				         Scorch_Blocks: true
				""");

		BeamData data = BeamWeaponParser.parseBeam(shoot, report);

		assertEquals(40.0, data.getRange());
		assertEquals(0.8, data.getWidth());
		assertEquals(2, data.getAmmoPerLevel());
		assertEquals(3, data.getPierce().entities());
		assertEquals(1, data.getPierce().blocks());
		assertEquals(0.5, data.getPierce().damageMultiplierPerTarget());
		assertEquals(10.0, data.getDamage().base());
		assertEquals(2.0, data.getDamage().perLevel());
		assertEquals(6.0, data.getDamage().head());
		assertEquals(1.2, data.getDamage().knockback());
		assertEquals(40, data.getDamage().fireTicks());
		assertEquals("CRIT", data.getPreview().particle());
		assertEquals(2.0, data.getPreview().lengthPerLevel());
		assertEquals(3, data.getPreview().interval());
		assertTrue(data.getPreview().guideLine());
		assertEquals("FLAME", data.getRender().coreParticle());
		assertEquals("#FF0000", data.getRender().coreColor());
		assertEquals("SMOKE", data.getRender().glowParticle());
		assertEquals(0.4, data.getRender().thickness());
		assertEquals(0.5, data.getRender().step());
		assertEquals(12, data.getRender().duration());
		assertTrue(data.isScorchBlocks());
		assertFalse(report.hasErrors());
	}

	@Test
	@DisplayName("Mode other than 'burst' warns and is treated as burst")
	void nonBurstModeWarns() {
		NodeReader shoot = shootReaderFor("""
				Shoot:
				   Beam:
				      Mode: sustained
				""");

		BeamWeaponParser.parseBeam(shoot, report);

		assertTrue(report.issues().stream().anyMatch(
				issue -> issue.severity() == Severity.WARNING && issue.code().equals("beam.unsupported_mode")),
				"expected a beam.unsupported_mode warning");
	}

	@Test
	@DisplayName("Pierce lowers to a PenetrationModifier: -1 entities -> MAX_VALUE, multiplier -> reduction")
	void lowerPierce_buildsPenetrationModifier() {
		BeamWeapon weapon = beamWeapon(new BeamData.PierceData(-1, 2, 0.85));

		BeamWeaponParser.lowerPierce(weapon);

		PenetrationModifier penetration = weapon.getModifiersData().getPenetration();
		assertEquals(2, penetration.penetrateBlocks());
		assertEquals(Integer.MAX_VALUE, penetration.penetrateEntities());
		assertEquals(0.15, penetration.damageReduction(), 1e-9);
	}

	@Test
	@DisplayName("an explicit Modifiers.Penetration always wins over the Pierce lowering")
	void lowerPierce_explicitModifierWins() {
		BeamWeapon weapon = beamWeapon(new BeamData.PierceData(-1, 2, 0.85));
		weapon.setModifiersData(new ModifiersData());
		PenetrationModifier explicit = new PenetrationModifier(9, 9, 0.9);
		weapon.getModifiersData().setPenetration(explicit);

		BeamWeaponParser.lowerPierce(weapon);

		assertEquals(explicit, weapon.getModifiersData().getPenetration());
	}

	@Test
	@DisplayName("Impact lowers to one On_Beam_Fire sound spec and one particle spec, defaults when Impact: absent")
	void lowerImpactEffects_producesTwoSpecs() {
		NodeReader shoot = shootReaderFor("""
				Shoot:
				   Beam:
				      Range: 60.0
				""");

		EffectsData effects = EffectsData.empty();
		BeamWeaponParser.lowerImpactEffects(shoot, effects);

		List<EffectSpec> specs = effects.forHook(EffectHook.ON_BEAM_FIRE);
		assertEquals(2, specs.size());

		EffectSpec sound = specs.stream().filter(s -> s.type().equals("sound")).findFirst().orElseThrow();
		assertEquals("ENTITY_LIGHTNING_BOLT_IMPACT", sound.arg("Sound"));
		assertEquals("impact", sound.arg("At"));

		EffectSpec particle = specs.stream().filter(s -> s.type().equals("particle")).findFirst().orElseThrow();
		assertEquals("FLASH", particle.arg("Particle"));
		assertEquals("impact", particle.arg("At"));
	}

	@Test
	@DisplayName("Impact lowering never overrides a hook the weapon already declared its own Effects: list for")
	void lowerImpactEffects_doesNotMergeOverDeclaredHook() {
		NodeReader shoot = shootReaderFor("""
				Shoot:
				   Beam:
				      Impact:
				         Sound: ENTITY_GENERIC_EXPLODE
				""");

		EffectsData declared = EffectsData.empty();
		declared.put(EffectHook.ON_BEAM_FIRE,
		            List.of(new EffectSpec("message", Map.of("Text", "custom"))));

		BeamWeaponParser.lowerImpactEffects(shoot, declared);

		List<EffectSpec> specs = declared.forHook(EffectHook.ON_BEAM_FIRE);
		assertEquals(1, specs.size());
		assertEquals("message", specs.get(0).type());
	}

	private BeamWeapon beamWeapon(BeamData.PierceData pierce) {
		BeamData beamData = new BeamData(60.0, 0.6, 1, pierce,
		                                 new BeamData.BeamDamageData(6.0, 5.0, 4.0, 0.8, 0),
		                                 new BeamData.PreviewData("END_ROD", 1.5, 2, false),
		                                 new BeamData.RenderData("DUST", "#66CCFF", "END_ROD", 0.25, 0.25, 8),
		                                 false);
		ChargeData charge = new ChargeData(8, 4, 1, false);

		return new BeamWeapon(UUID.randomUUID(), "test_beam", "&bTest Beam", WeaponType.BEAM,
		                      org.bukkit.Material.STICK, 0, (short) 100, List.of(), false, null, beamData, charge,
		                      null, null);
	}

	private NodeReader shootReaderFor(String yaml) {
		report = new ConfigReport();
		ConfigDocument doc  = new ConfigParser().parse(FIXTURE, new StringReader(yaml), report);
		NodeReader     root = NodeReader.of(doc.root(), report);

		MappingNode shootSection = root.get("Shoot").asMapping().orNull();
		return shootSection != null ? NodeReader.of(shootSection, report) : null;
	}

}
