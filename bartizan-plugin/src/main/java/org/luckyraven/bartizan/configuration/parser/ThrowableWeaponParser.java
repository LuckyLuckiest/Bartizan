package org.luckyraven.bartizan.configuration.parser;

import com.cryptomorin.xseries.XMaterial;
import org.bukkit.Material;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.inventory.ItemStack;
import org.luckyraven.keystone.item.ItemBuilder;
import org.luckyraven.keystone.persistence.config.ConfigReport;
import org.luckyraven.keystone.persistence.config.MappingNode;
import org.luckyraven.keystone.persistence.config.NodeReader;
import org.luckyraven.keystone.persistence.config.Severity;
import org.luckyraven.bartizan.ammo.AmmunitionManager;
import org.luckyraven.bartizan.configuration.parser.AmmunitionSectionParser.ParsedAmmo;
import org.luckyraven.bartizan.api.weapon.dto.AmmunitionData;
import org.luckyraven.bartizan.api.weapon.dto.ExplosionData;
import org.luckyraven.bartizan.api.weapon.dto.ExplosionData.Shape;
import org.luckyraven.bartizan.api.weapon.dto.ReloadData;
import org.luckyraven.bartizan.api.weapon.dto.ThrowableData;
import org.luckyraven.bartizan.api.weapon.ThrowableType;
import org.luckyraven.bartizan.api.weapon.ThrowableWeapon;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Parses the {@code Throw:} / {@code Shoot:} section of a THROWABLE weapon YAML and constructs a
 * {@link ThrowableWeapon}.
 */
public class ThrowableWeaponParser {

	private final AmmunitionSectionParser ammoParser;

	public ThrowableWeaponParser(AmmunitionManager ammunitionManager) {
		this.ammoParser = new AmmunitionSectionParser(ammunitionManager);
	}

	public ThrowableWeapon parse(NodeReader root, NodeReader shoot, ConfigReport report, WeaponBaseData base)
			throws InvalidConfigurationException {
		if (shoot == null) {
			throw new InvalidConfigurationException("Throw/Shoot section not found for throwable weapon");
		}

		int     fuseTime        = shoot.get("Fuse_Time").asInt().min(0).orDefault(60);
		double  explosionRadius = shoot.get("Explosion_Radius").asDouble().min(0).orDefault(3.0);
		int     explosionDamage = shoot.get("Explosion_Damage").asInt().min(0).orDefault(6);
		int     fireTicks       = shoot.get("Fire_Ticks").asInt().min(0).orDefault(0);
		boolean bounces         = shoot.get("Bounces").asBool().orDefault(false);
		int     maxBounces      = shoot.get("Max_Bounces").asInt().min(0).orDefault(5);
		boolean sticky          = shoot.get("Sticky").asBool().orDefault(false);

		// Entity_Type (gate HI-a): dead key. Throwables have flown as the Display_Item (or the weapon's held
		// material) since before this gate — nothing ever read this back into a real thrown-entity type.
		if (shoot.get("Entity_Type").asString().orNull() != null) {
			report.add(Severity.WARNING, shoot.mapping().location(), "Throw.Entity_Type",
			           "ignored, throwables fly as the Display_Item", "throw.entity_type_ignored");
		}

		// Owner_Immunity defaults false so grenades keep self-damaging unless a weapon opts in (gate HF, §4).
		boolean ownerImmunity = shoot.get("Owner_Immunity").asBool().orDefault(false);
		boolean ignoreTeams   = shoot.get("Ignore_Teams").asBool().orDefault(false);
		double  knockback     = shoot.get("Knockback").asDouble().orDefault(0.0);

		// Explosion.Knockback legacy default (gate HI-a): 2.0 when Throw.Knockback is absent entirely, mirroring
		// the deleted ThrowableAction#detonate's hardcoded "push the thrower away at (1 - dist/radius) * 2.0"
		// vanilla-blast-adjacent knockback — the unified ExplosionHandler now applies this to every victim in
		// range (thrower included), not just the thrower. An explicit Throw.Knockback keeps its configured value.
		Double explosionKnockbackDefault = shoot.has("Knockback") ? knockback : 2.0;

		if (bounces && sticky) {
			throw new InvalidConfigurationException("Throwable cannot have both Bounces and Sticky enabled");
		}

		ThrowableType type          = ThrowableType.getType(shoot.get("Type").asString().required().orNull());
		List<String>  effects       = shoot.get("Effects").asList().ofStrings().orEmpty();
		int           cloudDuration = shoot.get("Cloud_Duration").asInt().min(0).orDefault(0);
		double        cloudRadius   = shoot.get("Cloud_Radius").asDouble().min(0).orDefault(0.0);

		MappingNode displaySection = shoot.get("Display_Item").asMapping().orNull();
		ItemStack displayItem = parseDisplayItem(displaySection == null ? null : NodeReader.of(displaySection, report),
		                                         base.fileName());

		if (type == ThrowableType.SMOKE && cloudDuration <= 0) {
			throw new InvalidConfigurationException(
					"Throwable '" + base.fileName() + "' has Type: SMOKE but Cloud_Duration is missing or <= 0");
		}
		if (type == ThrowableType.STUN && effects.isEmpty()) {
			throw new InvalidConfigurationException(
					"Throwable '" + base.fileName() + "' has Type: STUN but Effects list is empty");
		}

		ThrowableData throwableData = new ThrowableData(fuseTime, explosionRadius, explosionDamage, fireTicks,
		                                                bounces, maxBounces, sticky,
		                                                type, effects, cloudDuration, cloudRadius, displayItem,
		                                                ownerImmunity, ignoreTeams, knockback);
		ParsedAmmo     parsed         = ammoParser.parse(root, report);
		ReloadData     reloadData     = parsed != null ? parsed.reload() : null;
		AmmunitionData ammunitionData = parsed != null ? parsed.ammo() : null;

		// Explosion: (gate HI-a) — sibling of the legacy Throw.Explosion_*/Fuse_Time keys above.
		// ExplosionSectionParser lowers them in so a grenade's blast behaves identically either way. Legacy
		// throwables never explode on impact (Sticky/Bounces are physics-only) — the fuse alone drives detonation.
		MappingNode explosionSection = shoot.get("Explosion").asMapping().orNull();
		NodeReader  explosionReader  = explosionSection != null ? NodeReader.of(explosionSection, report) : null;
		ExplosionSectionParser.LegacyDefaults legacyExplosion = new ExplosionSectionParser.LegacyDefaults(
				explosionRadius, explosionDamage, fireTicks, explosionKnockbackDefault, ownerImmunity, ignoreTeams,
				Shape.FLAT, new ExplosionData.Detonation(Set.of(), 0, fuseTime));
		ExplosionData explosionData = ExplosionSectionParser.parse(explosionReader, legacyExplosion, report);

		ThrowableWeapon weapon = new ThrowableWeapon(null, base.fileName(), base.displayName(), base.category(),
		                                             base.material(), base.customModelData(), base.durability(),
		                                             base.lore(), base.dropHologram(), base.deathMessages(),
		                                             throwableData, reloadData, ammunitionData);
		weapon.setExplosionData(explosionData);
		return weapon;
	}

	/**
	 * Parses an optional {@code Display_Item:} subsection. Returns {@code null} when absent so the caller falls back to
	 * the weapon's held material.
	 */
	private ItemStack parseDisplayItem(NodeReader display, String fileName) {
		if (display == null) return null;

		String materialString = display.get("Material").asString().required().orNull();
		if (materialString == null) return null;

		Optional<XMaterial> xMaterialOptional = XMaterial.matchXMaterial(materialString);
		Material            material;
		if (xMaterialOptional.isPresent()) {
			material = xMaterialOptional.get().get();
		} else {
			throw new IllegalArgumentException(
					"Throwable '" + fileName + "' Display_Item.Material '" + materialString +
					"' is not a valid material");
		}
		if (material == null) return null;

		ItemBuilder builder = new ItemBuilder(new ItemStack(material));

		String name = display.get("Name").asString().orNull();
		if (name != null) builder.setDisplayName(name);

		List<String> lore = display.get("Lore").asList().ofStrings().orEmpty();
		if (!lore.isEmpty()) builder.setLore(lore);

		int customModelData = display.get("Custom_Model_Data").asInt().min(0).orDefault(0);
		if (customModelData > 0) builder.setCustomModelData(customModelData);

		return builder.build();
	}

}
