package org.luckyraven.bartizan.configuration.parser;

import com.cryptomorin.xseries.XMaterial;
import com.cryptomorin.xseries.particles.XParticle;
import lombok.CustomLog;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.configuration.InvalidConfigurationException;
import org.jetbrains.annotations.Nullable;
import org.luckyraven.keystone.persistence.config.ConfigNode;
import org.luckyraven.keystone.persistence.config.ConfigReport;
import org.luckyraven.keystone.persistence.config.MappingNode;
import org.luckyraven.keystone.persistence.config.NodeReader;
import org.luckyraven.keystone.persistence.config.Severity;
import org.luckyraven.bartizan.api.weapon.SelectiveFire;
import org.luckyraven.bartizan.ammo.AmmunitionManager;
import org.luckyraven.bartizan.configuration.parser.AmmunitionSectionParser.ParsedAmmo;
import org.luckyraven.bartizan.api.weapon.dto.AmmunitionData;
import org.luckyraven.bartizan.api.weapon.dto.BouncyData;
import org.luckyraven.bartizan.api.weapon.dto.DropoffStep;
import org.luckyraven.bartizan.api.weapon.dto.ExplosionData;
import org.luckyraven.bartizan.api.weapon.dto.ExplosionData.Shape;
import org.luckyraven.bartizan.api.weapon.dto.ExplosionData.Trigger;
import org.luckyraven.bartizan.api.weapon.dto.ProjectileData;
import org.luckyraven.bartizan.api.weapon.dto.ReloadData;
import org.luckyraven.bartizan.api.weapon.dto.VisualData;
import org.luckyraven.bartizan.api.weapon.ProjectileType;
import org.luckyraven.bartizan.api.weapon.GunWeapon;
import org.luckyraven.bartizan.util.BlockGroupResolver;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Parses the {@code Shoot:} section of a GUN weapon YAML and constructs a {@link GunWeapon}. Ammunition is required for
 * guns — throws if the {@code Ammunition} section is absent or invalid.
 */
@CustomLog
public class GunWeaponParser {

	private final AmmunitionSectionParser ammoParser;

	public GunWeaponParser(AmmunitionManager ammunitionManager) {
		this.ammoParser = new AmmunitionSectionParser(ammunitionManager);
	}

	public GunWeapon parse(NodeReader root, NodeReader shoot, ConfigReport report, WeaponBaseData base)
			throws InvalidConfigurationException {
		if (shoot == null) throw new InvalidConfigurationException("Shoot section not found");

		SelectiveFireSectionParser.ParsedSelectiveFire parsedSelectiveFire =
				SelectiveFireSectionParser.parse(shoot, report, base.fileName());
		if (parsedSelectiveFire == null) {
			throw new InvalidConfigurationException(
					"Gun weapon '" + base.fileName() + "' is missing Selective_Fire under Shoot:");
		}
		SelectiveFire selectiveFire = parsedSelectiveFire.current();

		MappingNode projectileSection = shoot.get("Projectile").asMapping().required().orNull();
		if (projectileSection == null) {
			throw new InvalidConfigurationException(
					"Gun weapon '" + base.fileName() + "' is missing Projectile under Shoot:");
		}

		NodeReader projectile = NodeReader.of(projectileSection, report);

		int            projectileSpeed      = projectile.get("Speed").asInt().min(0).required().orDefault(0);
		String         projectileTypeString = projectile.get("Type").asString().required().orDefault("BULLET");
		ProjectileType projectileType       = ProjectileType.getType(projectileTypeString);

		// Pellets: hitscan "burst" count per shot - historically hardcoded to 8 for SPREAD and 1 for every other
		// type (the old WeaponShooting.SPREAD_PELLET_COUNT constant). Now user-configurable; the type-based
		// default is preserved for files that don't set it.
		int defaultPellets    = projectileType == ProjectileType.SPREAD ? 8 : 1;
		int projectilePellets = projectile.get("Pellets").asInt().min(1).orDefault(defaultPellets);

		MappingNode damageSection = projectile.get("Damage").asMapping().required().orNull();
		if (damageSection == null) {
			throw new InvalidConfigurationException(
					"Gun weapon '" + base.fileName() + "' is missing Damage under Shoot.Projectile:");
		}

		NodeReader damage = NodeReader.of(damageSection, report);

		int    projectileDamage          = damage.get("Base").asInt().min(0).required().orDefault(0);
		int    projectileExplosionDamage = damage.get("Explosion_Damage").asInt().min(0).orDefault(0);
		// Explosion_Radius is authored separately from Explosion_Damage. Before 0.8.3 the radius was read off the
		// damage value, so Explosion_Damage: 50 produced a 50-block blast. Default mirrors ThrowableWeaponParser.
		double projectileExplosionRadius = damage.get("Explosion_Radius").asDouble().min(0).orDefault(3.0);
		int    projectileFireTicks       = damage.get("Fire_Ticks").asInt().min(0).orDefault(0);
		int    projectileHeadDamage      = damage.get("Head").asInt().min(0).orDefault(0);

		int criticalHitChance = 0;
		int criticalHitDamage = 0;

		MappingNode criticalHitSection = damage.get("Critical_Hit").asMapping().orNull();
		if (criticalHitSection != null) {
			NodeReader crit = NodeReader.of(criticalHitSection, report);
			criticalHitChance = crit.get("Chance").asInt().min(0).max(100).orDefault(0);
			criticalHitDamage = crit.get("Amount").asInt().min(0).orDefault(0);
		}

		// Dropoff: list of "<distance> <delta>" entries — malformed entries are skipped with a warning rather
		// than failing the whole weapon load.
		List<DropoffStep> dropoff = new ArrayList<>();
		for (String entry : damage.get("Dropoff").asList().ofStrings().orEmpty()) {
			DropoffStep step = DropoffStep.parse(entry);
			if (step != null) {
				dropoff.add(step);
			} else {
				log.warn("Gun weapon '{}' has an invalid Damage.Dropoff entry '{}' — skipping it", base.fileName(),
				         entry);
			}
		}

		// Hit-zone deltas (gate HF §2) — Head already existed above; Body/Arms/Legs/Feet/Back are new.
		double bodyDamage = damage.get("Body").asDouble().orDefault(0.0);
		double armsDamage = damage.get("Arms").asDouble().orDefault(0.0);
		double legsDamage = damage.get("Legs").asDouble().orDefault(0.0);
		double feetDamage = damage.get("Feet").asDouble().orDefault(0.0);
		double backDamage = damage.get("Back").asDouble().orDefault(0.0);

		int     armorDamage   = damage.get("Armor_Damage").asInt().min(0).orDefault(0);
		boolean ownerImmunity = damage.get("Owner_Immunity").asBool().orDefault(false);
		boolean ignoreTeams   = damage.get("Ignore_Teams").asBool().orDefault(false);

		// Knockback: null (absent) leaves vanilla knockback untouched; any present value (0 included) replaces it.
		Double knockback = damage.has("Knockback") ? damage.get("Knockback").asDouble().orDefault(0.0) : null;

		// Explosion: (gate HI-a) — sibling of Damage: under Projectile:. Absent entirely for every weapon file
		// bundled before this gate; ExplosionSectionParser lowers the legacy Damage.Explosion_* keys above into it
		// so a rocket's blast behaves identically either way. Legacy guns explode immediately on any impact.
		// Fire_Ticks lowers to 0, not Damage.Fire_Ticks: the old vanilla-style rocket blast never set victims on
		// fire (only a direct hit did, via DamageData.fireTicks above) — an explicit Explosion.Fire_Ticks still
		// works.
		MappingNode explosionSection = projectile.get("Explosion").asMapping().orNull();
		NodeReader  explosionReader  = explosionSection != null ? NodeReader.of(explosionSection, report) : null;
		ExplosionSectionParser.LegacyDefaults legacyExplosion = new ExplosionSectionParser.LegacyDefaults(
				projectileExplosionRadius, projectileExplosionDamage, 0, knockback, ownerImmunity,
				ignoreTeams, Shape.SPHERE, new ExplosionData.Detonation(Set.of(Trigger.BLOCK, Trigger.ENTITY), 0, 0));
		ExplosionData explosionData = ExplosionSectionParser.parse(explosionReader, legacyExplosion, report);

		int projectileConsumed = projectile.get("Consumed_Amount").asInt().min(1).orDefault(1);
		int projectilePerShot  = projectile.get("Per_Shot").asInt().min(1).orDefault(1);
		// Cooldown is authored as a decimal (e.g. 0.8) and Bukkit's getInt silently truncated to 0. Preserve that
		// semantic by reading as double and casting — keeps existing configs working without surfacing a type error.
		int     projectileCooldown = (int) projectile.get("Cooldown").asDouble().min(0).orDefault(0.0);
		int     projectileDistance = projectile.get("Distance").asInt().min(0).orDefault(0);
		// Particle: gate HI part b wires this up to draw a per-tick tracer segment for ROCKET/FLARE — dead
		// (parsed, never read) before that gate.
		boolean projectileParticle = projectile.get("Particle").asBool().orDefault(false);
		double  projectileGravity  = projectile.get("Gravity").asDouble().orDefault(0.0);

		double  projectileDrag           = projectile.get("Drag").asDouble().min(0).max(1).orDefault(0.0);
		boolean projectileExtinguishWater = projectile.get("Extinguish_In_Water").asBool().orDefault(false);
		int     projectileAliveTicks     = projectile.get("Alive_Ticks").asInt().min(0).orDefault(0);
		Particle projectileTrail         = parseTrail(projectile, report, base.fileName());
		VisualData projectileVisual      = parseVisual(projectile, projectileType, report, base.fileName());
		BouncyData projectileBouncy      = parseBouncy(projectile, report);

		MappingNode weaponConsumedSection = shoot.get("Weapon_Consumed").asMapping().required().orNull();
		int         weaponConsumedOnShot  = 0;
		if (weaponConsumedSection != null) {
			weaponConsumedOnShot = NodeReader.of(weaponConsumedSection, report)
			                                 .get("Consume_On_Shot").asInt().min(0).orDefault(0);
		}

		// A missing/empty Ammunition: section tolerates no magazine at all — same as every other weapon category
		// (WM weapons without an ammo block need to load through the future importer). An Ammunition: section that
		// IS present but invalid (unknown ammo id, or both Ammo_Type and Types set) is a config error handled
		// uniformly by WeaponAddon.registerWeapon via ConfigReport, not by throwing here.
		ParsedAmmo     parsed         = ammoParser.parse(root, report);
		ReloadData     reloadData     = parsed != null ? parsed.reload() : null;
		AmmunitionData ammunitionData = parsed != null ? parsed.ammo() : null;

		ProjectileData projectileData = ProjectileData.builder()
		                                              .speed(projectileSpeed)
		                                              .type(projectileType)
		                                              .damage(projectileDamage)
		                                              .consumed(projectileConsumed)
		                                              .perShot(projectilePerShot)
		                                              .cooldown(projectileCooldown)
		                                              .distance(projectileDistance)
		                                              .particle(projectileParticle)
		                                              .gravity(projectileGravity)
		                                              .pellets(projectilePellets)
		                                              .drag(projectileDrag)
		                                              .visual(projectileVisual)
		                                              .bouncy(projectileBouncy)
		                                              .extinguishInWater(projectileExtinguishWater)
		                                              .aliveTicks(projectileAliveTicks)
		                                              .trail(projectileTrail)
		                                              .build();

		GunWeapon gun = new GunWeapon(null, base.fileName(), base.displayName(), base.category(),
		                              base.material(), base.customModelData(), base.durability(), base.lore(),
		                              base.dropHologram(), base.deathMessages(), selectiveFire, weaponConsumedOnShot,
		                              projectileData, reloadData, ammunitionData);

		gun.setAllowedSelectiveFires(parsedSelectiveFire.allowed());

		gun.getDamageData().setExplosionDamage(projectileExplosionDamage);
		gun.getDamageData().setExplosionRadius(projectileExplosionRadius);
		gun.getDamageData().setFireTicks(projectileFireTicks);
		gun.getDamageData().setHeadDamage(projectileHeadDamage);
		gun.getDamageData().setCriticalHitChance(criticalHitChance);
		gun.getDamageData().setCriticalHitDamage(criticalHitDamage);
		gun.getDamageData().setDropoff(dropoff);
		gun.getDamageData().setBodyDamage(bodyDamage);
		gun.getDamageData().setArmsDamage(armsDamage);
		gun.getDamageData().setLegsDamage(legsDamage);
		gun.getDamageData().setFeetDamage(feetDamage);
		gun.getDamageData().setBackDamage(backDamage);
		gun.getDamageData().setArmorDamage(armorDamage);
		gun.getDamageData().setOwnerImmunity(ownerImmunity);
		gun.getDamageData().setIgnoreTeams(ignoreTeams);
		gun.getDamageData().setKnockback(knockback);

		gun.setExplosionData(explosionData);

		return gun;
	}

	/**
	 * {@code Projectile.Visual} (gate {@code HI} part b): which cosmetic entity the stepped-flight task drives.
	 * Absent entirely, or missing just {@code Type}, falls back to the historical hardcoded visual — {@code
	 * fireball} for ROCKET, {@code firework} for every other type (FLARE). An unrecognised {@code Type} string is
	 * a {@link Severity#WARNING} falling back to that same type-based default, rather than failing the load.
	 */
	private static VisualData parseVisual(NodeReader projectile, ProjectileType type, ConfigReport report,
	                                      String fileName) {
		VisualData.VisualType fallback = type == ProjectileType.ROCKET
		                                  ? VisualData.VisualType.FIREBALL
		                                  : VisualData.VisualType.FIREWORK;

		MappingNode visualSection = projectile.get("Visual").asMapping().orNull();
		if (visualSection == null) {
			return new VisualData(fallback, null, 0, null);
		}

		NodeReader visual = NodeReader.of(visualSection, report);
		String     raw    = visual.get("Type").asString().orNull();

		VisualData.VisualType visualType = fallback;
		if (raw != null) {
			try {
				visualType = VisualData.VisualType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
			} catch (IllegalArgumentException exception) {
				ConfigNode node = visual.get("Type").node();
				report.add(Severity.WARNING, node != null ? node.location() : visualSection.location(),
				          childPath(visual, "Type"),
				          "unknown Projectile.Visual.Type '" + raw + "' in weapon '" + fileName + "'",
				          "projectile.unknown_visual_type");
			}
		}

		Material item  = resolveMaterial(visual.get("Item").asString().orNull());
		int      cmd   = visual.get("Custom_Model_Data").asInt().min(0).orDefault(0);
		Material block = resolveMaterial(visual.get("Block").asString().orNull());

		return new VisualData(visualType, item, cmd, block);
	}

	@Nullable
	private static Material resolveMaterial(@Nullable String materialName) {
		if (materialName == null) return null;
		return XMaterial.matchXMaterial(materialName).map(XMaterial::get).orElse(null);
	}

	/**
	 * {@code Projectile.Bouncy} (gate {@code HI} part b): {@code Default} is the multiplier applied to any block
	 * not otherwise listed ({@code 0.0} = no bounce — matches the historical behaviour of terminating on first
	 * block contact); every other key is a material or {@link BlockGroupResolver} block-group name with its own
	 * multiplier. Absent entirely -&gt; {@code null}, meaning every block hit still terminates the projectile.
	 */
	@Nullable
	private static BouncyData parseBouncy(NodeReader projectile, ConfigReport report) {
		MappingNode bouncySection = projectile.get("Bouncy").asMapping().orNull();
		if (bouncySection == null) return null;

		NodeReader bouncy   = NodeReader.of(bouncySection, report);
		double     fallback = bouncy.get("Default").asDouble().min(0).orDefault(0.0);

		Map<Material, Double> perMaterial = new HashMap<>();
		for (String key : bouncy.keys()) {
			if (key.equalsIgnoreCase("Default")) continue;

			double multiplier = bouncy.get(key).asDouble().min(0).orDefault(fallback);
			for (Material material : BlockGroupResolver.resolve(key)) {
				perMaterial.put(material, multiplier);
			}
		}

		return new BouncyData(fallback, perMaterial);
	}

	/**
	 * {@code Projectile.Trail} (gate {@code HI} part b): a particle spawned once at the projectile's position
	 * every tick. Absent -&gt; {@code null} (no trail). An unrecognised particle name is a
	 * {@link Severity#WARNING} — the trail is simply skipped at runtime rather than failing the load.
	 */
	@Nullable
	private static Particle parseTrail(NodeReader projectile, ConfigReport report, String fileName) {
		String raw = projectile.get("Trail").asString().orNull();
		if (raw == null) return null;

		Particle trail = XParticle.of(raw).map(XParticle::get).orElse(null);
		if (trail == null) {
			ConfigNode node = projectile.get("Trail").node();
			report.add(Severity.WARNING, node != null ? node.location() : projectile.mapping().location(),
			          childPath(projectile, "Trail"),
			          "'" + raw + "' is not a recognised particle in weapon '" + fileName + "' — no trail will be drawn",
			          "projectile.unknown_trail");
		}
		return trail;
	}

	private static String childPath(NodeReader parent, String key) {
		String parentPath = parent.mapping().path();
		return parentPath == null || parentPath.isEmpty() ? key : parentPath + "." + key;
	}

}
