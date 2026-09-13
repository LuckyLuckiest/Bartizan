package org.luckyraven.bartizan.effect;

import lombok.Builder;
import lombok.Getter;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.raytrace.WeaponMuzzle;
import org.luckyraven.bartizan.util.BartizanChatUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Immutable snapshot an {@link Effect} reads its placeholders, targets and locations off. Built once per
 * {@link EffectRunner#run} call by the firing action (weapons-roadmap.md gate {@code HA}, §1 "Targets").
 */
@Getter
@Builder
public class EffectContext {

	private final Weapon weapon;
	@Nullable
	private final LivingEntity source;
	@Nullable
	private final LivingEntity victim;
	@Nullable
	private final Location muzzle;
	@Nullable
	private final Location impact;
	private final double damage;
	private final double distance;
	private final int level;
	private final int ammoLeft;
	private final int ammoMax;
	@Nullable
	private final String denyReason;

	/**
	 * Pre-fills the fields every firing action's "shot" context shares — {@code weapon}, {@code source},
	 * {@code muzzle} and the ammo pair — so {@code GunAction}, {@code BiologicalAction}, {@code IncendiaryAction},
	 * {@code MeleeAction}, {@code ThrowableAction} and {@code NpcWeaponControllerImpl} don't each repeat the same
	 * boilerplate. Callers add anything else (e.g. {@code level}) and call {@code build()}.
	 */
	public static EffectContextBuilder shot(Weapon weapon, LivingEntity shooter, Vector lookDirection) {
		return builder()
				.weapon(weapon)
				.source(shooter)
				.muzzle(WeaponMuzzle.compute(shooter, lookDirection))
				.ammoLeft(weapon.getAmmunitionData() != null ? weapon.getCurrentMagCapacity() : 0)
				.ammoMax(weapon.getAmmunitionData() != null ? weapon.getAmmunitionData().getMaxMagCapacity() : 0);
	}

	/**
	 * @return the placeholder table (%player%, %victim%, %weapon%, %damage%, %distance%, %level%, %ammo_left%,
	 * 		%ammo_max%, %deny_reason%) used by {@link #format(String)}.
	 */
	public Map<String, String> placeholders() {
		Map<String, String> map = new LinkedHashMap<>();
		map.put("%player%", source != null ? source.getName() : "");
		map.put("%victim%", victim != null ? victim.getName() : "");
		map.put("%weapon%", weapon != null ? weapon.getDisplayName() : "");
		map.put("%damage%", String.format(Locale.ROOT, "%.1f", damage));
		map.put("%distance%", String.format(Locale.ROOT, "%.1f", distance));
		map.put("%level%", String.valueOf(level));
		map.put("%ammo_left%", String.valueOf(ammoLeft));
		map.put("%ammo_max%", String.valueOf(ammoMax));
		map.put("%deny_reason%", denyReason != null ? denyReason : "");
		return map;
	}

	/**
	 * Substitutes every placeholder in {@code text} and runs the result through {@link BartizanChatUtil#color}.
	 */
	@Nullable
	public String format(@Nullable String text) {
		if (text == null) return null;

		String result = text;
		for (Map.Entry<String, String> entry : placeholders().entrySet()) {
			result = result.replace(entry.getKey(), entry.getValue());
		}

		return BartizanChatUtil.color(result);
	}

	/**
	 * Resolves a location key: {@code source | muzzle | impact | victim}. {@code muzzle} falls back to the
	 * source's eye location; {@code impact} falls back to the victim's, then the source's, location.
	 */
	@Nullable
	public Location at(@Nullable String key) {
		if (key == null) return null;

		return switch (key.trim().toLowerCase(Locale.ROOT)) {
			case "source" -> source != null ? source.getLocation() : null;
			case "muzzle" -> muzzle != null ? muzzle : (source != null ? source.getEyeLocation() : null);
			case "impact" -> impact != null ? impact
			                                : (victim != null ? victim.getLocation()
			                                                   : (source != null ? source.getLocation() : null));
			case "victim" -> victim != null ? victim.getLocation() : null;
			default -> null;
		};
	}

	/**
	 * Resolves a target key: {@code source | victim | nearby}. {@code nearby} collects living entities within
	 * {@code radius} of {@link #at}{@code ("impact")}, falling back to the source's location.
	 */
	public List<LivingEntity> targets(@Nullable String key, double radius) {
		if (key == null) return List.of();

		return switch (key.trim().toLowerCase(Locale.ROOT)) {
			case "source" -> source != null ? List.of(source) : List.of();
			case "victim" -> victim != null ? List.of(victim) : List.of();
			case "nearby" -> nearby(radius);
			default -> List.of();
		};
	}

	private List<LivingEntity> nearby(double radius) {
		Location center = at("impact");
		if (center == null && source != null) center = source.getLocation();
		if (center == null || center.getWorld() == null || radius <= 0) return List.of();

		List<LivingEntity> found = new ArrayList<>();
		for (Entity entity : center.getWorld().getNearbyEntities(center, radius, radius, radius)) {
			if (entity instanceof LivingEntity living) found.add(living);
		}
		return found;
	}

}
