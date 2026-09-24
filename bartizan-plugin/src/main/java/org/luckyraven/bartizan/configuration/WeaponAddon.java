package org.luckyraven.bartizan.configuration;

import com.cryptomorin.xseries.XAttribute;
import com.cryptomorin.xseries.XMaterial;
import lombok.CustomLog;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.InvalidConfigurationException;
import org.jetbrains.annotations.Nullable;
import org.luckyraven.keystone.nms.NmsVersion;
import org.luckyraven.keystone.util.Placeholder;
import org.luckyraven.keystone.sound.SoundEffect;
import org.luckyraven.keystone.persistence.FileHandler;
import org.luckyraven.keystone.persistence.config.ConfigIssue;
import org.luckyraven.keystone.persistence.config.ConfigNode;
import org.luckyraven.keystone.persistence.config.ConfigReport;
import org.luckyraven.keystone.persistence.config.FileHandlerReader;
import org.luckyraven.keystone.persistence.config.MappingNode;
import org.luckyraven.keystone.persistence.config.NodeReader;
import org.luckyraven.keystone.persistence.config.Severity;
import org.luckyraven.bartizan.api.weapon.BeamWeapon;
import org.luckyraven.bartizan.api.weapon.BiologicalWeapon;
import org.luckyraven.bartizan.api.weapon.SelectiveFire;
import org.luckyraven.bartizan.api.weapon.Weapon;
import org.luckyraven.bartizan.ammo.AmmunitionManager;
import org.luckyraven.bartizan.configuration.parser.*;
import org.luckyraven.bartizan.api.weapon.dto.*;
import org.luckyraven.bartizan.api.weapon.WeaponType;
import org.luckyraven.bartizan.raytrace.WeaponMuzzle;

import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

@CustomLog
public class WeaponAddon {

	/**
	 * The only {@link ConfigReport} {@link Severity#ERROR} codes that fail a weapon's whole load —
	 * {@link org.luckyraven.bartizan.configuration.parser.AmmunitionSectionParser}'s unresolvable-ammo errors.
	 * Every other {@code config.*} ERROR (a {@code NodeReader.required()} marker for an absent-but-optional key,
	 * a {@code min()}/{@code max()} range violation, or a type mismatch) is clamped/defaulted by the reader and
	 * logged — it must not turn a previously-loading file into a load failure.
	 */
	private static final Set<String> FATAL_AMMO_CODES = Set.of("ammo.unknown_type", "ammo.both_ammo_type_and_types");

	private final Map<String, Weapon> weapons;

	/**
	 * Placeholder resolver handed over by the plugin bootstrap at init time. Each parsed {@link Weapon} has this
	 * injected after construction so its {@code buildItem} / {@code updateWeaponData} calls resolve configured
	 * PlaceholderAPI tokens in the display name and lore.
	 */
	@Nullable
	private final Placeholder placeholder;

	public WeaponAddon(@Nullable Placeholder placeholder) {
		this.weapons     = new HashMap<>();
		this.placeholder = placeholder;
	}

	public ConfigReport registerWeapon(AmmunitionManager ammunitionManager, FileHandler fileHandler) throws
			InvalidConfigurationException {
		String       fileName = fileHandler.getName().toLowerCase();
		ConfigReport report   = new ConfigReport();
		NodeReader   root     = FileHandlerReader.read(fileHandler, report);

		String configVersion = root.get("Config_Version").asString().orNull();
		if (configVersion != null) {
			return report;
		}

		/* information section */
		MappingNode informationSection = root.get("Information").asMapping().required().orNull();
		if (informationSection == null) {
			throw new InvalidConfigurationException("Information section not found for '" + fileName + "'");
		}

		NodeReader information = NodeReader.of(informationSection, report);

		String displayName = information.get("Name").asString().required().orNull();

		String     categoryString = information.get("Category").asString().required().orNull();
		WeaponType category       = WeaponType.getType(Objects.requireNonNull(categoryString));

		String              materialString    = information.get("Material").asString().required().orNull();
		Optional<XMaterial> xMaterialOptional = XMaterial.matchXMaterial(Objects.requireNonNull(materialString));
		Material            material;
		if (xMaterialOptional.isPresent()) material = xMaterialOptional.get().get();
		else material = XMaterial.FEATHER.get();

		int customModelData = information.get("Custom_Model_Data").asInt().min(0).orDefault(0);

		MappingNode durabilitySection = information.get("Durability").asMapping().required().orNull();
		short       durability        = 0;
		short       onShotDurability  = 0;
		if (durabilitySection != null) {
			NodeReader dur = NodeReader.of(durabilitySection, report);
			durability = (short) dur.get("Base").asInt().min(0).required().orDefault(0);

			MappingNode changeSection = dur.get("Change").asMapping().orNull();
			if (changeSection != null) {
				onShotDurability = (short) NodeReader.of(changeSection, report)
				                                     .get("On_Shot").asInt().orDefault(0);
			}
		}

		List<String> lore         = information.get("Lore").asList().ofStrings().orEmpty();
		boolean      dropHologram = information.get("Drop_Hologram").asBool().orDefault(false);

		List<String> deathMessages = root.get("Death_Messages").asList().ofStrings().orEmpty();
		if (deathMessages.isEmpty()) deathMessages = null;

		WeaponBaseData base = new WeaponBaseData(fileName, displayName, category, material, customModelData, durability,
		                                         lore, dropHologram, deathMessages);

		/* dispatch to type-specific parser */
		MappingNode shootSection = resolveShootSection(root);
		NodeReader  shoot        = shootSection != null ? NodeReader.of(shootSection, report) : null;

		Weapon weapon = switch (category) {
			case GUN, OTHER -> new GunWeaponParser(ammunitionManager).parse(root, shoot, report, base);
			case THROWABLE -> new ThrowableWeaponParser(ammunitionManager).parse(root, shoot, report, base);
			case MELEE -> new MeleeWeaponParser(ammunitionManager).parse(root, shoot, report, base);
			case INCENDIARY -> new IncendiaryWeaponParser(ammunitionManager).parse(root, shoot, report, base);
			case BIOLOGICAL -> new BiologicalWeaponParser(ammunitionManager).parse(root, shoot, report, base);
			case BEAM -> new BeamWeaponParser(ammunitionManager).parse(root, shoot, report, base);
		};

		/* apply shared post-parse sections */
		weapon.setDurabilityData(new DurabilityData());
		weapon.setSoundData(new SoundData());
		weapon.getDurabilityData().setOnShot(onShotDurability);
		applyHandling(information, shoot, weapon, report);
		applyShootSounds(shoot, weapon, report);
		applyReloadSoundsAndActionBar(root, weapon, report);
		applyOptionalShootConfig(shoot, weapon, report);
		applyScope(root, materialString, weapon, report);
		ModifiersSectionParser.apply(root, weapon, report);
		if (weapon instanceof BeamWeapon beamWeapon) {
			BeamWeaponParser.lowerPierce(beamWeapon);
		}
		applyEffects(root, shoot, weapon, report);
		applyHud(root, weapon, report);
		applySkins(root, information, weapon, report, customModelData);

		// hand the placeholder resolver to the weapon instance so its rendering path can resolve
		// configured PlaceholderAPI tokens
		weapon.setPlaceholder(placeholder);

		if (!report.isEmpty()) report.log(log);

		// Only the ammo codes in FATAL_AMMO_CODES fail the whole weapon's load — uniformly across all six
		// categories, not just guns. Every other ConfigReport ERROR (NodeReader.required() on an absent-but-
		// optional key, a clamped range, a defaulted type mismatch) stays a logged warning-equivalent so a file
		// that loaded before keeps loading.
		List<ConfigIssue> fatalIssues = fatalIssues(report);
		if (!fatalIssues.isEmpty()) {
			String errors = fatalIssues.stream().map(ConfigIssue::render).collect(Collectors.joining("; "));
			throw new InvalidConfigurationException("weapon '" + fileName + "' has configuration errors: " + errors);
		}

		weapons.put(fileName, weapon);
		return report;
	}

	/**
	 * The subset of {@code report}'s issues that must fail the weapon's load — see {@link #FATAL_AMMO_CODES}.
	 * Extracted so this filter is testable without bootstrapping a full {@link #registerWeapon} call.
	 */
	static List<ConfigIssue> fatalIssues(ConfigReport report) {
		return report.issues().stream()
		             .filter(issue -> issue.severity() == Severity.ERROR && FATAL_AMMO_CODES.contains(issue.code()))
		             .toList();
	}

	@Nullable
	public Weapon getWeapon(String key) {
		return weapons.get(key);
	}

	/**
	 * Every parsed catalogue entry. These are shared templates — copy before handing one out.
	 */
	public Collection<Weapon> getWeapons() {
		return Collections.unmodifiableCollection(weapons.values());
	}

	public Set<String> getWeaponKeys() {
		return weapons.keySet();
	}

	public void clear() {
		weapons.clear();
	}

	public int size() {
		return weapons.size();
	}

	// -------------------------------------------------------------------------
	// Shoot section resolution
	// -------------------------------------------------------------------------

	/**
	 * Resolves the shoot section for non-GUN weapon types. Priority: {@code Shoot:} → {@code Attack:} → {@code Throw:}
	 * → legacy {@code Melee:}/{@code Throwable:}. Returns {@code null} if none exist.
	 */
	@Nullable
	private MappingNode resolveShootSection(NodeReader root) {
		MappingNode section = root.get("Shoot").asMapping().orNull();
		if (section != null) return section;
		section = root.get("Attack").asMapping().orNull();
		if (section != null) return section;
		section = root.get("Throw").asMapping().orNull();
		if (section != null) return section;
		section = root.get("Melee").asMapping().orNull();
		if (section != null) return section;
		return root.get("Throwable").asMapping().orNull();
	}

	// -------------------------------------------------------------------------
	// Shared post-parse helpers
	// -------------------------------------------------------------------------

	private void applyOptionalShootConfig(@Nullable NodeReader shoot, Weapon weapon, ConfigReport report) {
		if (shoot == null) return;

		String selectiveFireString = shoot.get("Selective_Fire").asString().orNull();
		if (selectiveFireString != null) {
			// BZ-CF-14: an unrecognised Selective_Fire value used to silently resolve to AUTO via
			// SelectiveFire.getType's default branch, with no warning anywhere — same fix as
			// SelectiveFireSectionParser.parse, kept under the same "selectiveFire.unknown_mode" code.
			Optional<SelectiveFire> selectiveFire = SelectiveFire.fromKey(selectiveFireString);
			weapon.setCurrentSelectiveFire(selectiveFire.orElse(SelectiveFire.AUTO));
			if (selectiveFire.isEmpty()) {
				ConfigNode node = shoot.get("Selective_Fire").node();
				report.add(Severity.WARNING, node != null ? node.location() : shoot.mapping().location(),
				           "Shoot.Selective_Fire",
				           "unrecognised value '" + selectiveFireString + "' for Shoot.Selective_Fire",
				           "selectiveFire.unknown_mode");
			}
		}

		MappingNode weaponConsumedSection = shoot.get("Weapon_Consumed").asMapping().orNull();
		if (weaponConsumedSection != null) {
			int consumeOnTime = NodeReader.of(weaponConsumedSection, report)
			                              .get("Time").asInt().orDefault(-1);
			if (consumeOnTime == 0) consumeOnTime = -1;
			weapon.getDurabilityData().setConsumeOnTime(consumeOnTime);
		}

		MappingNode muzzleOffsetSection = shoot.get("Muzzle_Offset").asMapping().orNull();
		if (muzzleOffsetSection != null) {
			NodeReader muzzleOffset = NodeReader.of(muzzleOffsetSection, report);
			weapon.setMuzzleOffsetData(new MuzzleOffsetData(
					parseMuzzleOffset(muzzleOffset, "Right_Hand", report),
					parseMuzzleOffset(muzzleOffset, "Left_Hand", report),
					parseMuzzleOffset(muzzleOffset, "Scope", report)));
		}

		MappingNode recoilSection = shoot.get("Recoil").asMapping().orNull();
		if (recoilSection != null) {
			NodeReader recoil = NodeReader.of(recoilSection, report);
			weapon.setRecoilData(new RecoilData());
			weapon.getRecoilData().setAmount(recoil.get("Amount").asDouble().orDefault(0.0));
			weapon.getRecoilData().setPushVelocity(recoil.get("Push").asDouble().orDefault(0.0));
			weapon.getRecoilData().setPushPowerUp(recoil.get("Power_Up").asDouble().orDefault(0.0));
			weapon.getRecoilData().setPattern(
					recoil.get("Pattern").asList().ofStrings().orEmpty()
							.stream().map(s -> s.split(";")).toList());

			MappingNode randomSection = recoil.get("Random").asMapping().orNull();
			if (randomSection != null) {
				NodeReader randomReader = NodeReader.of(randomSection, report);
				weapon.getRecoilData().setRandom(new RecoilData.RecoilRandom(
						randomReader.get("Mean_X").asDouble().orDefault(0.0),
						randomReader.get("Mean_Y").asDouble().orDefault(0.0),
						randomReader.get("Variance_X").asDouble().min(0).orDefault(0.0),
						randomReader.get("Variance_Y").asDouble().min(0).orDefault(0.0)));

				if (!weapon.getRecoilData().getPattern().isEmpty()) {
					report.add(Severity.WARNING, recoilSection.location(), recoilSection.path(),
					           "Recoil.Random and Recoil.Pattern both configured - Random takes precedence",
					           "recoil.random_and_pattern");
				}
			}
		}

		MappingNode spreadSection = shoot.get("Spread").asMapping().orNull();
		if (spreadSection == null) return;

		NodeReader spread = NodeReader.of(spreadSection, report);

		weapon.setSpreadData(new SpreadData());
		weapon.getSpreadData().setStart(spread.get("Starting_Spread").asDouble().orDefault(0.0));
		weapon.getSpreadData().setResetTime(spread.get("Time").asInt().orDefault(0));

		MappingNode modifySpreadSection = spread.get("Modify_Spread_When").asMapping().orNull();
		if (modifySpreadSection != null) {
			NodeReader modifySpread = NodeReader.of(modifySpreadSection, report);
			weapon.getSpreadData().setZoomingModifier(modifySpread.get("Zooming").asDouble().orDefault(0.0));
			weapon.getSpreadData().setSneakingModifier(modifySpread.get("Sneaking").asDouble().orDefault(0.0));
			weapon.getSpreadData().setSprintingModifier(modifySpread.get("Sprinting").asDouble().orDefault(0.0));
			weapon.getSpreadData().setInMidairModifier(modifySpread.get("In_Midair").asDouble().orDefault(0.0));
			weapon.getSpreadData().setSwimmingModifier(modifySpread.get("Swimming").asDouble().orDefault(0.0));
		}

		MappingNode spreadChangeSection = spread.get("Change").asMapping().orNull();
		if (spreadChangeSection == null) return;

		NodeReader change = NodeReader.of(spreadChangeSection, report);
		weapon.getSpreadData().setChangeBase(change.get("Base").asDouble().orDefault(0.0));

		MappingNode boundSection = change.get("Bounds").asMapping().orNull();
		if (boundSection == null) return;

		NodeReader bounds = NodeReader.of(boundSection, report);
		weapon.getSpreadData().setResetOnBound(bounds.get("Reset_On_Bound").asBool().orDefault(false));
		weapon.getSpreadData().setBoundMinimum(bounds.get("Min").asDouble().orDefault(0.0));
		weapon.getSpreadData().setBoundMaximum(bounds.get("Max").asDouble().orDefault(0.0));
	}

	/**
	 * Parses {@code Information.Equip_Delay}/{@code Deny_Use_In_Crafting}/{@code Cancel}/{@code Attributes} and,
	 * when a {@code Shoot:} section exists, {@code Shoot.Trigger}/{@code Circumstance}/{@code Destroy_When_Empty}/
	 * {@code Reset_Fall_Distance} into a {@link HandlingData} (weapons-roadmap.md gate {@code HE}, part a). Every
	 * key is optional and category-agnostic — {@code Trigger}/{@code Circumstance} only have an effect for GUN
	 * weapons ({@code WeaponInteract}/{@code GunAction}).
	 */
	private void applyHandling(NodeReader information, @Nullable NodeReader shoot, Weapon weapon,
	                           ConfigReport report) {
		HandlingData handling = new HandlingData();

		handling.setEquipDelay(information.get("Equip_Delay").asInt().min(0).orDefault(0));
		handling.setDenyUseInCrafting(information.get("Deny_Use_In_Crafting").asBool().orDefault(true));
		handling.setAttributes(parseAttributes(information, weapon.getName(), report));

		MappingNode cancelSection = information.get("Cancel").asMapping().orNull();
		if (cancelSection != null) {
			NodeReader cancel = NodeReader.of(cancelSection, report);
			handling.setCancel(new HandlingData.Cancel(
					cancel.get("Drop_Item").asBool().orDefault(false),
					cancel.get("Swap_Hands").asBool().orDefault(false),
					cancel.get("Break_Blocks").asBool().orDefault(true),
					cancel.get("Arm_Swing").asBool().orDefault(false)));
		}

		if (shoot != null) {
			String triggerString = shoot.get("Trigger").asString().orNull();
			if (triggerString != null) {
				Optional<HandlingData.Trigger> trigger = HandlingData.Trigger.fromKey(triggerString);
				if (trigger.isPresent()) handling.setTrigger(trigger.get());
				else warnBadHandlingValue(shoot, "Trigger", triggerString, report);
			}

			MappingNode circumstanceSection = shoot.get("Circumstance").asMapping().orNull();
			if (circumstanceSection != null) {
				NodeReader circumstance = NodeReader.of(circumstanceSection, report);
				for (HandlingData.Circumstance key : HandlingData.Circumstance.values()) {
					String raw = circumstance.get(key.key()).asString().orNull();
					if (raw == null) continue;

					Optional<HandlingData.Rule> rule = HandlingData.Rule.fromKey(raw);
					if (rule.isPresent()) handling.getCircumstances().put(key, rule.get());
					else warnBadHandlingValue(circumstance, key.key(), raw, report);
				}
			}

			handling.setDestroyWhenEmpty(shoot.get("Destroy_When_Empty").asBool().orDefault(false));
			handling.setResetFallDistance(shoot.get("Reset_Fall_Distance").asBool().orDefault(false));
		}

		weapon.setHandlingData(handling);
	}

	/**
	 * Parses {@code Information.Attributes} — a list of {@code "<attribute> <operation> <amount>"} strings.
	 * {@link XAttribute#of(String)} resolves both the modern (1.21.2+) and legacy ({@code GENERIC_}-prefixed)
	 * attribute names. A malformed entry (wrong token count, unrecognised attribute/operation, bad number) is a
	 * {@link Severity#WARNING} — the entry is skipped, the rest of the file keeps loading.
	 */
	private List<HandlingData.AttributeEntry> parseAttributes(NodeReader information, String weaponName,
	                                                          ConfigReport report) {
		List<String> raw = information.get("Attributes").asList().ofStrings().orEmpty();
		if (raw.isEmpty()) return List.of();

		List<HandlingData.AttributeEntry> entries = new ArrayList<>();
		for (String line : raw) {
			String[] tokens = line.trim().split("\\s+");
			if (tokens.length != 3) {
				warnBadAttribute(information, weaponName, line, report);
				continue;
			}

			Optional<XAttribute> xAttribute = XAttribute.of(tokens[0].toUpperCase(Locale.ROOT));
			if (xAttribute.isEmpty()) {
				warnBadAttribute(information, weaponName, line, report);
				continue;
			}

			try {
				AttributeModifier.Operation operation = AttributeModifier.Operation.valueOf(
						tokens[1].toUpperCase(Locale.ROOT));
				double amount = Double.parseDouble(tokens[2]);
				entries.add(new HandlingData.AttributeEntry(xAttribute.get().get(), operation, amount));
			} catch (IllegalArgumentException exception) {
				warnBadAttribute(information, weaponName, line, report);
			}
		}
		return entries;
	}

	private void warnBadAttribute(NodeReader information, String weaponName, String raw, ConfigReport report) {
		ConfigNode node = information.get("Attributes").node();
		report.add(Severity.WARNING, node != null ? node.location() : information.mapping().location(),
		           "Information.Attributes",
		           "weapon '" + weaponName + "' has an unrecognised Attributes entry '" + raw + "'",
		           "handling.unknown_attribute");
	}

	private void warnBadHandlingValue(NodeReader parent, String key, String raw, ConfigReport report) {
		ConfigNode node = parent.get(key).node();
		String     parentPath = parent.mapping().path();
		String     path       = parentPath == null || parentPath.isEmpty() ? key : parentPath + "." + key;
		report.add(Severity.WARNING, node != null ? node.location() : parent.mapping().location(), path,
		           "unrecognised value '" + raw + "' for " + path, "handling.unknown_" + key.toLowerCase(Locale.ROOT));
	}

	/**
	 * Parses the root {@code Effects:} section (weapons-roadmap.md gate {@code HA}, §1) and lowers the legacy
	 * {@code Shoot.Sound.*}/{@code Reload.Sound.*} slots into it, so old shipped YAML keeps producing feedback
	 * through the same {@link org.luckyraven.bartizan.effect.EffectRunner} path without any file edits.
	 */
	private void applyEffects(NodeReader root, @Nullable NodeReader shoot, Weapon weapon, ConfigReport report) {
		MappingNode effectsSection = root.get("Effects").asMapping().orNull();
		NodeReader  effects        = effectsSection != null ? NodeReader.of(effectsSection, report) : null;

		EffectsData effectsData = EffectsSectionParser.parse(effects, report);
		EffectsSectionParser.lowerLegacySounds(weapon.getSoundData(), effectsData);
		ChargeSectionParser.lowerChargeFeedback(shoot, effectsData);
		if (weapon instanceof BeamWeapon) {
			BeamWeaponParser.lowerImpactEffects(shoot, effectsData);
		}

		if (weapon instanceof BiologicalWeapon biological) {
			StatusData statusData = biological.getBiologicalData().getStatus();
			StatusSectionParser.lowerFeedback(shoot, effectsData, statusData.getName(), statusData.getIcon());
			StatusSectionParser.lowerTracer(shoot, weapon.getModifiersData());
		}

		weapon.setEffects(effectsData);
	}

	/**
	 * Parses the root {@code HUD:} section (weapons-roadmap.md gate {@code HD}) — category agnostic, like
	 * {@link #applyEffects}.
	 */
	private void applyHud(NodeReader root, Weapon weapon, ConfigReport report) {
		MappingNode hudSection = root.get("HUD").asMapping().orNull();
		NodeReader  hud        = hudSection != null ? NodeReader.of(hudSection, report) : null;

		weapon.setHudData(HudSectionParser.parse(hud, report));
	}

	/**
	 * Parses {@code Information.Item_Model} and the root {@code Skins:} section (weapons-roadmap.md gate
	 * {@code HJ}) — category agnostic, like {@link #applyHud}. {@code Item_Model} is warned about (but still
	 * stored) when the running server is older than 1.21.2, since {@link Weapon#updateWeaponData} never applies it
	 * on such a server anyway — the warning is purely so an admin understands why the model never shows up.
	 */
	private void applySkins(NodeReader root, NodeReader information, Weapon weapon, ConfigReport report,
	                        int customModelData) {
		NamespacedKey itemModel = SkinSectionParser.parseItemModel(information, "Item_Model", report);
		if (itemModel != null && !NmsVersion.current().atLeast(21, 2)) {
			String parentPath = information.mapping().path();
			String path = parentPath == null || parentPath.isEmpty() ? "Item_Model" : parentPath + ".Item_Model";
			report.add(Severity.WARNING, information.mapping().location(), path,
			           "Information.Item_Model requires server 1.21.2+ - ignored on this server version",
			           "skins.item_model_unsupported_version");
		}
		weapon.setItemModel(itemModel);

		MappingNode skinsSection = root.get("Skins").asMapping().orNull();
		NodeReader  skins        = skinsSection != null ? NodeReader.of(skinsSection, report) : null;

		weapon.setSkinsData(SkinSectionParser.parse(skins, customModelData, report));
	}

	/**
	 * {@code XMaterial.SPYGLASS.isSupported()} behind a swappable seam (weapons-roadmap.md gate {@code HP}).
	 * Package-visible so {@code ScopeConfigTest} can stub the &lt;1.17 fallback (and the happy path it would
	 * otherwise gate) without a matching spigot-api jar on the test classpath. Reset by every test that touches it.
	 * <p/>
	 * Deliberately a lambda, not a {@code XMaterial.SPYGLASS::isSupported} method reference: binding that
	 * reference evaluates {@code XMaterial.SPYGLASS} immediately, which forces {@code XMaterial}'s static
	 * initialiser to run the moment THIS class is merely loaded (e.g. by {@code mock(WeaponAddon.class)}) - inside
	 * a {@code mockStatic(Bukkit.class)} block that never stubbed {@code getVersion()}, that initialiser NPEs and
	 * permanently poisons both classes for the rest of the JVM fork. The lambda defers the {@code XMaterial} touch
	 * to the moment {@link BooleanSupplier#getAsBoolean()} actually runs, exactly like every other {@code XMaterial}
	 * call in this file.
	 */
	static BooleanSupplier spyglassSupported = () -> XMaterial.SPYGLASS.isSupported();

	private void applyScope(NodeReader root, @Nullable String materialString, Weapon weapon, ConfigReport report) {
		MappingNode scopeSection = root.get("Scope").asMapping().orNull();
		if (scopeSection == null) return;

		NodeReader scope = NodeReader.of(scopeSection, report);

		ScopeData scopeData = new ScopeData();
		weapon.setScopeData(scopeData);

		ScopeType type = parseScopeType(scope, scopeSection, materialString, weapon, report);
		scopeData.setType(type);

		// Zoom_Amount is a WeaponMechanics-style alias for Level; when both are given, Level wins with a warning.
		// Both keys are read unconditionally (NodeReader.has() alone does not mark a key as touched, and a
		// short-circuited get() would otherwise leave Zoom_Amount looking unread when Level also wins), so
		// configuring both never raises a spurious config.unknown_key on top of the intended warning below.
		int     levelValue      = scope.get("Level").asInt().orDefault(0);
		int     zoomAmountValue = scope.get("Zoom_Amount").asInt().orDefault(0);
		boolean hasLevel        = scope.has("Level");
		boolean hasZoomAmount   = scope.has("Zoom_Amount");
		if (hasLevel && hasZoomAmount) {
			report.add(Severity.WARNING, scopeSection.location(), scopeSection.path(),
			           "Scope.Level and Scope.Zoom_Amount both configured - Level takes precedence",
			           "scope.level_and_zoom_amount");
		}
		scopeData.setLevel(hasLevel ? levelValue : zoomAmountValue);

		scopeData.setNightVision(scope.get("Night_Vision").asBool().orDefault(false));
		scopeData.setShootDelayAfterScope(scope.get("Shoot_Delay_After_Scope").asInt().min(0).orDefault(0));

		MappingNode zoomStackingSection = scope.get("Zoom_Stacking").asMapping().orNull();
		if (zoomStackingSection != null) {
			if (type == ScopeType.SPYGLASS) {
				// Meaningless under a fixed vanilla zoom - warned and ignored rather than silently read, so an
				// admin who copies Zoom_Stacking onto a spyglass weapon finds out why it does nothing.
				report.add(Severity.WARNING, scopeSection.location(), scopeSection.path(),
				           "Scope.Zoom_Stacking is meaningless for Scope.Type: spyglass (fixed vanilla zoom) - ignored",
				           "scope.zoom_stacking_ignored_spyglass");
			} else {
				NodeReader zoomStacking = NodeReader.of(zoomStackingSection, report);
				scopeData.setZoomStacks(zoomStacking.get("Maximum_Stacks").asInt().min(1).orDefault(1));
				scopeData.setZoomPerStack(zoomStacking.get("Increase_Per_Stack").asInt().min(0).orDefault(1));
			}
		}

		MappingNode soundSection = scope.get("Sound").asMapping().orNull();
		if (soundSection == null) return;

		NodeReader sound = NodeReader.of(soundSection, report);

		weapon.getSoundData().setScopeDefault(parseSound(sound, "Default_Sound",
		                                                 SoundEffect.SoundType.VANILLA, report));
		weapon.getSoundData().setScopeCustom(parseSound(sound, "Custom_Sound",
		                                                SoundEffect.SoundType.CUSTOM, report));
	}

	/**
	 * {@code Scope.Type} (weapons-roadmap.md gate {@code HP}): {@code slowness} (default, today's behaviour) or
	 * {@code spyglass} (1.17+ vanilla zoom/raised-arm/use-slowdown, guns only - no packets, no NMS). Falls back to
	 * {@link ScopeType#SLOWNESS} - the weapon keeps loading and scopes exactly like a {@code Type: slowness} gun -
	 * when the running server predates spyglasses ({@link Severity#WARNING}) or {@code Information.Material} isn't
	 * literally {@code SPYGLASS} ({@link Severity#ERROR}: the vanilla zoom is keyed off the held item's real type,
	 * so a mismatched material could never actually zoom). {@code Shoot.Trigger: right_click} is also warned about
	 * once the weapon is confirmed spyglass - right-click is the spyglass's own vanilla use, so left-click is
	 * implied.
	 */
	private ScopeType parseScopeType(NodeReader scope, MappingNode scopeSection, @Nullable String materialString,
	                                 Weapon weapon, ConfigReport report) {
		String raw = scope.get("Type").asString().orNull();
		if (raw == null || !raw.trim().equalsIgnoreCase("spyglass")) return ScopeType.SLOWNESS;

		if (!spyglassSupported.getAsBoolean()) {
			report.add(Severity.WARNING, scopeSection.location(), scopeSection.path(),
			           "Scope.Type: spyglass requires server 1.17+ - '" + weapon.getName() +
			           "' will scope as slowness instead",
			           "scope.spyglass_unsupported_version");
			return ScopeType.SLOWNESS;
		}

		// Compared through XMaterial.matchXMaterial, like every other Material read in this file (line ~89), so
		// "minecraft:spyglass" / legacy forms don't wrongly trip this - the vanilla zoom is keyed off the held
		// item's real type, so only the resolved material actually matters, not the raw YAML string.
		Optional<XMaterial> resolvedMaterial = materialString == null ? Optional.empty()
		                                                               : XMaterial.matchXMaterial(materialString);
		if (resolvedMaterial.orElse(null) != XMaterial.SPYGLASS) {
			report.add(Severity.ERROR, scopeSection.location(), scopeSection.path(),
			           "Scope.Type: spyglass requires Information.Material: SPYGLASS in '" + weapon.getName() +
			           "' - scoping as slowness instead",
			           "scope.material_mismatch");
			return ScopeType.SLOWNESS;
		}

		HandlingData handling = weapon.getHandlingData();
		if (handling != null && handling.getTrigger() == HandlingData.Trigger.RIGHT_CLICK) {
			report.add(Severity.WARNING, scopeSection.location(), scopeSection.path(),
			           "'" + weapon.getName() + "' uses Scope.Type: spyglass with the default Shoot.Trigger: " +
			           "right_click - right-click is the spyglass's own vanilla use, so left-click is implied",
			           "scope.spyglass_right_click_trigger");
		}

		return ScopeType.SPYGLASS;
	}

	private void applyShootSounds(@Nullable NodeReader shoot, Weapon weapon, ConfigReport report) {
		if (shoot == null) return;
		MappingNode soundSection = shoot.get("Sound").asMapping().orNull();
		if (soundSection == null) return;

		NodeReader sound = NodeReader.of(soundSection, report);

		weapon.getSoundData().setShotDefault(parseSound(sound, "Default_Sound",
		                                                SoundEffect.SoundType.VANILLA, report));
		weapon.getSoundData().setShotCustom(parseSound(sound, "Custom_Sound",
		                                               SoundEffect.SoundType.CUSTOM, report));
		weapon.getSoundData().setEmptyMagDefault(parseSound(sound, "Empty_Default_Sound",
		                                                    SoundEffect.SoundType.VANILLA, report));
		weapon.getSoundData().setEmptyMagCustom(parseSound(sound, "Empty_Custom_Sound",
		                                                   SoundEffect.SoundType.CUSTOM, report));

		double flybyRange = sound.get("Flyby_Range").asDouble().orDefault(0.0);
		weapon.getSoundData().setFlybyRange(flybyRange);
		weapon.getSoundData().setFlybyDefault(parseSound(sound, "Flyby_Default_Sound",
		                                                 SoundEffect.SoundType.VANILLA, report));
		weapon.getSoundData().setFlybyCustom(parseSound(sound, "Flyby_Custom_Sound",
		                                                SoundEffect.SoundType.CUSTOM, report));
		weapon.getSoundData().setImpactDefault(parseSound(sound, "Impact_Default_Sound",
		                                                  SoundEffect.SoundType.VANILLA, report));
		weapon.getSoundData().setImpactCustom(parseSound(sound, "Impact_Custom_Sound",
		                                                 SoundEffect.SoundType.CUSTOM, report));
	}

	private void applyReloadSoundsAndActionBar(NodeReader root, Weapon weapon, ConfigReport report) {
		MappingNode reloadSection = root.get("Reload").asMapping().orNull();
		if (reloadSection == null) return;

		NodeReader reload = NodeReader.of(reloadSection, report);

		MappingNode reloadSoundSection = reload.get("Sound").asMapping().orNull();
		if (reloadSoundSection != null) {
			NodeReader reloadSound = NodeReader.of(reloadSoundSection, report);

			weapon.getSoundData().setReloadDefaultBefore(parseSound(reloadSound, "Default_Sound_Before",
			                                                        SoundEffect.SoundType.VANILLA, report));
			weapon.getSoundData().setReloadDefaultAfter(parseSound(reloadSound, "Default_Sound_After",
			                                                       SoundEffect.SoundType.VANILLA, report));

			MappingNode customSoundSection = reloadSound.get("Custom_Sound").asMapping().orNull();
			if (customSoundSection != null) {
				NodeReader custom = NodeReader.of(customSoundSection, report);
				weapon.getSoundData().setReloadCustomStart(parseSound(custom, "Start",
				                                                      SoundEffect.SoundType.CUSTOM, report));
				weapon.getSoundData().setReloadCustomMid(parseSound(custom, "Mid",
				                                                    SoundEffect.SoundType.CUSTOM, report));
				weapon.getSoundData().setReloadCustomEnd(parseSound(custom, "End",
				                                                    SoundEffect.SoundType.CUSTOM, report));
			}
		}

		MappingNode actionBarSection = reload.get("Action_Bar").asMapping().orNull();
		if (actionBarSection != null) {
			NodeReader actionBar = NodeReader.of(actionBarSection, report);
			weapon.setReloadActionBarData(new ReloadActionBarData());
			weapon.getReloadActionBarData().setReloading(actionBar.get("Reloading").asString().orNull());
			weapon.getReloadActionBarData().setOpening(actionBar.get("Opening").asString().orNull());
		}
	}

	/**
	 * Parses one {@code Shoot.Muzzle_Offset.<key>} entry: a space-separated {@code "right up forward"} triple in
	 * blocks (e.g. {@code "0.3 -0.2 0.5"}). An entirely absent key silently falls back to {@link WeaponMuzzle}'s
	 * historical constants (0 for the forward component, which has no legacy equivalent) — {@code Left_Hand}'s
	 * right-component fallback is mirrored to the opposite side ({@code -RIGHT_OFFSET}) since it sits on the
	 * shooter's other hand. A configured-but-unparsable component is a {@code ConfigReport} WARNING
	 * ({@code shoot.muzzle_offset}), not a silent catch.
	 */
	private MuzzleOffsetData.Offset parseMuzzleOffset(NodeReader muzzleOffset, String key, ConfigReport report) {
		String raw            = muzzleOffset.get(key).asString().orDefault("").trim();
		double rightFallback = "Left_Hand".equals(key) ? -WeaponMuzzle.RIGHT_OFFSET : WeaponMuzzle.RIGHT_OFFSET;
		if (raw.isEmpty()) {
			return new MuzzleOffsetData.Offset(rightFallback, WeaponMuzzle.DOWN_OFFSET, 0.0);
		}

		String[] parts = raw.split("\\s+");
		return new MuzzleOffsetData.Offset(
				parseOffsetComponent(muzzleOffset, key, parts, 0, rightFallback, report),
				parseOffsetComponent(muzzleOffset, key, parts, 1, WeaponMuzzle.DOWN_OFFSET, report),
				parseOffsetComponent(muzzleOffset, key, parts, 2, 0.0, report));
	}

	private double parseOffsetComponent(NodeReader muzzleOffset, String key, String[] parts, int index,
	                                    double fallback, ConfigReport report) {
		if (index >= parts.length) return fallback;
		try {
			return Double.parseDouble(parts[index]);
		} catch (NumberFormatException exception) {
			MappingNode mapping = muzzleOffset.mapping();
			report.add(Severity.WARNING, mapping.location(), mapping.path() + "." + key,
			           "malformed Muzzle_Offset." + key + " component '" + parts[index] + "' - using default",
			           "shoot.muzzle_offset");
			return fallback;
		}
	}

	@Nullable
	private SoundEffect parseSound(NodeReader parent, String key, SoundEffect.SoundType type,
	                                      ConfigReport report) {
		MappingNode section = parent.get(key).asMapping().orNull();
		if (section == null) return null;
		NodeReader r     = NodeReader.of(section, report);
		String     sound = r.get("Sound").asString().orNull();
		if (sound == null) return null;
		float volume = (float) r.get("Volume").asDouble().orDefault(1.0);
		float pitch  = (float) r.get("Pitch").asDouble().orDefault(1.0);
		return new SoundEffect(type, sound, volume, pitch);
	}

}
