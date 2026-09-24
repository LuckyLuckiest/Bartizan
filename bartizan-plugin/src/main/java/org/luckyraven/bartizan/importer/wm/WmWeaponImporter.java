package org.luckyraven.bartizan.importer.wm;

import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The WeaponMechanics -> Bartizan weapon translator (weapons-roadmap.md gate {@code HM}, §6.2) - the mapping
 * table applied to one already-loaded WM weapon body ({@code YamlConfiguration}'s single top-level section, keyed
 * by the WM internal title). Builds the {@code WmYamlEmitter} tree {@code WmImportCommand} writes to
 * {@code plugins/Bartizan/weapon/<title>.yml}, and fills the matching {@link WmImportReport.WeaponEntry}.
 */
public final class WmWeaponImporter {

	/**
	 * One resolved physical-ammo reference a weapon's {@code Reload.Ammo} needs appended to {@code ammunition.yml}.
	 *
	 * @param sourceRef the raw, un-sanitized {@code Reload.Ammo} ref this entry came from - carried through so the
	 * 		shared append point (only place that sees every weapon's {@code AmmoAppend} together) can tell two
	 * 		different WM ammo refs that collide after {@link #sanitizeKey} (e.g. {@code "5.56mm"} vs
	 * 		{@code "5,56mm"}, both {@code wm_5_56mm}) apart from the same ref imported by a second weapon.
	 */
	public record AmmoAppend(String id, String material, String name, String sourceRef) {
	}

	/** The result of importing one WM weapon file. */
	public record ImportedWeapon(String fileKey, Map<String, Object> yaml, @Nullable AmmoAppend ammoAppend) {
	}

	private WmWeaponImporter() {
	}

	/**
	 * @param title the WM weapon's internal title (the file's single top-level YAML key).
	 * @param body the title's own section - everything the roadmap's mapping table calls {@code Info.*},
	 * 		{@code Shoot.*}, {@code Damage.*}, etc. hangs directly off this.
	 * @param projectiles every loaded {@code projectiles/*.yml} entry, keyed by its own top-level ref name, so
	 * 		{@code Projectile: "<ref>"} can be inlined.
	 *
	 * @return the imported weapon, or {@code null} when the body matches none of the three supported shapes (gun/
	 * 		throwable/melee) - e.g. a WM "consumable" like {@code Stim} with no {@code Projectile_Speed},
	 * 		{@code Explosion} or {@code Melee.Enable_Melee}. The caller's report already has a line explaining why.
	 */
	@Nullable
	public static ImportedWeapon importWeapon(String title, ConfigurationSection body,
	                                          Map<String, ConfigurationSection> projectiles,
	                                          Map<String, ConfigurationSection> ammos,
	                                          WmImportReport.WeaponEntry report) {
		String fileKey = sanitizeKey(title);

		ConfigurationSection shoot     = body.getConfigurationSection("Shoot");
		ConfigurationSection explosion = body.getConfigurationSection("Explosion");
		boolean              melee     = body.getBoolean("Melee.Enable_Melee", false);
		boolean              hasSpeed  = shoot != null && shoot.isSet("Projectile_Speed");
		boolean              consumeOnShoot = shoot != null && shoot.getBoolean("Consume_Item_On_Shoot", false);

		Map<String, Object> yaml = new LinkedHashMap<>();
		int[]                commentSeq = {0};

		if (melee && !hasSpeed) {
			buildInformation(title, body, yaml, report, "melee");
			buildAttack(body, yaml, report);
			dropMeleeOnlyKeys(body, report);
			buildHud(body, yaml);
			buildEffects(body, yaml, "melee", report, commentSeq);
			return new ImportedWeapon(fileKey, yaml, null);
		}

		if (explosion != null && consumeOnShoot) {
			buildInformation(title, body, yaml, report, "throwable");
			buildThrow(body, shoot, explosion, yaml, report);
			buildHud(body, yaml);
			buildEffects(body, yaml, "throwable", report, commentSeq);
			return new ImportedWeapon(fileKey, yaml, null);
		}

		if (hasSpeed) {
			buildInformation(title, body, yaml, report, "gun");
			AmmoAppend ammo = buildGun(body, shoot, explosion, projectiles, ammos, yaml, report);
			buildHud(body, yaml);
			buildEffects(body, yaml, "gun", report, commentSeq);
			return new ImportedWeapon(fileKey, yaml, ammo);
		}

		report.dropped("unsupported weapon shape (no Shoot.Projectile_Speed, Explosion+Consume_Item_On_Shoot, or "
		                + "Melee.Enable_Melee) - likely a consumable/attachment-only entry; skipped entirely");
		return null;
	}

	public static String sanitizeKey(String title) {
		String cleaned = title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_")
		                       .replaceAll("^_+|_+$", "");
		return cleaned.isEmpty() ? "wm_weapon" : cleaned;
	}

	// -------------------------------------------------------------------------
	// Information (shared by every category)
	// -------------------------------------------------------------------------

	private static void buildInformation(String title, ConfigurationSection body, Map<String, Object> yaml,
	                                     WmImportReport.WeaponEntry report, String category) {
		Map<String, Object> information = new LinkedHashMap<>();
		yaml.put("Information", information);

		String rawName = body.getString("Info.Weapon_Item.Name", title);
		information.put("Name", WmColorTranslator.translate(rawName));
		information.put("Category", category);
		information.put("Material", body.getString("Info.Weapon_Item.Type", "FEATHER"));

		int baseCmd = resolveSkins(body, yaml, information, report);
		// WM's Custom Model Data is a bare int and a negative value is legal there (Combat_Knife.yml ships
		// Skin.Default.Custom_Model_Data: -10) - != 0 (not > 0) so a genuinely negative WM value isn't silently
		// conflated with "unset". Bartizan's own Information.Custom_Model_Data floors at 0 (WeaponAddon), so a
		// negative value still can't be carried over as-is - reported instead of dropped without a trace.
		if (baseCmd > 0) {
			information.put("Custom_Model_Data", baseCmd);
		} else if (baseCmd < 0) {
			report.approximated("Skin.Default resolved to a negative Custom_Model_Data (" + baseCmd + ") - WM "
			                     + "allows this but Bartizan's Information.Custom_Model_Data requires >= 0, left unset");
		}

		Map<String, Object> durability = new LinkedHashMap<>();
		durability.put("Base", 1);
		Map<String, Object> change = new LinkedHashMap<>();
		change.put("On_Shot", 0);
		durability.put("Change", change);
		information.put("Durability", durability);

		List<String> lore = new ArrayList<>();
		for (String line : body.getStringList("Info.Weapon_Item.Lore")) {
			lore.add(WmColorTranslator.translate(line));
		}
		information.put("Lore", lore);

		information.put("Equip_Delay", body.getInt("Info.Weapon_Equip_Delay", 0));
		information.put("Deny_Use_In_Crafting", body.getBoolean("Info.Weapon_Item.Deny_Use_In_Crafting", true));

		if (body.isSet("Info.Cancel")) {
			Map<String, Object> cancel = new LinkedHashMap<>();
			cancel.put("Drop_Item", body.getBoolean("Info.Cancel.Drop_Item", false));
			cancel.put("Break_Blocks", body.getBoolean("Info.Cancel.Break_Blocks", true));
			cancel.put("Arm_Swing", body.getBoolean("Info.Cancel.Arm_Swing_Animation", false));
			information.put("Cancel", cancel);

			if (body.isSet("Info.Cancel.Block_Interactions") || body.isSet("Info.Cancel.Item_Interactions")) {
				report.dropped("Cancel.Block_Interactions/Item_Interactions - no Bartizan equivalent");
			}
		}

		List<String> attributes = new ArrayList<>();
		for (String raw : body.getStringList("Info.Weapon_Item.Attributes")) {
			String[] parts = raw.trim().split("\\s+");
			if (parts.length != 2) {
				report.dropped("Info.Weapon_Item.Attributes entry '" + raw + "' - unrecognised shape");
				continue;
			}
			attributes.add(parts[0].toUpperCase(Locale.ROOT) + " ADD_NUMBER " + parts[1]);
		}
		if (!attributes.isEmpty()) information.put("Attributes", attributes);

		report.mapped("Info.Weapon_Item.* -> Information.*");
	}

	/**
	 * Resolves {@code Skin.Default} into the base custom model data, then every other {@code Skin.<key>} into
	 * {@code Skins.<Key>} (the 4 known states) or {@code Skins.Named.<key>.Default} (anything else) - a value is
	 * either a bare int, an {@code "ADD n"} string (relative to the base), or a mapping with its own
	 * {@code Custom_Model_Data}. The emitted keys are exactly the ones {@code SkinSectionParser} (gate {@code HJ})
	 * reads.
	 *
	 * @return the resolved base custom model data (0 when {@code Skin.Default} is absent/unresolvable).
	 */
	private static int resolveSkins(ConfigurationSection body, Map<String, Object> yaml,
	                                Map<String, Object> information, WmImportReport.WeaponEntry report) {
		ConfigurationSection skin = body.getConfigurationSection("Skin");
		if (skin == null) return 0;

		int base = resolveSkinValue(skin, "Default", 0, report);
		Map<String, Object> skins = new LinkedHashMap<>();
		Map<String, Object> named = new LinkedHashMap<>();

		for (String key : skin.getKeys(false)) {
			if (key.equalsIgnoreCase("Default")) continue;

			int resolved = resolveSkinValue(skin, key, base, report);
			// SkinSectionParser's exact state keys (gate HJ): Scope, Reload, Sprint, No_Ammo; a named skin's
			// model is its own Default state, not a Custom_Model_Data key.
			switch (key.toLowerCase(Locale.ROOT)) {
				case "scope" -> skins.put("Scope", resolved);
				case "reload" -> skins.put("Reload", resolved);
				case "sprint" -> skins.put("Sprint", resolved);
				case "no_ammo" -> skins.put("No_Ammo", resolved);
				default -> {
					Map<String, Object> namedEntry = new LinkedHashMap<>();
					namedEntry.put("Default", resolved);
					named.put(key.toLowerCase(Locale.ROOT), namedEntry);
				}
			}
		}

		if (!named.isEmpty()) skins.put("Named", named);
		if (!skins.isEmpty()) {
			// Skins lives at the weapon's root, a sibling of Information, not nested inside it.
			yaml.put("Skins", skins);
		}

		return base;
	}

	private static int resolveSkinValue(ConfigurationSection skin, String key, int base,
	                                    WmImportReport.WeaponEntry report) {
		if (skin.isInt(key)) return skin.getInt(key);
		if (skin.isConfigurationSection(key)) {
			return skin.getInt(key + ".Custom_Model_Data", base);
		}
		String raw = skin.getString(key, "");
		if (raw.trim().toUpperCase(Locale.ROOT).startsWith("ADD")) {
			String[] parts = raw.trim().split("\\s+");
			if (parts.length == 2) {
				try {
					return base + Integer.parseInt(parts[1]);
				} catch (NumberFormatException ignored) {
					report.approximated("Skin." + key + " ('" + raw + "') - unparsable ADD offset, using base");
				}
			}
		}
		return base;
	}

	// -------------------------------------------------------------------------
	// Melee
	// -------------------------------------------------------------------------

	private static void buildAttack(ConfigurationSection body, Map<String, Object> yaml,
	                                WmImportReport.WeaponEntry report) {
		Map<String, Object> attack = new LinkedHashMap<>();
		yaml.put("Attack", attack);

		attack.put("Damage", body.getDouble("Damage.Base_Damage", 4.0));
		attack.put("Range", body.getDouble("Melee.Melee_Range", 2.5));
		attack.put("Cooldown", body.getInt("Melee.Melee_Hit_Delay", 10));
		report.mapped("Melee.Melee_Range/Melee_Hit_Delay, Damage.Base_Damage -> Attack.Range/Cooldown/Damage");
	}

	private static void dropMeleeOnlyKeys(ConfigurationSection body, WmImportReport.WeaponEntry report) {
		if (body.isSet("Damage.Head") || body.isSet("Damage.Backstab") || body.isSet("Damage.Body")) {
			report.dropped("Damage.Head/Backstab/Body bonus damage - MeleeWeaponParser has no hit-zone deltas");
		}
		if (body.isSet("Damage.Armor_Damage")) {
			report.dropped("Damage.Armor_Damage - no Bartizan equivalent for melee");
		}
		if (body.isSet("Melee.Melee_Miss")) {
			report.approximated("Melee.Melee_Miss -> Effects.On_Miss (mechanics only, no separate miss delay)");
		}
	}

	// -------------------------------------------------------------------------
	// Throwable
	// -------------------------------------------------------------------------

	private static void buildThrow(ConfigurationSection body, @Nullable ConfigurationSection shoot,
	                               ConfigurationSection explosion, Map<String, Object> yaml,
	                               WmImportReport.WeaponEntry report) {
		Map<String, Object> throwSection = new LinkedHashMap<>();
		yaml.put("Throw", throwSection);

		boolean isStun = explosion.isSet("Flashbang");
		throwSection.put("Type", isStun ? "stun" : "explosive");

		boolean spawnFuse = explosion.getBoolean("Detonation.Impact_When.Spawn", false);
		int fuseTicks = explosion.getInt("Detonation.Delay_After_Impact", 60);
		if (!spawnFuse) {
			report.approximated("Explosion.Detonation has no Impact_When.Spawn - defaulted Fuse_Time to 60 ticks");
			fuseTicks = 60;
		}
		throwSection.put("Fuse_Time", fuseTicks);

		double radius = explosion.getDouble("Explosion_Type_Data.Radius", -1);
		if (radius < 0) {
			radius = 4.0;
			report.approximated("Explosion.Explosion_Type_Data has no Radius (a Depth/Yield/Angle-only shape) - "
			                     + "defaulted Explosion_Radius to 4.0");
		}
		throwSection.put("Explosion_Radius", radius);
		throwSection.put("Explosion_Damage", body.getInt("Damage.Base_Explosion_Damage", 0));
		throwSection.put("Fire_Ticks", body.getInt("Damage.Fire_Ticks", 0));

		boolean bouncy = body.isSet("Projectile.Bouncy");
		boolean sticky = body.isSet("Projectile.Sticky");
		if (bouncy && sticky) {
			report.approximated("Projectile has both Bouncy and Sticky - Sticky wins (Bartizan forbids both)");
			bouncy = false;
		}
		if (bouncy) {
			throwSection.put("Bounces", true);
			int maxBounces = body.getInt("Projectile.Bouncy.Maximum_Bounce_Amount", 5);
			throwSection.put("Max_Bounces", maxBounces < 0 ? 999 : maxBounces);
			if (maxBounces < 0) report.approximated("Bouncy.Maximum_Bounce_Amount: -1 (unlimited) -> Max_Bounces: 999");
		}
		if (sticky) throwSection.put("Sticky", true);

		throwSection.put("Owner_Immunity", body.getBoolean("Damage.Enable_Owner_Immunity", false));
		if (body.isSet("Damage.Ignore_Teams")) throwSection.put("Ignore_Teams", body.getBoolean("Damage.Ignore_Teams"));
		if (body.isSet("Damage.Armor_Damage")) report.dropped("Damage.Armor_Damage - no Bartizan equivalent for throwables");

		if (isStun) {
			List<String> effects = translateFlashbangEffects(explosion, report);
			throwSection.put("Effects", effects);
		}

		Map<String, Object> explosionOut = new LinkedHashMap<>();
		String shape = explosion.getString("Explosion_Shape", "");
		if ("SPHERE".equalsIgnoreCase(shape)) {
			explosionOut.put("Shape", "sphere");
		} else if (!shape.isEmpty() && !"FLAT".equalsIgnoreCase(shape)) {
			explosionOut.put("Shape", "flat");
			report.approximated("Explosion_Shape: " + shape + " -> flat (no linear-falloff equivalent shape)");
		}

		String exposure = explosion.getString("Explosion_Exposure", "");
		if ("DISTANCE".equalsIgnoreCase(exposure)) {
			explosionOut.put("Exposure", "distance");
		} else if (!exposure.isEmpty()) {
			report.approximated("Explosion_Exposure: " + exposure + " -> defaulted to distance");
		}

		if (body.isSet("Explosion.Block_Damage")) {
			explosionOut.put("Block_Damage", true);
			report.approximated("Explosion.Block_Damage.* (mode/falling-block chance/vanilla Regeneration) -> "
			                     + "flattened to a plain Block_Damage: true flag");
		}

		buildCluster(explosion, explosionOut, report);
		buildAirstrike(explosion, explosionOut, report);

		if (!explosionOut.isEmpty()) throwSection.put("Explosion", explosionOut);

		report.mapped("Throw.Type/Fuse_Time/Explosion_Radius/Explosion_Damage/Fire_Ticks/Bounces/Sticky");
	}

	private static List<String> translateFlashbangEffects(ConfigurationSection explosion,
	                                                       WmImportReport.WeaponEntry report) {
		List<String> effects = new ArrayList<>();
		for (String raw : explosion.getStringList("Flashbang.Mechanics")) {
			WmMechanicsTranslator.ParsedMechanic parsed = WmMechanicsTranslator.parse(raw);
			if (parsed == null || !"potion".equalsIgnoreCase(parsed.type())) continue;

			String potion = value(parsed, "potion");
			String time   = value(parsed, "time");
			String level  = value(parsed, "level");
			if (potion == null || time == null) continue;

			effects.add(potion.toUpperCase(Locale.ROOT) + "-" + time + "-" + (level != null ? level : "0"));
		}
		if (effects.isEmpty()) {
			report.approximated("Explosion.Flashbang.Mechanics had no Potion{} entries - Effects list is empty "
			                     + "(ThrowableWeaponParser requires at least one for Type: stun)");
			effects.add("BLINDNESS-100-0");
		}
		return effects;
	}

	@Nullable
	private static String value(WmMechanicsTranslator.ParsedMechanic parsed, String key) {
		for (Map.Entry<String, String> entry : parsed.args().entrySet()) {
			if (entry.getKey().equalsIgnoreCase(key)) return entry.getValue();
		}
		return null;
	}

	// -------------------------------------------------------------------------
	// Gun
	// -------------------------------------------------------------------------

	@Nullable
	private static AmmoAppend buildGun(ConfigurationSection body, ConfigurationSection shoot,
	                                   @Nullable ConfigurationSection explosion,
	                                   Map<String, ConfigurationSection> projectiles,
	                                   Map<String, ConfigurationSection> ammos, Map<String, Object> yaml,
	                                   WmImportReport.WeaponEntry report) {
		Map<String, Object> shootOut = new LinkedHashMap<>();
		yaml.put("Shoot", shootOut);

		buildTrigger(shoot, shootOut, report);
		buildSelectiveFire(shoot, shootOut, report);

		String projectileRef = body.getString("Projectile", "");
		ConfigurationSection projectile = projectiles.get(projectileRef);
		int pellets = shoot.getInt("Projectiles_Per_Shot", 1);

		String type;
		if (explosion != null) {
			type = "ROCKET";
		} else if (pellets > 1) {
			type = "SPREAD";
		} else {
			type = "BULLET";
		}

		Map<String, Object> projectileOut = new LinkedHashMap<>();
		shootOut.put("Projectile", projectileOut);
		projectileOut.put("Type", type);
		if (pellets > 1) projectileOut.put("Pellets", pellets);

		double wmSpeed = shoot.getDouble("Projectile_Speed", 40.0);
		double bartizanSpeed = Math.round(wmSpeed / 10.0);
		if (bartizanSpeed < 1) bartizanSpeed = 1;
		if (Math.abs(wmSpeed / 10.0 - bartizanSpeed) > 0.01) {
			report.approximated("Projectile_Speed: " + wmSpeed + " / 10 -> rounded to " + (int) bartizanSpeed);
		}
		projectileOut.put("Speed", (int) bartizanSpeed);
		report.mapped("Shoot.Projectile_Speed / 10 -> Shoot.Projectile.Speed");

		buildDistance(body, projectileOut, report);
		projectileOut.put("Particle", true);

		double wmGravity = projectile != null ? projectile.getDouble("Projectile_Settings.Gravity", 0.0) : 0.0;
		// WM's Gravity is a real-world-ish m/s^2 constant (9.8 for a falling SNOWBALL); Bartizan's is a small
		// hand-tuned "how much does this drop past its effective range" coefficient (0.1 on the shipped M4, 0 on
		// the shipped rocket launcher) - there's no principled unit conversion between the two, so this just picks
		// Bartizan's own shipped ballpark instead of carrying WM's raw value over 1:1 (which would be ~10x too high).
		double bartizanGravity = wmGravity > 0 ? 0.1 : 0.0;
		projectileOut.put("Gravity", bartizanGravity);
		if (wmGravity > 0) {
			report.approximated("Projectile_Settings.Gravity: " + wmGravity + " -> defaulted Gravity to 0.1 "
			                     + "(WM's real-world-ish constant has no principled equivalent in Bartizan's "
			                     + "hand-tuned drop coefficient)");
		}

		buildGunDamage(body, explosion, projectileOut, report);
		buildGunModifiers(projectile, yaml, report);

		int consumedAmount = shoot.getInt("Ammo_Consumption", 1);
		projectileOut.put("Consumed_Amount", consumedAmount);

		int perShot = body.getInt("Shoot.Burst.Shots_Per_Burst", 1);
		projectileOut.put("Per_Shot", perShot);

		int cooldown;
		if (body.isSet("Shoot.Burst.Ticks_Between_Each_Shot")) {
			cooldown = body.getInt("Shoot.Burst.Ticks_Between_Each_Shot");
		} else if (shoot.isSet("Fully_Automatic_Shots_Per_Second")) {
			double sps = shoot.getDouble("Fully_Automatic_Shots_Per_Second", 10.0);
			cooldown = Math.max(1, (int) Math.round(20.0 / sps));
			report.approximated("Fully_Automatic_Shots_Per_Second: " + sps + " -> shared Shoot.Projectile.Cooldown "
			                     + "= round(20/sps) = " + cooldown + " (one Cooldown field covers every fire mode)");
		} else if (shoot.isSet("Delay_Between_Shots")) {
			cooldown = shoot.getInt("Delay_Between_Shots");
		} else {
			cooldown = 5;
		}
		projectileOut.put("Cooldown", cooldown);

		Map<String, Object> weaponConsumed = new LinkedHashMap<>();
		weaponConsumed.put("Consume_On_Shot", 0);
		weaponConsumed.put("Time", -1);
		shootOut.put("Weapon_Consumed", weaponConsumed);

		buildRecoilAndSpread(shoot, shootOut, report);

		AmmoAppend ammo = buildReloadAndAmmo(body, ammos, yaml, report);

		buildScope(body, yaml, report);

		return ammo;
	}

	private static void buildTrigger(ConfigurationSection shoot, Map<String, Object> shootOut,
	                                 WmImportReport.WeaponEntry report) {
		String main = shoot.getString("Trigger.Main_Hand", "RIGHT_CLICK");
		if ("RIGHT_CLICK".equalsIgnoreCase(main)) {
			shootOut.put("Trigger", "right_click");
		} else if ("LEFT_CLICK".equalsIgnoreCase(main)) {
			shootOut.put("Trigger", "left_click");
		} else {
			shootOut.put("Trigger", "right_click");
			report.approximated("Shoot.Trigger.Main_Hand: " + main + " -> defaulted to right_click");
		}

		Map<String, Object> circumstance = new LinkedHashMap<>();
		putCircumstance(shoot, "Swimming", circumstance, report);
		putCircumstance(shoot, "Sprinting", circumstance, report);
		if (shoot.isSet("Trigger.Circumstance.Dual_Wielding")) {
			report.dropped("Shoot.Trigger.Circumstance.Dual_Wielding - no Bartizan equivalent");
		}
		if (!circumstance.isEmpty()) shootOut.put("Circumstance", circumstance);
	}

	/** WM's {@code ALLOW/DENY/REQUIRE} -> Bartizan's {@code Rule} ({@code deny}/{@code required}); {@code ALLOW} is
	 * omitted entirely since it's already the unset default. */
	private static void putCircumstance(ConfigurationSection shoot, String key, Map<String, Object> circumstance,
	                                     WmImportReport.WeaponEntry report) {
		String raw = shoot.getString("Trigger.Circumstance." + key, "");
		if (raw.isEmpty()) return;

		switch (raw.toUpperCase(Locale.ROOT)) {
			case "ALLOW" -> { /* already the default - omit */ }
			case "DENY" -> circumstance.put(key, "deny");
			case "REQUIRE" -> circumstance.put(key, "required");
			default -> report.dropped("Shoot.Trigger.Circumstance." + key + ": '" + raw + "' - unrecognised value");
		}
	}

	private static void buildSelectiveFire(ConfigurationSection shoot, Map<String, Object> shootOut,
	                                       WmImportReport.WeaponEntry report) {
		boolean hasBurst = shoot.isSet("Burst");
		boolean hasAuto  = shoot.isSet("Fully_Automatic_Shots_Per_Second");

		List<String> allowed = new ArrayList<>();
		allowed.add("single");
		if (hasBurst) allowed.add("burst");
		if (hasAuto) allowed.add("auto");

		String startMode = shoot.getString("Selective_Fire.Default", "");
		if (startMode.isEmpty()) {
			// Selective_Fire.Default isn't a real WM key - Shoot.Selective_Fire (when present at all) describes the
			// mode-switch TRIGGER, not a starting mode (see FN_FAL.yml: Trigger/Mechanics, no Default). A missing
			// selective-fire NBT tag reads as 0 = SINGLE in WM, so a Selective_Fire section with no Default present
			// starts single too - only a weapon with NO Selective_Fire section at all (so WM fires in its one
			// configured mode) falls back to whichever of auto/burst/single that one mode is.
			startMode = shoot.isConfigurationSection("Selective_Fire") ? "single"
			                                                          : (hasAuto ? "auto" : (hasBurst ? "burst" : "single"));
		}
		String normalized = startMode.toLowerCase(Locale.ROOT);
		if (!allowed.contains(normalized)) allowed.add(normalized);

		shootOut.put("Selective_Fire", normalized);
		shootOut.put("Allowed_Modes", allowed);
		report.approximated("Shoot.Selective_Fire/Burst/Fully_Automatic_Shots_Per_Second -> Allowed_Modes inferred "
		                     + "from which blocks are present - WM has no explicit Modes: list in this version");
	}

	/** {@code Shoot.Projectile.Distance} has no direct WM key - derived from the last {@code Damage.Dropoff} range
	 * (each entry is {@code "<range> <damage delta>"}, ascending) when present, else left at Bartizan's default. */
	private static void buildDistance(ConfigurationSection body, Map<String, Object> projectileOut,
	                                  WmImportReport.WeaponEntry report) {
		List<String> dropoff = body.getStringList("Damage.Dropoff");
		if (!dropoff.isEmpty()) {
			String[] lastEntry = dropoff.get(dropoff.size() - 1).trim().split("\\s+");
			try {
				int distance = (int) Math.round(Double.parseDouble(lastEntry[0]));
				projectileOut.put("Distance", distance);
				report.approximated("Shoot.Projectile.Distance: no WM range cap found - derived " + distance
				                     + " from the last Damage.Dropoff range");
				return;
			} catch (NumberFormatException ignored) {
				// falls through to the plain default below.
			}
		}

		projectileOut.put("Distance", 100);
		report.approximated("no WM range cap found - defaulted Shoot.Projectile.Distance to 100");
	}

	private static void buildGunDamage(ConfigurationSection body, @Nullable ConfigurationSection explosion,
	                                   Map<String, Object> projectileOut, WmImportReport.WeaponEntry report) {
		Map<String, Object> damage = new LinkedHashMap<>();
		projectileOut.put("Damage", damage);

		damage.put("Base", (int) Math.round(body.getDouble("Damage.Base_Damage", 5.0)));
		damage.put("Explosion_Damage", body.getInt("Damage.Base_Explosion_Damage", 0));
		damage.put("Fire_Ticks", body.getInt("Damage.Fire_Ticks", 0));
		if (body.isSet("Damage.Head.Bonus_Damage")) damage.put("Head", body.getInt("Damage.Head.Bonus_Damage"));
		if (body.isSet("Damage.Body.Bonus_Damage")) damage.put("Body", body.getDouble("Damage.Body.Bonus_Damage"));
		if (body.isSet("Damage.Arms.Bonus_Damage")) damage.put("Arms", body.getDouble("Damage.Arms.Bonus_Damage"));
		if (body.isSet("Damage.Legs.Bonus_Damage")) damage.put("Legs", body.getDouble("Damage.Legs.Bonus_Damage"));
		if (body.isSet("Damage.Feet.Bonus_Damage")) damage.put("Feet", body.getDouble("Damage.Feet.Bonus_Damage"));
		if (body.isSet("Damage.Backstab.Bonus_Damage")) damage.put("Back", body.getDouble("Damage.Backstab.Bonus_Damage"));
		if (body.isSet("Damage.Armor_Damage")) damage.put("Armor_Damage", body.getInt("Damage.Armor_Damage"));
		if (body.isSet("Damage.Enable_Owner_Immunity")) {
			damage.put("Owner_Immunity", body.getBoolean("Damage.Enable_Owner_Immunity"));
		}
		if (body.isSet("Damage.Ignore_Teams")) damage.put("Ignore_Teams", body.getBoolean("Damage.Ignore_Teams"));

		List<String> dropoff = body.getStringList("Damage.Dropoff");
		if (!dropoff.isEmpty()) damage.put("Dropoff", dropoff);

		report.mapped("Damage.Base_Damage/Fire_Ticks/Head/Backstab/Armor_Damage/Dropoff -> Shoot.Projectile.Damage.*");

		if (explosion != null) {
			double radius = explosion.getDouble("Explosion_Type_Data.Radius", -1);
			if (radius < 0) {
				radius = 4.0;
				report.approximated("Explosion.Explosion_Type_Data has no Radius - defaulted Explosion_Radius to 4.0");
			}
			damage.put("Explosion_Radius", radius);

			Map<String, Object> explosionOut = new LinkedHashMap<>();
			String shape = explosion.getString("Explosion_Shape", "SPHERE");
			// Guns only ever ship the linear-falloff sphere (GunWeaponParser's own legacy default) - unlike the
			// throwable path, there's no "flat" fallback worth picking for a rocket, so every shape value lands
			// on sphere and only a non-sphere WM value earns a report line.
			explosionOut.put("Shape", "sphere");
			if (!"SPHERE".equalsIgnoreCase(shape)) {
				report.approximated("Explosion_Shape: " + shape + " -> sphere (gun rockets default to linear falloff)");
			}
			String exposure = explosion.getString("Explosion_Exposure", "");
			if ("DISTANCE".equalsIgnoreCase(exposure)) explosionOut.put("Exposure", "distance");

			if (explosion.isSet("Block_Damage")) {
				explosionOut.put("Block_Damage", true);
				report.approximated("Explosion.Block_Damage.* flattened to a plain Block_Damage: true flag");
			}

			buildCluster(explosion, explosionOut, report);
			buildAirstrike(explosion, explosionOut, report);

			if (!explosionOut.isEmpty()) projectileOut.put("Explosion", explosionOut);
		}
	}

	private static void buildCluster(ConfigurationSection explosion, Map<String, Object> explosionOut,
	                                 WmImportReport.WeaponEntry report) {
		if (!explosion.isSet("Cluster_Bomb")) return;

		Map<String, Object> cluster = new LinkedHashMap<>();
		cluster.put("Count", explosion.getInt("Cluster_Bomb.Number_Of_Bombs", 3));
		cluster.put("Speed", Math.round(explosion.getDouble("Cluster_Bomb.Projectile_Speed", 10.0) / 10.0));
		cluster.put("Delay_Ticks", explosion.getInt("Cluster_Bomb.Detonation.Delay_After_Impact", 0));
		explosionOut.put("Cluster", cluster);
		report.approximated("Explosion.Cluster_Bomb.* -> Explosion.Cluster.* (approximate field-for-field mapping)");
	}

	private static void buildAirstrike(ConfigurationSection explosion, Map<String, Object> explosionOut,
	                                   WmImportReport.WeaponEntry report) {
		if (!explosion.isSet("Airstrike")) return;

		int min = explosion.getInt("Airstrike.Minimum_Bombs", 3);
		int max = explosion.getInt("Airstrike.Maximum_Bombs", min);
		Map<String, Object> airstrike = new LinkedHashMap<>();
		airstrike.put("Count", (min + max) / 2);
		airstrike.put("Height", explosion.getDouble("Airstrike.Height", 10.0));
		airstrike.put("Radius", explosion.getDouble("Airstrike.Maximum_Distance_From_Center",
		                                            explosion.getDouble("Explosion_Type_Data.Radius", 10.0)));
		airstrike.put("Delay_Ticks", explosion.getInt("Airstrike.Delay_Between_Layers", 0));
		explosionOut.put("Airstrike", airstrike);
		report.approximated("Explosion.Airstrike.{Minimum_Bombs,Maximum_Bombs,Layers,Distance_Between_Bombs} -> "
		                     + "Explosion.Airstrike.{Count,Height,Radius,Delay_Ticks} (multi-layer waves collapsed "
		                     + "into one, Layers dropped)");
	}

	private static void buildGunModifiers(@Nullable ConfigurationSection projectile, Map<String, Object> yaml,
	                                      WmImportReport.WeaponEntry report) {
		if (projectile == null) return;

		Map<String, Object> modifiers = new LinkedHashMap<>();

		if (projectile.isSet("Through.Maximum_Through_Amount")) {
			int through = projectile.getInt("Through.Maximum_Through_Amount", 0);
			boolean throughEntities = projectile.isSet("Through.Entities");
			modifiers.put("Penetration", through + "-" + (throughEntities ? through : 0) + "-0");
			report.approximated("Through.Maximum_Through_Amount/Entities -> Modifiers.Penetration "
			                     + "(blocks/entities share one WM value, damage-reduction-per-hit unknown, left 0)");
		}

		if (!modifiers.isEmpty()) yaml.put("Modifiers", modifiers);
	}

	/** Synthesized {@code Starting_Spread} for a {@code Spread_Image}/no-spread shotgun - matches the shipped
	 * {@code shotgun.yml}'s own value, since without it a SPREAD weapon's pellets all travel one ray. */
	private static final double SYNTHETIC_PELLET_SPREAD = 0.25;

	private static void buildRecoilAndSpread(ConfigurationSection shoot, Map<String, Object> shootOut,
	                                         WmImportReport.WeaponEntry report) {
		int pellets = shoot.getInt("Projectiles_Per_Shot", 1);

		if (shoot.isSet("Spread.Base_Spread")) {
			Map<String, Object> spread = new LinkedHashMap<>();
			spread.put("Starting_Spread", shoot.getDouble("Spread.Base_Spread", 0.0) / 100.0);
			shootOut.put("Spread", spread);
			report.mapped("Shoot.Spread.Base_Spread -> Shoot.Spread.Starting_Spread (WM's 0-100 scale / 100)");
		} else if (pellets > 1) {
			Map<String, Object> spread = new LinkedHashMap<>();
			spread.put("Starting_Spread", SYNTHETIC_PELLET_SPREAD);
			shootOut.put("Spread", spread);
			report.approximated("Shoot.Spread.Base_Spread absent on a " + pellets + "-pellet weapon (likely a "
			                     + "Spread.Spread_Image instead) - synthesized Starting_Spread: "
			                     + SYNTHETIC_PELLET_SPREAD + " (the shipped shotgun.yml value) so pellets actually "
			                     + "spread instead of travelling one ray");
		} else if (shoot.isSet("Spread.Spread_Image")) {
			report.dropped("Shoot.Spread.Spread_Image - spread images aren't supported, Starting_Spread left "
			                + "at Bartizan's default");
		}

		if (shoot.isSet("Recoil.Mean_X")) {
			Map<String, Object> recoil = new LinkedHashMap<>();
			Map<String, Object> random = new LinkedHashMap<>();
			random.put("Mean_X", shoot.getDouble("Recoil.Mean_X", 0.0));
			random.put("Mean_Y", shoot.getDouble("Recoil.Mean_Y", 0.0));
			random.put("Variance_X", shoot.getDouble("Recoil.Variance_X", 0.0));
			random.put("Variance_Y", shoot.getDouble("Recoil.Variance_Y", 0.0));
			recoil.put("Random", random);
			recoil.put("Amount", 0.05);
			recoil.put("Push", 0.05);
			recoil.put("Power_Up", 0.0002);
			shootOut.put("Recoil", recoil);
			report.mapped("Shoot.Recoil.Mean_X/Y,Variance_X/Y -> Shoot.Recoil.Random.*");
			if (shoot.isSet("Recoil.Speed") || shoot.isSet("Recoil.Damping")) {
				report.dropped("Shoot.Recoil.Speed/Damping/Damping_Recovery/Smoothing/Max_Accumulation - WM's "
				                + "spring-back recoil model has no Bartizan equivalent");
			}
		}
	}

	@Nullable
	private static AmmoAppend buildReloadAndAmmo(ConfigurationSection body, Map<String, ConfigurationSection> ammos,
	                                             Map<String, Object> yaml, WmImportReport.WeaponEntry report) {
		if (!body.isSet("Reload")) return null;

		Map<String, Object> reload = new LinkedHashMap<>();
		yaml.put("Reload", reload);
		reload.put("Cooldown", body.getInt("Reload.Reload_Duration", 40));
		reload.put("Type", "instant");
		report.mapped("Reload.Reload_Duration -> Reload.Cooldown, Reload.Trigger (drop-item gesture) has no "
		              + "Bartizan equivalent - reload stays command/out-of-ammo driven");

		Map<String, Object> ammunition = new LinkedHashMap<>();
		yaml.put("Ammunition", ammunition);
		int capacity = body.getInt("Reload.Magazine_Size", 30);
		ammunition.put("Capacity", capacity);
		ammunition.put("Restore", capacity);
		ammunition.put("Consume", 1);

		if (body.isSet("Reload.Ammo")) {
			String ref = body.getString("Reload.Ammo", "");
			String ammoId = "wm_" + sanitizeKey(ref);
			ammunition.put("Ammo_Type", ammoId);
			report.approximated("Reload.Ammo: '" + ref + "' -> new ammunition.yml entry '" + ammoId + "' "
			                     + "(magazine semantics dropped)");

			ConfigurationSection ammoSection = ammos.get(ref);
			String material = ammoSection != null ? ammoSection.getString("Item_Ammo.Bullet_Item.Type", "IRON_NUGGET")
			                                      : "IRON_NUGGET";
			String rawName  = ammoSection != null ? ammoSection.getString("Item_Ammo.Bullet_Item.Name", ref) : ref;
			return new AmmoAppend(ammoId, material, WmColorTranslator.translate(rawName), ref);
		}

		ammunition.put("Ammo_Type", "none");
		report.approximated("no Reload.Ammo reference found -> Ammo_Type: none");
		return null;
	}

	private static void buildScope(ConfigurationSection body, Map<String, Object> yaml,
	                               WmImportReport.WeaponEntry report) {
		if (!body.isSet("Scope")) return;

		Map<String, Object> scope = new LinkedHashMap<>();
		yaml.put("Scope", scope);
		double zoomAmount = body.getDouble("Scope.Zoom_Amount", 1.5);
		int roundedZoom = Math.max(1, (int) Math.round(zoomAmount));
		scope.put("Zoom_Amount", roundedZoom);
		if (Math.abs(zoomAmount - roundedZoom) > 0.01) {
			report.approximated("Scope.Zoom_Amount: " + zoomAmount + " -> rounded to " + roundedZoom
			                     + " (Scope.Zoom_Amount/Level are ints in Bartizan)");
		}
		scope.put("Night_Vision", body.getBoolean("Scope.Night_Vision", false));
		scope.put("Shoot_Delay_After_Scope", body.getInt("Scope.Shoot_Delay_After_Scope", 0));

		if (body.isSet("Scope.Zoom_Stacking.Stacks")) {
			List<Integer> stacks = body.getIntegerList("Scope.Zoom_Stacking.Stacks");
			Map<String, Object> zoomStacking = new LinkedHashMap<>();
			zoomStacking.put("Maximum_Stacks", Math.max(1, stacks.size()));
			zoomStacking.put("Increase_Per_Stack", 1);
			scope.put("Zoom_Stacking", zoomStacking);
			report.approximated("Scope.Zoom_Stacking.Stacks (a list of absolute zoom levels) -> "
			                     + "Zoom_Stacking.Maximum_Stacks/Increase_Per_Stack (a linear step model)");
		}

		report.mapped("Scope.Zoom_Amount/Night_Vision/Shoot_Delay_After_Scope -> Scope.*");
	}

	// -------------------------------------------------------------------------
	// HUD / Effects / Skins side channel
	// -------------------------------------------------------------------------

	private static void buildHud(ConfigurationSection body, Map<String, Object> yaml) {
		String message = body.getString("Info.Weapon_Info_Display.Action_Bar.Message", null);
		if (message == null) return;

		String withPlaceholders = message
				.replace("<ammo_left>", "%ammo_left%")
				.replace("<firearm_state>", "%firearm_state%")
				.replace("<reload>", "%reload%")
				.replace("<selective_fire_state>", "%selective_fire%")
				.replace("<selective_fire>", "%selective_fire%");

		Map<String, Object> hud = new LinkedHashMap<>();
		hud.put("Action_Bar", WmColorTranslator.translate(withPlaceholders));
		yaml.put("HUD", hud);
	}

	/** Hooks WM's {@code *_Mechanics}/{@code Mechanics} lists this importer knows about onto their Bartizan hook. */
	private static void buildEffects(ConfigurationSection body, Map<String, Object> yaml, String category,
	                                 WmImportReport.WeaponEntry report, int[] commentSeq) {
		Map<String, Object> effects = new LinkedHashMap<>();

		addHook(body, "Weapon_Get_Mechanics", effects, "On_Equip", report, commentSeq);
		if ("gun".equals(category)) {
			addHook(body, "Shoot.Mechanics", effects, "On_Shoot", report, commentSeq);
			addHook(body, "Damage.Mechanics", effects, "On_Hit", report, commentSeq);
			addHook(body, "Damage.Victim_Mechanics", effects, "On_Hit", report, commentSeq);
			addHook(body, "Damage.Head.Victim_Mechanics", effects, "On_Headshot", report, commentSeq);
			addHook(body, "Damage.Backstab.Victim_Mechanics", effects, "On_Back", report, commentSeq);
			addHook(body, "Scope.Mechanics", effects, "On_Scope_In", report, commentSeq);
			addHook(body, "Scope.Zoom_Off.Mechanics", effects, "On_Scope_Out", report, commentSeq);
			addHook(body, "Reload.Start_Mechanics", effects, "On_Reload_Start", report, commentSeq);
			addHook(body, "Reload.Finish_Mechanics", effects, "On_Reload_End", report, commentSeq);
			addHook(body, "Explosion.Mechanics", effects, "On_Explode", report, commentSeq);
			skipKnownUnmapped(body, report, "Firearm_Action.Open.Mechanics", "Firearm_Action.Close.Mechanics",
			                   "Selective_Fire.Mechanics", "Cosmetics.Splash_Mechanics");
		} else if ("throwable".equals(category)) {
			addHook(body, "Shoot.Mechanics", effects, "On_Shoot", report, commentSeq);
			addHook(body, "Damage.Mechanics", effects, "On_Hit", report, commentSeq);
			addHook(body, "Explosion.Mechanics", effects, "On_Explode", report, commentSeq);
			skipKnownUnmapped(body, report, "Cosmetics.Splash_Mechanics", "Projectile.Mechanics");
		} else {
			addHook(body, "Damage.Victim_Mechanics", effects, "On_Hit", report, commentSeq);
			addHook(body, "Damage.Head.Victim_Mechanics", effects, "On_Headshot", report, commentSeq);
			addHook(body, "Damage.Backstab.Victim_Mechanics", effects, "On_Back", report, commentSeq);
			addHook(body, "Melee.Melee_Miss.Mechanics", effects, "On_Miss", report, commentSeq);
		}

		boolean hasRealHook = effects.keySet().stream().anyMatch(key -> !key.startsWith("#unmapped_"));
		if (hasRealHook) yaml.put("Effects", effects);
	}

	private static void skipKnownUnmapped(ConfigurationSection body, WmImportReport.WeaponEntry report,
	                                      String... paths) {
		for (String path : paths) {
			if (body.isSet(path)) {
				report.dropped(path + " - WMC/cosmetics-only mechanics list, not translated");
			}
		}
	}

	private static void addHook(ConfigurationSection body, String wmPath, Map<String, Object> effects,
	                            String hook, WmImportReport.WeaponEntry report, int[] commentSeq) {
		List<String> raw = body.getStringList(wmPath);
		if (raw.isEmpty() && body.isConfigurationSection(wmPath)) {
			raw = flattenMechanicsMap(body.getConfigurationSection(wmPath), wmPath, report);
		}
		if (raw.isEmpty()) return;

		List<Object> translated = new ArrayList<>();
		for (String entry : raw) {
			WmMechanicsTranslator.ParsedMechanic parsed = WmMechanicsTranslator.parse(entry);
			WmMechanicsTranslator.TranslatedEffect result = parsed != null ? WmMechanicsTranslator.translate(parsed)
			                                                              : null;
			if (result == null) {
				report.dropped(wmPath + ": '" + entry + "' - mechanic not supported");
				effects.put("#unmapped_" + (commentSeq[0]++), "unmapped: " + wmPath + " '" + entry + "'");
				continue;
			}

			if ("sound".equals(result.type()) || "custom_sound".equals(result.type())) {
				if (value(parsed, "noise") != null) {
					report.approximated(wmPath + ": '" + entry + "' - noise dropped, no pitch jitter");
				}
				if (value(parsed, "delayBeforePlay") != null) {
					report.dropped(wmPath + ": '" + entry + "' - delayBeforePlay dropped, no per-effect delay");
				}
			}
			// Push/Leap{speed=..., height=...} both translate their WM arg onto the single Bartizan Strength key
			// (WmMechanicsTranslator's "push","leap" case) - PushHookEffect has no separate vertical component, so
			// the second copy() call silently overwrites the first. Reported here, not redesigned: there's no
			// evidence this combination is common enough to justify a model change.
			if ("push".equals(result.type()) && value(parsed, "speed") != null && value(parsed, "height") != null) {
				report.approximated(wmPath + ": '" + entry + "' - Push/Leap has both speed and height; only one "
				                     + "is kept as Strength");
			}
			// An absent Command{} console key now defaults to As: player, not console (least-privilege - see
			// WmMechanicsTranslator) - reported like every other place this importer substitutes its own default
			// for a value WM's own config didn't set.
			if ("command".equals(result.type()) && value(parsed, "console") == null) {
				report.approximated(wmPath + ": '" + entry + "' - no explicit console flag, defaulted Command's "
				                     + "As to player (least-privilege)");
			}

			Map<String, Object> spec = new LinkedHashMap<>();
			spec.put("Type", capitalizeType(result.type()));
			spec.putAll(result.args());
			translated.add(spec);
		}

		if (!translated.isEmpty()) {
			@SuppressWarnings("unchecked")
			List<Object> merged = (List<Object>) effects.computeIfAbsent(hook, key -> new ArrayList<>());
			merged.addAll(translated);
			report.mapped(wmPath + " -> Effects." + hook);
		}
	}

	/** Flattens a {@code *_Mechanics} nested-map shape (e.g. {@code Victim_Mechanics: { Sounds: [...] } }) into one
	 * flat list, reporting each child - never dropping a present key silently. */
	private static List<String> flattenMechanicsMap(ConfigurationSection section, String wmPath,
	                                                 WmImportReport.WeaponEntry report) {
		List<String> flattened = new ArrayList<>();
		for (String key : section.getKeys(false)) {
			String childPath = wmPath + "." + key;
			if (section.isList(key)) {
				flattened.addAll(section.getStringList(key));
				report.mapped(childPath + " (nested mechanics list) -> Effects.*");
			} else {
				report.dropped(childPath + " - nested mechanics entry is not a list, not translated");
			}
		}
		return flattened;
	}

	private static String capitalizeType(String lowercaseType) {
		String[] parts = lowercaseType.split("_");
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < parts.length; i++) {
			if (i > 0) out.append('_');
			out.append(parts[i].substring(0, 1).toUpperCase(Locale.ROOT)).append(parts[i].substring(1));
		}
		return out.toString();
	}

}
