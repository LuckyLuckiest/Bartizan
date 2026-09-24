package org.luckyraven.bartizan.configuration.parser;

import org.jetbrains.annotations.Nullable;
import org.luckyraven.keystone.persistence.config.ConfigNode;
import org.luckyraven.keystone.persistence.config.ConfigReport;
import org.luckyraven.keystone.persistence.config.MappingNode;
import org.luckyraven.keystone.persistence.config.NodeReader;
import org.luckyraven.keystone.persistence.config.Severity;
import org.luckyraven.bartizan.api.weapon.dto.ExplosionData;
import org.luckyraven.bartizan.api.weapon.dto.ExplosionData.Airstrike;
import org.luckyraven.bartizan.api.weapon.dto.ExplosionData.Cluster;
import org.luckyraven.bartizan.api.weapon.dto.ExplosionData.Detonation;
import org.luckyraven.bartizan.api.weapon.dto.ExplosionData.Exposure;
import org.luckyraven.bartizan.api.weapon.dto.ExplosionData.Shape;
import org.luckyraven.bartizan.api.weapon.dto.ExplosionData.Trigger;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/**
 * Parses a weapon's {@code Explosion:} block (gate {@code HI-a}, "explosion parity") into a shared
 * {@link ExplosionData} — used by both {@code GunWeaponParser} ({@code Shoot.Projectile.Explosion:}) and
 * {@code ThrowableWeaponParser} ({@code Throw.Explosion:}).
 * <p>
 * Every key inside {@code Explosion:} falls back to the caller's {@link LegacyDefaults} when omitted — including
 * when the whole {@code Explosion:} block is absent, which is the case for every weapon file bundled before this
 * gate — so an unconfigured weapon behaves exactly as it did under the two old explosion paths.
 */
public final class ExplosionSectionParser {

	private ExplosionSectionParser() {
	}

	/**
	 * The legacy-key values a caller already parsed off its own section (guns: {@code Damage.Explosion_Damage}
	 * etc.; throwables: {@code Throw.Explosion_Radius} etc.), threaded through as the default for the matching
	 * {@code Explosion:} key when that key is absent.
	 *
	 * @param shapeDefault the caller's historical falloff shape — guns default to {@link Shape#SPHERE} (the old
	 * 		rocket linear falloff); throwables default to {@link Shape#FLAT} (the old grenade behaviour: full
	 * 		{@code damage} anywhere inside {@code radius}, no vanilla blast stacked on top).
	 * @param detonationDefault the caller's historical detonation behaviour — guns explode immediately on any
	 * 		impact ({@code Set.of(BLOCK, ENTITY)}, no delay, no fuse); throwables never explode on impact and rely
	 * 		purely on their fuse ({@code Set.of()}, no delay, {@code Fuse_Time} ticks).
	 */
	public record LegacyDefaults(double radius, double damage, int fireTicks, @Nullable Double knockback,
	                             boolean ownerImmunity, boolean ignoreTeams, Shape shapeDefault,
	                             Detonation detonationDefault) {
	}

	public static ExplosionData parse(@Nullable NodeReader explosion, LegacyDefaults legacy, ConfigReport report) {
		ExplosionData data = new ExplosionData();

		if (explosion == null) {
			data.setRadius(legacy.radius());
			data.setDamage(legacy.damage());
			data.setFireTicks(legacy.fireTicks());
			data.setShape(legacy.shapeDefault());
			data.setKnockback(legacy.knockback());
			data.setOwnerImmunity(legacy.ownerImmunity());
			data.setIgnoreTeams(legacy.ignoreTeams());
			data.setDetonation(legacy.detonationDefault());
			return data;
		}

		data.setRadius(explosion.get("Radius").asDouble().min(0).orDefault(legacy.radius()));
		data.setDamage(explosion.get("Damage").asDouble().min(0).orDefault(legacy.damage()));
		data.setFireTicks(explosion.get("Fire_Ticks").asInt().min(0).orDefault(legacy.fireTicks()));
		data.setShape(parseEnum(explosion, "Shape", legacy.shapeDefault(), Shape::valueOf, report));
		data.setExposure(parseEnum(explosion, "Exposure", Exposure.DISTANCE, Exposure::valueOf, report));
		data.setBlockDamage(explosion.get("Block_Damage").asBool().orDefault(false));
		data.setOwnerImmunity(explosion.get("Owner_Immunity").asBool().orDefault(legacy.ownerImmunity()));
		data.setIgnoreTeams(explosion.get("Ignore_Teams").asBool().orDefault(legacy.ignoreTeams()));
		// Not a ternary: a primitive-double branch alongside a possibly-null Double legacy default forces
		// auto-unboxing of both operands under Java's conditional-expression typing rules, throwing an NPE the
		// instant the legacy value is null even when that branch is never actually taken.
		if (explosion.has("Knockback")) {
			data.setKnockback(explosion.get("Knockback").asDouble().orDefault(0.0));
		} else {
			data.setKnockback(legacy.knockback());
		}

		data.setCluster(parseCluster(explosion, report));
		data.setAirstrike(parseAirstrike(explosion, data.getRadius(), report));
		data.setDetonation(parseDetonation(explosion, legacy.detonationDefault(), report));

		return data;
	}

	@Nullable
	private static Cluster parseCluster(NodeReader explosion, ConfigReport report) {
		MappingNode section = explosion.get("Cluster").asMapping().orNull();
		if (section == null) return null;

		NodeReader cluster = NodeReader.of(section, report);
		// BZ-CF-17: no ceiling let a Count of e.g. 5000 fan out into 5000 concurrent SteppedProjectileTasks per
		// detonation - each its own scheduler task, entity and potential nested explosion/block-damage scan.
		int    count      = cluster.get("Count").asInt().min(1).max(32).orDefault(3);
		double speed      = cluster.get("Speed").asDouble().min(0).orDefault(1.0);
		int    delayTicks = cluster.get("Delay_Ticks").asInt().min(0).orDefault(0);
		return new Cluster(count, speed, delayTicks);
	}

	@Nullable
	private static Airstrike parseAirstrike(NodeReader explosion, double explosionRadius, ConfigReport report) {
		MappingNode section = explosion.get("Airstrike").asMapping().orNull();
		if (section == null) return null;

		NodeReader airstrike = NodeReader.of(section, report);
		// BZ-CF-17: same unbounded-fan-out risk as Cluster.Count above.
		int    count      = airstrike.get("Count").asInt().min(1).max(32).orDefault(3);
		double height     = airstrike.get("Height").asDouble().min(0).orDefault(10.0);
		double radius     = airstrike.get("Radius").asDouble().min(0).orDefault(explosionRadius);
		int    delayTicks = airstrike.get("Delay_Ticks").asInt().min(0).orDefault(0);
		return new Airstrike(count, height, radius, delayTicks);
	}

	private static Detonation parseDetonation(NodeReader explosion, Detonation legacyDefault, ConfigReport report) {
		MappingNode section = explosion.get("Detonation").asMapping().orNull();
		if (section == null) return legacyDefault;

		NodeReader detonation = NodeReader.of(section, report);

		Set<Trigger> impactWhen = parseTriggers(detonation, report);
		if (impactWhen == null) impactWhen = legacyDefault.impactWhen();

		int delayAfterImpact = detonation.get("Delay_After_Impact").asInt().min(0)
		                                 .orDefault(legacyDefault.delayAfterImpactTicks());
		int fuseTicks = detonation.get("Fuse_Ticks").asInt().min(0).orDefault(legacyDefault.fuseTicks());

		return new Detonation(impactWhen, delayAfterImpact, fuseTicks);
	}

	/**
	 * @return the parsed trigger set, or {@code null} when {@code Impact_When} is absent entirely (caller falls
	 * 		back to the legacy default) — an empty list is a legitimate "never on impact" and is returned as such.
	 */
	@Nullable
	private static Set<Trigger> parseTriggers(NodeReader detonation, ConfigReport report) {
		if (!detonation.has("Impact_When")) return null;

		Set<Trigger> triggers = EnumSet.noneOf(Trigger.class);
		for (String raw : detonation.get("Impact_When").asList().ofStrings().orEmpty()) {
			try {
				triggers.add(Trigger.valueOf(raw.trim().toUpperCase(Locale.ROOT)));
			} catch (IllegalArgumentException exception) {
				ConfigNode node = detonation.get("Impact_When").node();
				report.add(Severity.WARNING, node != null ? node.location() : detonation.mapping().location(),
				           "Detonation.Impact_When", "unknown trigger '" + raw + "'", "explosion.unknown_trigger");
			}
		}
		return Set.copyOf(triggers);
	}

	private static <E extends Enum<E>> E parseEnum(NodeReader parent, String key, E fallback,
	                                                Function<String, E> valueOf, ConfigReport report) {
		String raw = parent.get(key).asString().orNull();
		if (raw == null) return fallback;

		try {
			return valueOf.apply(raw.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException exception) {
			ConfigNode node = parent.get(key).node();
			String     kind = key.toLowerCase(Locale.ROOT);
			report.add(Severity.WARNING, node != null ? node.location() : parent.mapping().location(),
			           "Explosion." + key, "unknown " + kind + " '" + raw + "'", "explosion.unknown_" + kind);
			return fallback;
		}
	}

}
