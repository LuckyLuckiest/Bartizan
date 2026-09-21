package org.luckyraven.bartizan.api.weapon;

import com.cryptomorin.xseries.XMaterial;
import com.cryptomorin.xseries.XPotion;
import java.lang.reflect.Method;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.CrossbowMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.luckyraven.keystone.item.ItemBuilder;
import org.luckyraven.keystone.nms.NmsVersion;
import org.luckyraven.keystone.util.Placeholder;
import org.luckyraven.keystone.exception.PluginException;
import org.luckyraven.bartizan.api.ammo.Ammunition;
import org.luckyraven.bartizan.api.item.AttributeModifiers;
import org.luckyraven.bartizan.api.weapon.dto.*;
import org.luckyraven.bartizan.api.weapon.durability.DurabilityCalculator;
import org.luckyraven.bartizan.api.weapon.recoil.RecoilManager;
import org.luckyraven.bartizan.api.weapon.spread.SpreadManager;
import org.luckyraven.bartizan.api.weapon.reload.Reload;
import org.luckyraven.bartizan.api.weapon.WeaponType;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

@Getter
@Setter
public abstract class Weapon implements Cloneable, Comparable<Weapon> {

	private final String                 name;
	private final String                 displayName;
	private final WeaponType             category;
	private final Material               material;
	private final int                    customModelData;
	private final short                  durability;
	private final List<String>           lore;
	private final boolean                dropHologram;
	@Setter(AccessLevel.NONE)
	private final List<String>           deathMessages;
	// Runtime state
	private final Map<WeaponTag, Object> tags;
	// Reload configuration (immutable — set at construction)
	@Nullable
	private final ReloadData             reloadData;
	@Nullable
	private final AmmunitionData         ammunitionData;
	// Core identity
	@Setter(AccessLevel.NONE)
	private       UUID                   uuid;
	// Configuration groups (mutable — set by WeaponAddon after construction)
	private       DurabilityData         durabilityData;
	private       SoundData              soundData;
	private       ReloadActionBarData    reloadActionBarData;
	private       ModifiersData          modifiersData;
	private       RecoilData             recoilData;
	private       ScopeData              scopeData;
	private       SpreadData             spreadData;
	/**
	 * {@code Shoot.Muzzle_Offset}, parsed by {@code WeaponAddon.applyOptionalShootConfig}. {@code null} when
	 * unconfigured — {@code WeaponMuzzle.compute} falls back to its historical hardcoded offset in that case.
	 */
	@Nullable
	private       MuzzleOffsetData       muzzleOffsetData;
	private       EffectsData            effects = EffectsData.empty();
	@Nullable
	private       HudData                hudData;
	/**
	 * {@code Skins:} section (weapons-roadmap.md gate {@code HJ}) — per-{@link SkinState} custom model data plus
	 * player-selectable {@code Named} skins. {@code null} for a weapon with no {@code Skins:} block at all, in
	 * which case {@link #resolveCustomModelData}/{@link #resolveItemModel} fall straight through to
	 * {@link #customModelData}/{@link #itemModel}.
	 */
	@Nullable
	private       SkinsData              skinsData;
	/**
	 * {@code Information.Item_Model} (weapons-roadmap.md gate {@code HJ}) — a 1.21.2+ item model component.
	 * {@code null} when unconfigured. Never applied on a server older than 1.21.2; see
	 * {@link #updateWeaponData(ItemBuilder, Player)}.
	 */
	@Nullable
	private       NamespacedKey          itemModel;
	/**
	 * Interaction-handling rules (weapons-roadmap.md gate {@code HE}, part a) — {@code Equip_Delay},
	 * {@code Deny_Use_In_Crafting}, {@code Cancel.*}, {@code Attributes}, {@code Trigger}, {@code Circumstance},
	 * {@code Destroy_When_Empty}, {@code Reset_Fall_Distance}. {@code null} for a weapon built outside
	 * {@code WeaponAddon.registerWeapon} (e.g. a test fixture) — every consumer treats {@code null} the same as
	 * the all-defaults {@link HandlingData}.
	 */
	@Nullable
	private       HandlingData           handlingData;
	// Runtime state
	private       int                    currentMagCapacity;
	private       SelectiveFire          currentSelectiveFire;
	/**
	 * Wall-clock deadline ({@link System#currentTimeMillis()}) before which {@code Reload.Shoot_Delay_After_Reload}
	 * silently refuses to fire. Set by {@code Reload#endReloading} on a non-interrupted completion; checked by
	 * {@code GunAction#weaponShoot} via {@link #isShootLocked()}. Transient runtime state — not persisted to NBT
	 * and reset on {@link #initClone}, so a freshly minted item never starts shoot-locked.
	 */
	private       long                   shootLockedUntilMillis;
	/**
	 * Modes the weapon is permitted to cycle through. Set by the parser from the {@code Allowed_Modes} yml key. Empty
	 * or {@code null} means "no restriction" — the legacy 3-mode SINGLE/BURST/AUTO cycle is used. The configured
	 * starting {@link #currentSelectiveFire} must always be present in this set when the set is non-empty.
	 */
	private       Set<SelectiveFire>     allowedSelectiveFires;
	/**
	 * The currently selected {@code Skins.Named} skin (weapons-roadmap.md gate {@code HJ}), or {@code null} when
	 * none is selected. Runtime state — persisted via {@link WeaponTag#SKIN} and rehydrated by {@code
	 * WeaponService#setWeaponData} on every resolve, mirroring {@link #currentSelectiveFire}/{@code AMMO_TYPE}.
	 * Set only through {@link #setSelectedSkin(String)}, which validates the name against {@link #skinsData}.
	 */
	@Setter(AccessLevel.NONE)
	private       String                 selectedSkin;

	private String changingDisplayName;
	private short  currentDurability;

	// Managers (non-serialized)
	@Setter(AccessLevel.NONE)
	private DurabilityCalculator durabilityCalculator;
	@Getter(AccessLevel.NONE)
	@Setter(AccessLevel.NONE)
	private Reload               reload;
	@Setter(AccessLevel.NONE)
	private RecoilManager        recoil;
	@Setter(AccessLevel.NONE)
	private SpreadManager        spread;
	// Placeholder resolver for display name + lore (injected by WeaponAddon after construction).
	@Nullable
	private Placeholder          placeholder;

	protected Weapon(UUID uuid, String name, String displayName, WeaponType category, Material material,
	                 int customModelData, short durability, List<String> lore, boolean dropHologram,
	                 @Nullable List<String> deathMessages, @Nullable ReloadData reloadData,
	                 @Nullable AmmunitionData ammunitionData) {
		this.uuid              = uuid;
		this.name              = name;
		this.displayName       = displayName;
		this.category          = category;
		this.material          = material;
		this.customModelData   = customModelData;
		this.durability        = durability;
		this.currentDurability = durability;
		this.lore              = lore;
		this.dropHologram      = dropHologram;
		this.deathMessages     = deathMessages;
		this.reloadData        = reloadData;
		this.ammunitionData    = ammunitionData;

		this.currentMagCapacity = ammunitionData != null ? ammunitionData.getMaxMagCapacity() : 0;

		this.tags                 = new TreeMap<>();
		this.recoil               = new RecoilManager(this);
		this.spread               = new SpreadManager(this);
		this.reload               = ammunitionData != null && reloadData != null ?
		                            reloadData.getType().createInstance(this, ammunitionData.getAmmoType()) :
		                            null;
		this.changingDisplayName  = buildDisplayName();
		this.durabilityCalculator = new DurabilityCalculator(this);
	}

	// --- Death message ---

	public abstract Weapon copyWithUUID(UUID newUuid);

	// --- Scope operations ---

	public static String getTagProperName(WeaponTag tag) {
		return tag.name().toLowerCase().replace("_", "-");
	}

	public Optional<String> pickDeathMessage() {
		if (deathMessages == null || deathMessages.isEmpty()) return Optional.empty();
		return Optional.of(deathMessages.get(ThreadLocalRandom.current().nextInt(deathMessages.size())));
	}

	public void scope(Player player, boolean bypass) {
		if (scopeData == null) return;
		if (!bypass && scopeData.isScoped()) return;

		scopeData.setScoped(true);

		applyEffect(player, XPotion.SLOWNESS, scopeData.amplifier());
		// Night_Vision and Shoot_Delay_After_Scope are player-initiated-only side effects (see #cycleScope) -
		// Reload#startReloading also calls this method to slow the player during a reload, and must not trigger
		// either one.
	}

	public void unScope(Player player, boolean bypass) {
		if (scopeData == null) return;
		if (!bypass && !scopeData.isScoped()) return;

		scopeData.setScoped(false);
		scopeData.setCurrentStack(0);

		removeEffect(player, XPotion.SLOWNESS);
		if (scopeData.isNightVision()) {
			removeEffect(player, XPotion.NIGHT_VISION);
		}
	}

	/**
	 * LMB scope toggle (weapons-roadmap.md gate {@code HH}): delegates the {@code Zoom_Stacking} transition to
	 * {@link ScopeData#advanceZoomStack()} then applies it through {@link #scope}/{@link #unScope} (both called
	 * with {@code bypass=true} since the stack step must re-apply even though {@code scoped} is already true).
	 *
	 * @return {@code true} when this call scoped in (or stepped to a further zoom stage) - the caller fires
	 * 		{@code ON_SCOPE_IN}; {@code false} when it unscoped - {@code ON_SCOPE_OUT}. A scopeless weapon
	 * 		({@code scopeData == null}) is a no-op that reports {@code false}.
	 */
	public boolean cycleScope(Player player) {
		if (scopeData == null) return false;

		boolean scopingIn = scopeData.advanceZoomStack();
		if (scopingIn) {
			scope(player, true);

			if (scopeData.isNightVision()) {
				applyEffect(player, XPotion.NIGHT_VISION, 0);
			}

			// Scope.Shoot_Delay_After_Scope: mirrors Reload.Shoot_Delay_After_Reload's lock (Reload#endReloading).
			// Player-initiated only - Reload's own scope(player, false) call must not arm this.
			if (scopeData.getShootDelayAfterScope() > 0) {
				shootLockedUntilMillis =
						System.currentTimeMillis() + scopeData.getShootDelayAfterScope() * 50L; // 50ms/tick
			}
		} else {
			unScope(player, true);
		}
		return scopingIn;
	}

	public boolean isReloading() {
		return reload != null && reload.isReloading();
	}

	/**
	 * @return 0.0-1.0 progress through the current reload, 0.0 when not reloading. Backed by {@link
	 * 		Reload#reloadProgress()}.
	 */
	public double reloadProgress() {
		return reload != null ? reload.reloadProgress() : 0.0;
	}

	/**
	 * @return the total duration (ticks) of the current/most recent reload, or 0 when the weapon has no reload
	 * 		configured. Backed by {@link Reload#totalDurationTicks()}.
	 */
	public long reloadDurationTicks() {
		return reload != null ? reload.totalDurationTicks() : 0L;
	}

	/**
	 * @return ticks left in the current/most recent reload after a Phase 2 resume's offset (weapons-roadmap.md
	 * 		gate {@code HO}), or 0 when the weapon has no reload configured. Backed by
	 * 		{@link Reload#remainingDurationTicks()} — read by {@code WeaponReloadListener} for the vanilla
	 * 		item-cooldown overlay.
	 */
	public long reloadRemainingDurationTicks() {
		return reload != null ? reload.remainingDurationTicks() : 0L;
	}

	/**
	 * @return the 0-based index of the reload stage currently in progress, or -1 when not reloading. Backed by
	 * 		{@link Reload#currentStageIndex()} — weapons-roadmap.md gate {@code HO}, {@code %reload_stage%}.
	 */
	public int reloadStageIndex() {
		return reload != null ? reload.currentStageIndex() : -1;
	}

	/**
	 * @return the total stage count of the current/most recent reload attempt, or 0 when not applicable. Backed by
	 * 		{@link Reload#stageCount()} — {@code %reload_stage_max%}.
	 */
	public int reloadStageCount() {
		return reload != null ? reload.stageCount() : 0;
	}

	/**
	 * Re-resolves this weapon's loaded ammo type from the item's persisted {@link WeaponTag#AMMO_TYPE} tag,
	 * matched by {@link Ammunition#getName()} against the configured {@code Ammunition.Types}/{@code Ammo_Type},
	 * and pushes it into the {@link Reload} instance. Called whenever a {@code Weapon} is (re)resolved from its
	 * item — see {@code WeaponService#setWeaponData} — so {@code Reload.Unload_Ammo_On_Reload} on a multi-{@code
	 * Types} weapon keeps handing back the type actually loaded instead of resetting to the first configured type
	 * across a relog, drop+pickup or {@code /bartizan reload}.
	 * <p/>
	 * No-op when there is no reload/ammo configured. Falls back to the first configured type for an absent or
	 * unrecognised tag (legacy items minted before {@code AMMO_TYPE} existed, or {@code Ammo_Type: none}
	 * switched to a real type since).
	 */
	public void setLoadedAmmoType(@Nullable String ammoTypeName) {
		if (reload == null || ammunitionData == null) return;

		List<Ammunition> types = ammunitionData.getAmmoTypes();
		if (types.isEmpty()) return;

		Ammunition resolved = types.get(0);
		if (ammoTypeName != null && !ammoTypeName.isEmpty()) {
			for (Ammunition candidate : types) {
				if (candidate.getName().equalsIgnoreCase(ammoTypeName)) {
					resolved = candidate;
					break;
				}
			}
		}

		reload.setLoadedAmmunition(resolved);
	}

	public void reload(JavaPlugin plugin, Player player, boolean removeAmmunition) {
		if (reload == null) return;
		reload.reload(plugin, player, removeAmmunition);
	}

	public void stopReloading() {
		if (reload == null) return;
		reload.stopReloading();
	}

	public boolean isMagazineFull() {
		if (ammunitionData == null) return true;
		return currentMagCapacity >= ammunitionData.getMaxMagCapacity();
	}

	public boolean isMagazineEmpty() {
		if (reloadData == null) return false;
		return currentMagCapacity <= 0;
	}

	public void addAmmunition(int amount) {
		if (ammunitionData == null) return;
		currentMagCapacity = Math.min(ammunitionData.getMaxMagCapacity(), currentMagCapacity + amount);
	}

	/**
	 * Consumes one shot's worth of ammunition. Returns {@code false} if the magazine is empty. Subclasses may override
	 * to change the consume amount (e.g. Gun uses projectileData.getConsumed()).
	 */
	public boolean consumeShot() {
		if (isMagazineEmpty()) return false;
		currentMagCapacity = Math.max(0, currentMagCapacity - 1);
		return true;
	}

	public boolean requiresReload() {
		return reloadData != null && !isMagazineFull();
	}

	/**
	 * {@code Reload.Shoot_Delay_After_Reload}: {@code true} while shooting should be silently refused after a
	 * completed reload. Vacuously {@code false} for a weapon that has never reloaded.
	 */
	public boolean isShootLocked() {
		return System.currentTimeMillis() < shootLockedUntilMillis;
	}

	// --- Skin operations (weapons-roadmap.md gate HJ) ---

	/**
	 * The active {@link SkinState} for rendering, in a fixed priority order: a reload in progress beats being
	 * scoped in, which beats an empty magazine, which beats sprinting.
	 * // ponytail: fixed priority, no per-weapon override — add a config knob if a weapon ever needs a different
	 * // order.
	 *
	 * @param player the shooter, or {@code null} when there is none (e.g. an NPC-fired shot) — sprinting is simply
	 * 		never detected in that case.
	 */
	public SkinState currentSkinState(@Nullable Player player) {
		if (isReloading()) return SkinState.RELOAD;
		if (scopeData != null && scopeData.isScoped()) return SkinState.SCOPE;
		if (isMagazineEmpty()) return SkinState.NO_AMMO;
		if (player != null && player.isSprinting()) return SkinState.SPRINT;
		return SkinState.DEFAULT;
	}

	/**
	 * The custom model data to render for {@code state}: the selected {@link #selectedSkin}'s own override for
	 * {@code state} (falling back to that named skin's own {@code Default}), else the root {@link #skinsData}'s
	 * override for {@code state} (falling back to its {@code Default}), else {@link #customModelData}.
	 */
	public int resolveCustomModelData(SkinState state) {
		if (skinsData == null) return customModelData;

		SkinsData.NamedSkin named = selectedSkin != null ? skinsData.named(selectedSkin) : null;
		if (named != null) {
			Integer namedState = named.state(state);
			if (namedState != null) return namedState;

			Integer namedDefault = named.state(SkinState.DEFAULT);
			if (namedDefault != null) return namedDefault;
		}

		Integer rootState = skinsData.state(state);
		if (rootState != null) return rootState;

		Integer rootDefault = skinsData.state(SkinState.DEFAULT);
		return rootDefault != null ? rootDefault : customModelData;
	}

	/**
	 * The item model to render: the selected named skin's {@code Item_Model} override when one is configured, else
	 * {@link #itemModel} ({@code Information.Item_Model}). {@code state} is accepted for symmetry with
	 * {@link #resolveCustomModelData(SkinState)} — unlike custom model data, {@code Item_Model} has no per-state
	 * override, only a per-named-skin one.
	 */
	@Nullable
	public NamespacedKey resolveItemModel(@Nullable SkinState state) {
		SkinsData.NamedSkin named = skinsData != null && selectedSkin != null ? skinsData.named(selectedSkin) : null;
		if (named != null && named.itemModel() != null) return named.itemModel();

		return itemModel;
	}

	/**
	 * Validates {@code name} against this weapon's {@link SkinsData#named(String)} table and, if it matches (or
	 * {@code name} is {@code null}/empty, meaning "no skin"), applies it.
	 *
	 * @return {@code false} for an unrecognised name — the current selection is left unchanged in that case.
	 */
	public boolean setSelectedSkin(@Nullable String name) {
		if (name == null || name.isEmpty()) {
			this.selectedSkin = null;
			return true;
		}

		if (skinsData == null || skinsData.named(name) == null) return false;

		this.selectedSkin = name.toLowerCase(Locale.ROOT);
		return true;
	}

	@NotNull
	public ItemStack buildItem() {
		return buildItem(null);
	}

	@NotNull
	public ItemStack buildItem(@Nullable Player player) {
		ItemBuilder builder = new ItemBuilder(material);
		builder.setDisplayName(resolvePlaceholder(player, changingDisplayName))
		       .setLore(resolvePlaceholder(player, lore));

		short currentDamage = (short) Math.floor(
				(durability - currentDurability) * (builder.getItemMaxDurability() / (double) durability));
		builder.setDurability(currentDamage);

		// The initial give only ever needs the DEFAULT-state model - routed through the same rendering path
		// updateWeaponData uses (gate HJ review finding 6) so a freshly given weapon on 1.21.2+ carries its item
		// model immediately instead of waiting for the first shot/reload/scope/sprint to apply it.
		applySkinRendering(builder, SkinState.DEFAULT);

		initializeTags(builder);
		builder.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);

		ItemStack item = builder.build();
		applyAttributeModifiers(item);
		applyCrossbowChargedProjectile(item);
		return item;
	}

	/**
	 * {@code Material: CROSSBOW} weapons render the vanilla charged-crossbow hold pose (both arms up) purely from
	 * one arrow sitting in {@link CrossbowMeta} - no packets, no NMS (weapons-roadmap.md gate {@code HP}). The
	 * arrow is cosmetic only; Bartizan's own shooting path never reads it. {@code Material} is compared through
	 * {@link XMaterial#CROSSBOW} per house rule even though the enum constant itself is safe on the 1.16.5 compile
	 * floor (added 1.14).
	 */
	private void applyCrossbowChargedProjectile(ItemStack item) {
		if (material != XMaterial.CROSSBOW.get()) return;

		ItemMeta meta = item.getItemMeta();
		if (!(meta instanceof CrossbowMeta crossbowMeta)) return;

		crossbowMeta.setChargedProjectiles(List.of(XMaterial.ARROW.parseItem()));
		item.setItemMeta(crossbowMeta);
	}

	public void updateWeaponData(ItemBuilder itemBuilder) {
		updateWeaponData(itemBuilder, null);
	}

	public void updateWeaponData(ItemBuilder itemBuilder, @Nullable Player player) {
		this.changingDisplayName = buildDisplayName();
		itemBuilder.setDisplayName(resolvePlaceholder(player, changingDisplayName));

		boolean updatedSelectiveFire = false, updatedCurrentAmmo = false, updatedAmmoType = false, updatedSkin = false;

		for (WeaponTag tag : WeaponTag.values()) {
			if (containsTag(itemBuilder, tag)) continue;

			switch (tag) {
				case UUID -> tags.put(tag, uuid != null ? uuid.toString() : "");
				case WEAPON -> tags.put(tag, name);
				case SELECTIVE_FIRE -> {
					tags.put(tag, getSelectiveFireForTag());
					updatedSelectiveFire = true;
				}
				case AMMO_LEFT -> {
					tags.put(tag, getAmmoLeftForTag());
					updatedCurrentAmmo = true;
				}
				case AMMO_TYPE -> {
					tags.put(tag, getAmmoTypeForTag());
					updatedAmmoType = true;
				}
				case SKIN -> {
					tags.put(tag, getSkinForTag());
					updatedSkin = true;
				}
			}
			itemBuilder.addTag(getTagProperName(tag), tags.get(tag));
		}

		if (!updatedSelectiveFire) updateTag(itemBuilder, WeaponTag.SELECTIVE_FIRE, getSelectiveFireForTag());
		if (!updatedCurrentAmmo) updateTag(itemBuilder, WeaponTag.AMMO_LEFT, getAmmoLeftForTag());
		if (!updatedAmmoType) updateTag(itemBuilder, WeaponTag.AMMO_TYPE, getAmmoTypeForTag());
		if (!updatedSkin) updateTag(itemBuilder, WeaponTag.SKIN, getSkinForTag());

		applySkinRendering(itemBuilder, player);

		// Attributes: re-applying on every rebuild must replace, not duplicate — applyAttributeModifiers keys each
		// modifier by a stable NamespacedKey derived from the weapon name + attribute, so this is idempotent.
		if (handlingData != null && !handlingData.getAttributes().isEmpty()) {
			applyAttributeModifiers(itemBuilder.build());
		}
	}

	/**
	 * Applies {@link #currentSkinState(Player)}'s custom model data and item model to {@code itemBuilder}
	 * (weapons-roadmap.md gate {@code HJ}).
	 */
	private void applySkinRendering(ItemBuilder itemBuilder, @Nullable Player player) {
		applySkinRendering(itemBuilder, currentSkinState(player));
	}

	/**
	 * Applies {@code state}'s custom model data and item model to {@code itemBuilder} — shared by
	 * {@link #buildItem(Player)} (always {@link SkinState#DEFAULT}) and {@link #updateWeaponData(ItemBuilder,
	 * Player)} (via {@link #currentSkinState(Player)}) so both apply skins through the same path (gate {@code HJ}
	 * review finding 6). A resolved custom model data of 0 (no {@code Default}/named override and no
	 * {@code Information.Custom_Model_Data}) clears any custom model data already on the item instead of leaving
	 * a previous state's value stuck on it (review finding 3). The item model write only touches {@link ItemMeta}
	 * — and only on a 1.21.2+ server, where {@code ItemMeta#setItemModel} exists as a real component — and only
	 * when the resolved key actually differs from what the stack already carries, so a rebuild that doesn't
	 * change skins never touches meta at all.
	 */
	private void applySkinRendering(ItemBuilder itemBuilder, SkinState state) {
		int resolvedCustomModelData = resolveCustomModelData(state);
		if (resolvedCustomModelData > 0) {
			itemBuilder.setCustomModelData(resolvedCustomModelData);
		} else {
			clearCustomModelData(itemBuilder);
		}

		if (!NmsVersion.current().atLeast(21, 2)) return;

		NamespacedKey resolvedItemModel = resolveItemModel(state);
		ItemStack     stack             = itemBuilder.build();
		ItemMeta      meta              = stack != null ? stack.getItemMeta() : null;
		if (meta == null || Objects.equals(readItemModel(meta), resolvedItemModel)) return;

		writeItemModel(meta, resolvedItemModel);
		stack.setItemMeta(meta);
	}

	// ItemMeta#getItemModel/#setItemModel are 1.21.2+ components; the compile floor is 1.16.5, so they are reached
	// reflectively behind the NmsVersion gate in applySkinRendering. Both are null on an older server and never invoked.
	private static final Method ITEM_MODEL_GETTER = lookupItemMetaMethod("getItemModel");
	private static final Method ITEM_MODEL_SETTER = lookupItemMetaMethod("setItemModel", NamespacedKey.class);

	@Nullable
	private static Method lookupItemMetaMethod(String name, Class<?>... parameterTypes) {
		try {
			return ItemMeta.class.getMethod(name, parameterTypes);
		} catch (NoSuchMethodException absent) {
			return null;
		}
	}

	@Nullable
	private static NamespacedKey readItemModel(ItemMeta meta) {
		if (ITEM_MODEL_GETTER == null) return null;
		try {
			return (NamespacedKey) ITEM_MODEL_GETTER.invoke(meta);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("ItemMeta#getItemModel is present but not invokable", e);
		}
	}

	private static void writeItemModel(ItemMeta meta, @Nullable NamespacedKey itemModel) {
		if (ITEM_MODEL_SETTER == null) return;
		try {
			ITEM_MODEL_SETTER.invoke(meta, itemModel);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("ItemMeta#setItemModel is present but not invokable", e);
		}
	}

	/**
	 * Clears a stale custom model data left over from a previously-rendered {@link SkinState} once the current
	 * state resolves to none at all (weapons-roadmap.md gate {@code HJ} review finding 3) —
	 * {@code ItemBuilder#setCustomModelData} only ever sets a positive value, so this is the only path that can
	 * take the component back off the item.
	 */
	private void clearCustomModelData(ItemBuilder itemBuilder) {
		ItemStack stack = itemBuilder.build();
		ItemMeta  meta  = stack != null ? stack.getItemMeta() : null;
		if (meta == null || !meta.hasCustomModelData()) return;

		meta.setCustomModelData(null);
		stack.setItemMeta(meta);
	}

	// --- Durability operations ---

	public void updateWeapon(Player player, ItemBuilder itemBuilder, int slot) {
		player.getInventory().setItem(slot, itemBuilder.build());
	}

	public void removeWeapon(Player player, int slot) {
		player.getInventory().setItem(slot, new ItemStack(Material.AIR));
	}

	public void increaseDurability(ItemBuilder itemBuilder, int amount) {
		durabilityCalculator.setDurability(itemBuilder, (short) (currentDurability + amount));
	}

	public void decreaseDurability(ItemBuilder itemBuilder, int amount) {
		durabilityCalculator.setDurability(itemBuilder, (short) (currentDurability - amount));
	}

	// --- Tag operations ---

	public boolean isBroken() {
		return currentDurability <= 0;
	}

	public void applyOnHitDurability(Player player, int slot) {
		int onShot = durabilityData.getOnShot();
		if (onShot <= 0) return;
		ItemStack item = player.getInventory().getItem(slot);
		if (item == null) return;
		ItemBuilder builder = new ItemBuilder(item);
		decreaseDurability(builder, onShot);
		updateWeapon(player, builder, slot);
	}

	// --- Copy / clone ---

	public boolean containsTag(ItemBuilder itemBuilder, WeaponTag tag) {
		return itemBuilder.hasNBTTag(getTagProperName(tag));
	}

	/**
	 * Base clone: shallow-copies via {@code Object.clone()}, then runs {@link #initClone(Weapon)}. Concrete subclasses
	 * override this, call {@code super.clone()} to get the base copy, then clone any additional mutable fields they
	 * own.
	 */
	@Override
	public Weapon clone() {
		try {
			Weapon copy = (Weapon) super.clone();
			copy.initClone(this);
			return copy;
		} catch (CloneNotSupportedException e) {
			throw new PluginException(e);
		}
	}

	// --- Comparable ---

	@Override
	public int compareTo(@NotNull Weapon other) {
		return Comparator.comparing(Weapon::getName, String.CASE_INSENSITIVE_ORDER)
		                 .thenComparing(Weapon::getCategory)
		                 .thenComparing(Weapon::getMaterial)
		                 .thenComparingInt(w -> w.durability)
		                 .compare(this, other);
	}

	@Override
	public String toString() {
		return String.format("Weapon{uuid='%s',name='%s',category='%s',material='%s'}", uuid, name, category, material);
	}

	public void applyPush(Player player) {
		// Never apply push if player is not on solid ground
		if (!isPlayerGrounded(player)) {
			return;
		}

		if (!player.isSneaking()) {
			push(player, recoilData.getPushPowerUp(), recoilData.getPushVelocity());
			return;
		}

		if (scopeData != null && scopeData.isScoped()) {
			push(player, recoilData.getPushPowerUp() / 2, recoilData.getPushVelocity() / 2);
		}
		// When sneaking and not scoped, no push is applied (implicitly returns)
	}

	/**
	 * Runs {@code text} through the injected placeholder resolver so configured PlaceholderAPI placeholders in
	 * the display name or lore are replaced before the item is rendered. Returns the original text unchanged when the
	 * resolver is absent or the input is empty.
	 */
	protected String resolvePlaceholder(@Nullable Player player, @Nullable String text) {
		if (text == null || text.isEmpty() || placeholder == null) return text;
		return placeholder.convert(player, text);
	}

	/**
	 * Resolves every line of a lore list via {@link #resolvePlaceholder(Player, String)}. Returns the original list
	 * unchanged when it is {@code null} or empty, or when the resolver is absent.
	 */
	protected List<String> resolvePlaceholder(@Nullable Player player, @Nullable List<String> loreLines) {
		if (loreLines == null || loreLines.isEmpty() || placeholder == null) return loreLines;
		List<String> resolved = new ArrayList<>(loreLines.size());
		for (String line : loreLines) {
			resolved.add(line == null ? null : placeholder.convert(player, line));
		}
		return resolved;
	}

	/**
	 * Shows ammo counter for any weapon that has reloadData configured.
	 */
	protected String buildDisplayName() {
		if (ammunitionData == null) return displayName + "&r";

		return String.format("%s&r &8«&6%d&7/&6%d&8»&r", displayName, currentMagCapacity,
		                     ammunitionData.getMaxMagCapacity());
	}

	protected SelectiveFire getSelectiveFireForTag() {
		return currentSelectiveFire != null ? currentSelectiveFire : SelectiveFire.SINGLE;
	}

	protected int getAmmoLeftForTag() {
		return currentMagCapacity;
	}

	/**
	 * The ammo id currently loaded into the magazine, per {@link Reload#getLoadedAmmunition()} — empty string
	 * when there is no reload configured or the ammo type is {@code none}.
	 */
	protected String getAmmoTypeForTag() {
		if (reload == null) return "";
		Ammunition loaded = reload.getLoadedAmmunition();
		return loaded != null ? loaded.getName() : "";
	}

	/**
	 * The name of the currently selected {@code Skins.Named} skin, or an empty string when none is selected.
	 */
	protected String getSkinForTag() {
		return selectedSkin != null ? selectedSkin : "";
	}

	protected void setUUID(UUID uuid) {
		this.uuid = uuid;
	}

	protected void updateTag(ItemBuilder itemBuilder, WeaponTag tag, Object value) {
		tags.replace(tag, value);
		itemBuilder.modifyTag(getTagProperName(tag), value);
	}

	protected void initializeTags(ItemBuilder itemBuilder) {
		tags.put(WeaponTag.UUID, uuid != null ? uuid.toString() : "");
		tags.put(WeaponTag.WEAPON, name);
		tags.put(WeaponTag.SELECTIVE_FIRE, getSelectiveFireForTag());
		tags.put(WeaponTag.AMMO_LEFT, getAmmoLeftForTag());
		tags.put(WeaponTag.AMMO_TYPE, getAmmoTypeForTag());
		tags.put(WeaponTag.SKIN, getSkinForTag());
		tags.forEach((tag, value) -> itemBuilder.addTag(getTagProperName(tag), value));
	}

	/**
	 * Re-initialises shared mutable data after a {@code super.clone()} shallow copy. Subclasses must call this inside
	 * their own {@code clone()} implementations.
	 */
	protected void initClone(Weapon source) {
		this.tags.clear();
		this.durabilityData      = source.durabilityData != null ? source.durabilityData.clone() : null;
		this.soundData           = source.soundData != null ? source.soundData.clone() : null;
		this.reloadActionBarData = source.reloadActionBarData != null ? source.reloadActionBarData.clone() : null;
		this.modifiersData       = source.modifiersData != null ? source.modifiersData.clone() : null;
		this.recoilData          = source.recoilData != null ? source.recoilData.clone() : null;
		this.scopeData           = source.scopeData != null ? source.scopeData.clone() : null;

		if (this.scopeData != null) {
			this.scopeData.setScoped(false);
			this.scopeData.setCurrentStack(0);
		}

		this.spreadData           = source.spreadData != null ? source.spreadData.clone() : null;
		// MuzzleOffsetData is a fully immutable record - sharing the same instance across template copies is
		// safe, no clone() needed.
		this.muzzleOffsetData     = source.muzzleOffsetData;
		this.effects              = source.effects != null ? source.effects.clone() : EffectsData.empty();
		this.hudData              = source.hudData != null ? source.hudData.clone() : null;
		this.handlingData         = source.handlingData != null ? source.handlingData.clone() : null;
		this.skinsData            = source.skinsData != null ? source.skinsData.clone() : null;
		// NamespacedKey is a fully immutable Bukkit value type - safe to share across template copies.
		this.itemModel            = source.itemModel;
		this.recoil               = new RecoilManager(this);
		this.spread               = new SpreadManager(this);
		this.durabilityCalculator = new DurabilityCalculator(this);

		// ammunitionData and reloadData are final — Object.clone() already shallow-copies them.
		// Only reset runtime state derived from them.
		this.currentMagCapacity = source.ammunitionData != null ? source.ammunitionData.getMaxMagCapacity() : 0;
		this.reload             = source.reload != null ? source.reload.clone() : null;
		if (this.reload != null) this.reload.rebindWeapon(this);
		this.currentSelectiveFire    = source.currentSelectiveFire;
		this.shootLockedUntilMillis  = 0L;
		// Runtime state, not carried over from the template - a fresh copy starts with no skin selected until
		// WeaponService#setWeaponData rehydrates it from the item's WeaponTag#SKIN.
		this.selectedSkin            = null;
	}

	protected void applyEffect(Player player, XPotion potion, int amplifier) {
		// PotionEffect.INFINITE_DURATION (-1) is 1.19.4+; the compile floor is 1.16.5. Integer.MAX_VALUE ticks never
		// runs out on any version, and removeEffect clears it explicitly.
		XPotion.of(potion.name())
		       .map(XPotion::getPotionEffectType)
		       .ifPresent(type -> player.addPotionEffect(
					   new PotionEffect(type, Integer.MAX_VALUE, amplifier)));
	}

	protected void removeEffect(Player player, XPotion potion) {
		XPotion.of(potion.name()).map(XPotion::getPotionEffectType).ifPresent(player::removePotionEffect);
	}

	/**
	 * {@code Information.Attributes} — Bukkit attribute modifiers applied to the main-hand item, via the shared
	 * {@link AttributeModifiers#apply} (weapons-roadmap.md gate {@code HL}, §2 — extracted so {@code Wearable}
	 * stamps its own {@code Attributes:} block through the exact same code path). Each entry is keyed by a
	 * {@link NamespacedKey} derived from the weapon name and the attribute itself, so calling this again (a rebuild
	 * via {@link #updateWeaponData}) replaces the previous modifier instead of stacking a duplicate.
	 */
	private void applyAttributeModifiers(@Nullable ItemStack item) {
		if (item == null || handlingData == null) return;
		AttributeModifiers.apply(item, handlingData.getAttributes(), EquipmentSlot.HAND, "attr_" + name);
	}

	private void push(Player player, double powerUp, double push) {
		// Safety check - clamp values to reasonable limits
		powerUp = Math.max(-0.5, Math.min(powerUp, 0.5));
		push    = Math.max(-0.5, Math.min(push, 0.5));

		if (push > 0) push *= -1;

		// Don't apply if values are effectively zero
		if (Math.abs(powerUp) < 0.001 && Math.abs(push) < 0.001) {
			return;
		}

		Location location  = player.getLocation();
		Vector   direction = location.getDirection().multiply(push);
		Vector   upward    = new Vector(0, powerUp, 0);
		Vector   velocity  = direction.add(upward);

		// Clamp final velocity to prevent "moved too quickly" warnings
		double maxSpeed = 1.0;
		if (velocity.length() > maxSpeed) {
			velocity.normalize().multiply(maxSpeed);
		}

		player.setVelocity(velocity);
	}

	/**
	 * Checks if the player is firmly on the ground. Returns false if jumping, flying, in creative flight, swimming,
	 * etc.
	 */
	private boolean isPlayerGrounded(Player player) {
		// Check if player is flying (creative/spectator or elytra)
		if (player.isFlying() || player.isGliding()) {
			return false;
		}

		// Check if player is swimming or in water
		if (player.isSwimming() || player.isInWater()) {
			return false;
		}

		// Check if player is climbing (ladders, vines). LivingEntity#isClimbing is 1.17+; the compile floor is 1.16.5,
		// and the climbable block tag has existed since 1.16. The tag is null off-server (unit tests).
		Tag<Material> climbable = Tag.CLIMBABLE;
		if (climbable != null && climbable.isTagged(player.getLocation().getBlock().getType())) {
			return false;
		}

		// Check the block below - must be solid ground
		Location playerLoc  = player.getLocation();
		Block    blockBelow = playerLoc.subtract(0, 0.1, 0).getBlock();

		if (!blockBelow.getType().isSolid()) {
			return false;
		}

		// Check player's Y velocity - if moving up or down significantly, not grounded
		double yVelocity = player.getVelocity().getY();
		return !(Math.abs(yVelocity) > 0.1);
	}

}
