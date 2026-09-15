# Bartizan API

`bartizan-api` (`org.luckyraven.bartizan:bartizan-api:0.1.0`, `provided` scope) is the only artifact a consumer
plugin depends on. It carries zero `net.minecraft.*` / `org.bukkit.craftbukkit.*` symbols and zero
`org.luckyraven.gangland.*` symbols — the jar is never Paper-remapped, so an NMS symbol here would break every
consumer on Paper.

```xml
<dependency>
    <groupId>org.luckyraven.bartizan</groupId>
    <artifactId>bartizan-api</artifactId>
    <version>0.1.0</version>
    <scope>provided</scope>
</dependency>
```

`bartizan-plugin` (the runtime, shaded into `Bartizan-0.1.0.jar`) is never a dependency of anything else — nothing
outside `bartizan-plugin` may name its classes.

## Resolving `BartizanApi`

`BartizanApi` is registered on Bukkit's `ServicesManager` at `ServicePriority.Normal` once Bartizan enables. Every
lookup must be **lazy and never cached at construction** — a consumer plugin may enable before or after Bartizan,
and Bartizan may be absent entirely (a supported configuration; see the vocabulary note below):

```java
RegisteredServiceProvider<BartizanApi> rsp =
        Bukkit.getServicesManager().getRegistration(BartizanApi.class);
if (rsp == null) return;              // Bartizan absent or not yet enabled — never cache this at construction
BartizanApi api = rsp.getProvider();

Weapon rifle = api.weapons().getWeaponTemplate("rifle");
```

## Service table

| Service class | Registered by | Priority | Consumed by |
|---|---|---|---|
| `org.luckyraven.bartizan.api.BartizanApi` | `WiringConfig.bartizanApi(...)` | `Normal` | Any consumer plugin (Gangland's `civilians`, `copsncrooks`, `gadget` modules) |
| `org.luckyraven.keystone.item.spi.ItemVocabulary` | `ItemConfig.bartizanItemVocabulary(...)` | `Normal` | Keystone-side item vocabulary folding (a consumer's `ItemConfig.itemVocabularies(...)`) |
| `org.luckyraven.bartizan.api.raytrace.WeaponRaytracer` | `WiringConfig.weaponRaytracer(...)` | `Normal` | Bartizan's own `NpcWeaponControllerImpl`; published for third-party consumers. No consumer is required to use it — the only historical Gangland caller was the static `WeaponRaytracer.isRaytraceDamageInProgress()`. |
| `org.luckyraven.bartizan.api.combat.CombatEligibility` | **The consumer** registers it; Bartizan **pulls** it (`CombatEligibility.resolve()`) | `Normal` | Bartizan's `WeaponInteract`, `NumberedReload`, `InstantReload` |

Direction is fixed: Bartizan publishes the first three, and pulls the fourth. Bartizan never looks up a consumer's
class by name — it only reads a small number of consumer-implemented interfaces it defines itself
(`CombatEligibility`).

`CombatEligibility.resolve()` is null-safe with no server installed and falls back to `CombatEligibility.DEFAULT`
(`player -> !player.isDead()`) when no consumer has registered one — a downed-player gate, a PvP-zone gate, or any
other "can this player currently be hit" rule is entirely the consumer's choice to implement or skip.

## `BartizanApi`'s five accessors, plus gate-`HD`/`HH` convenience methods

```java
public interface BartizanApi {
    WeaponCatalog weapons();
    WearableCatalog wearables();
    AmmunitionCatalog ammunition();
    NpcWeaponFactory npcWeapons();
    WeaponItemApi items();

    // gate HD
    @Nullable Weapon getHeldWeapon(Player player);
    boolean isScoping(Player player);
    boolean isReloading(Player player);

    // gate HH
    boolean tryReload(Player player);

    // gate HJ
    boolean setSkin(Player player, @Nullable String name);
    @Nullable String getSkin(Player player);
}
```

`getHeldWeapon`/`isScoping`/`isReloading` (added at gate `HD`, alongside the HUD feature) are a shortcut over
`weapons().validateAndGetWeapon(player, item)` for the common "what is this player currently holding/doing" query —
`getHeldWeapon` checks the main hand, then the off hand; `isScoping`/`isReloading` read that weapon's
`ScopeData`/`Reload` state. Like `validateAndGetWeapon`, `getHeldWeapon` is **not** read-only: it can mint and
register a live `Weapon` instance for an item that has no runtime registry entry yet.

`tryReload` (gate `HH`) starts a reload for the held weapon through the same guarded path
`WeaponDroppedListener`/`Reload.Auto_Reload_When_Empty` already use (`WeaponService.tryReload`): a no-op while
already reloading, the magazine is full, or the player carries none of the configured ammo and isn't in creative
mode. Returns `false` when the player holds no weapon or the reload was refused.

> `NpcWeaponFactory.create` throws `IllegalArgumentException` for a weapon name that is not configured; call `items().isValidWeaponName(name)` first (Gangland's `BartizanNpcWeapons` does, returning `NpcRangedAttack.NONE`).


| Accessor | Returns | Members |
|---|---|---|
| `weapons()` | `weapon.WeaponCatalog` | `getWeaponTemplate(String)`, `getWeaponTemplates()`, `createTransientWeapon(String)`, `isWeapon(ItemStack)` — read-only lookups: each may return `null`/empty for an unknown name, and none of them mints or registers a weapon instance. `validateAndGetWeapon(Player, ItemStack)` is **not** read-only — it resolves the held item's uuid and, when that uuid is not yet in the runtime registry, mints and registers a live `Weapon` instance for it (`WeaponService.getWeapon` → `weapons.put`); use `getWeaponTemplate(...)` instead for a read-only lookup. |
| `wearables()` | `wearable.WearableCatalog` | `getWearable(String)`, `getWearables()`, `resolveWearable(@Nullable ItemStack)`, `applyWearableReduction(double, LivingEntity, boolean)`, `reduceCritBonus(double, LivingEntity)`, `reduceFireTicks(int, LivingEntity)` |
| `ammunition()` | `ammo.AmmunitionCatalog` | `getAmmunitionKeys()`, `getAmmunition(String)` |
| `npcWeapons()` | `npc.NpcWeaponFactory` | `create(LivingEntity shooter, String weaponName, double fireRateMultiplier, double aimErrorDegrees)` → `npc.NpcWeaponController extends org.luckyraven.keystone.npc.spi.NpcRangedAttack` — the sole implementation of that Keystone SPI. A consumer with no Bartizan installed uses `NpcRangedAttack.NONE` instead of calling this accessor. |
| `getHeldWeapon(Player)` | `@Nullable weapon.Weapon` | Gate `HD`. Main hand, then off hand; `null` when neither holds a valid weapon. |
| `isScoping(Player)` | `boolean` | Gate `HD`. `getHeldWeapon(player)` scoped in. |
| `isReloading(Player)` | `boolean` | Gate `HD`. `getHeldWeapon(player)` mid-reload. |
| `tryReload(Player)` | `boolean` | Gate `HH`. Starts a reload for `getHeldWeapon(player)` via `WeaponService.tryReload`; `false` when no weapon is held or the reload was refused. |
| `setSkin(Player, String)` | `boolean` | Gate `HJ`. Sets `getHeldWeapon(player)`'s selected `Skins.Named` skin (`null`/empty clears it) and persists the item; `false` when the player holds no weapon or the name isn't one of that weapon's configured `Skins.Named` entries. |
| `getSkin(Player)` | `@Nullable String` | Gate `HJ`. `getHeldWeapon(player)`'s currently selected `Skins.Named` skin, or `null` when none is selected. |
| `items()` | `item.WeaponItemApi` | `buildItem(String)`, `isValidWeaponName(String)`, `isSameWeapon(ItemStack, ItemStack)`, `cleanDisplayName(ItemStack)` — see the worked example below. |

### `WeaponItemApi` notes

- `buildItem(String weaponName)` reproduces the throwable-UUID determinism rule
  (`UUID.nameUUIDFromBytes("throwable:" + name)`) so two throwable items of the same type keep stacking. Returns
  `null` for an unknown name.
- `isSameWeapon(ItemStack a, ItemStack b)` is **read-only**: it resolves each side's configured template and
  compares name/category/material/durability, and never mints or registers a weapon in the runtime's weapon
  registry as a side effect of the comparison — safe to call once per
  item from sign/shop similarity code without growing state.
- `cleanDisplayName(ItemStack item)` reads the item's weapon-name NBT tag, looks up the configured template's
  display name and returns it colored (`&` codes translated). Returns `null` for a stack that carries no weapon
  tag, or whose template has no display name configured.

### Domain types a consumer may name

`weapon.Weapon` and its six subclasses (`GunWeapon`, `MeleeWeapon`, `BiologicalWeapon`, `IncendiaryWeapon`,
`ThrowableWeapon`, `BeamWeapon` — gate `HC`), `weapon.{WeaponType, ThrowableType, SelectiveFire, WeaponTag,
ProjectileType, ProjectileState}` (`WeaponType.BEAM`, aliases `"beam"`/`"laser"`, added at gate `HC`), the
`weapon.dto.*` records (including `ChargeData` — `timePerLevel`, `maxLevel`, `minLevelToFire`, `autoFireAtMax` —
the charge-then-release config `BiologicalData#getCharge()` and `BeamWeapon#getCharge()` both carry, and
`BeamData` — `range`, `width`, `ammoPerLevel`, `pierce`/`damage`/`preview`/`render` nested records, `scorchBlocks`
— `BeamWeapon#getBeam()`'s config since gate `HC`), `ammo.Ammunition`, `wearable.Wearable` (string trait keys via
`traits()`/`traitLevel(String)` — including the gate-`HB` `sealed` trait, which reduces the incoming level of a
biological status rather than a damage/duration percentage; jetpack-style extra data via `extraTags()` — NBT keys
`fuel`/`fuel_current`/`fuel_max` are unchanged from the old `Jetpack:` block, so a consumer's fuel-reading code
needs no edit; see "Wearables" below for the gate-`HL` additions — `Attributes`/`Set`/`Effects_While_Worn`/
`Effects`, three new trait keys), `BartizanItemPredicates.WEARABLE`.

`Weapon#getHandlingData()` (nullable, gate `HE`) carries the interaction-handling rules — `Equip_Delay`,
`Deny_Use_In_Crafting`, `Cancel.*`, `Attributes`, `Trigger`, `Circumstance`, `Destroy_When_Empty`,
`Reset_Fall_Distance` — parsed from a weapon's `Information:`/`Shoot:` sections.

### Skins (`weapon.SkinState`, `weapon.dto.SkinsData`, `weapon.WeaponTag`, gate `HJ`)

`Weapon#getSkinsData()` (nullable) is a weapon's parsed `Skins:` section: a per-`SkinState`
(`DEFAULT`/`SCOPE`/`RELOAD`/`SPRINT`/`NO_AMMO`) custom-model-data table, plus a `named(String)` lookup of
player-selectable `Skins.Named` entries (`SkinsData.NamedSkin` — its own per-state overrides and an optional
`Item_Model` override). `null` means the weapon has no `Skins:` block and renders entirely through
`Information.Custom_Model_Data`/`Item_Model`.

`Weapon#currentSkinState(@Nullable Player)` resolves the active state in a fixed priority order — reloading beats
being scoped in, which beats an empty magazine, which beats sprinting, which falls back to `DEFAULT`.
`Weapon#resolveCustomModelData(SkinState)`/`#resolveItemModel(SkinState)` consult the selected named skin (see
below) first, then the root `Skins:` table, then `Information.Custom_Model_Data`/`Item_Model`.
`Weapon#setSelectedSkin(@Nullable String)` validates a name against `getSkinsData().named(String)` and returns
`false` (leaving the current selection untouched) for an unrecognised one; `null`/empty clears the selection.
`WeaponTag.SKIN` (dynamic) persists the selection across a relog/drop+pickup the same way `AMMO_TYPE` does.

`BartizanApi.setSkin(Player, String)`/`getSkin(Player)` (above) are the cross-plugin entry point; in-plugin,
`/bartizan weapon skin <name|default>` does the same against the sender's held weapon. `Item_Model` (both
`Information.Item_Model` and a named skin's override) only takes effect on a 1.21.2+ server — `ItemMeta#setItemModel`
doesn't exist before that — and is otherwise silently ignored (with a startup warning for the root
`Information.Item_Model` case).

### Ammo type list + reload parity (`weapon.dto.AmmunitionData`/`ReloadData`, `weapon.WeaponTag`, gate `HG`)

`AmmunitionData#getAmmoType()` (single `Ammunition`, nullable) is now a convenience accessor over
`getAmmoTypes()` (`List<Ammunition>`, possibly empty) — `Ammunition.Types` in the weapon YAML configures more than
one accepted ammo item, consumed in list order; an empty list is `Ammo_Type: none` (infinite supply, no item
tracked). The two-arg `AmmunitionData(Ammunition, int, int, int)` constructor is unchanged for the single-type
case. `ReloadData` gained three fields or their `Reload:` YAML keys: `isUnloadAmmoOnReload()`
(`Unload_Ammo_On_Reload`), `getShootDelayAfterReload()` (`Shoot_Delay_After_Reload`, ticks), and
`isAutoReloadWhenEmpty()` (`Auto_Reload_When_Empty`) — all default `false`/`0`. `WeaponTag` gained `AMMO_TYPE`
(dynamic), the ammo id currently loaded into the magazine, alongside the existing `AMMO_LEFT`. `Weapon` gained
`isShootLocked()`, backing `Shoot_Delay_After_Reload`.

### Biological status (`weapon.dto.StatusData`, gate `HB`)

`BiologicalData#getStatus()` (never `null`) is the tracked status — infection, radiation, whatever the weapon
names it — a biological weapon's hit applies, and `BiologicalData#isCumulativeLevels()` controls whether a release
at charge level N applies just that level's `Effects_Per_Level` entry or every entry from `1..N` merged (strongest
amplifier, longest duration per potion type). `StatusData` carries `name`/`icon`, `durationPerLevel` (ticks),
`stacking` (`Stacking`: `REFRESH`/`EXTEND`/`ESCALATE`/`IGNORE`), `maxLevel`, `killCreditWindow` (ticks),
`@Nullable contagion` (`ContagionData`: `radius`, `chance`, `interval`, `levelDrop`), `cure` (`CureData`: `items`
material names, `@Nullable wearableTrait`), `bossBar` (`BossBarData`: `text`/`color`/`style` — `color`/`style` are
`org.bukkit.boss.BarColor`/`BarStyle` names, resolved by `bartizan-plugin`'s `StatusEffectService`), and the
ambient-particle/`messageSpread` fields the service ticks. The runtime that owns live statuses
(`status.StatusEffectService`, `status.ActiveStatus`, `status.StatusListener`) lives in `bartizan-plugin`.

### Effects (`weapon.dto.{EffectHook, EffectSpec, EffectsData}`)

Gate `HA`'s feedback engine model, added at the api layer so a consumer can read what a weapon will do without
depending on `bartizan-plugin`. `EffectHook` is the v1 set of ~22 feedback hooks (`ON_SHOOT`, `ON_HIT`,
`ON_BLOCK_HIT` (added gate `HE` part b — a hitscan ray striking a block), `ON_KILL`, `ON_CRITICAL`, …);
`key()`/`fromKey(String)` round-trip the `Capitalized_Underscore` YAML spelling (`On_Shoot`).
`EffectSpec` is an immutable `(type, args)` record for one configured effect entry, with typed arg accessors (`arg`,
`intArg`, `doubleArg`). `EffectsData` is the hook → effect-list table (`forHook`, `has`, `put`, `empty()`).
`Weapon#getEffects()` exposes a weapon's parsed `Effects:` section (never `null`, empty when none configured). The
runtime engine that reads these (`effect.EffectRunner` and its 15 hook effects) lives in `bartizan-plugin` and is
wired into every firing action, listener and NPC controller (weapons-roadmap.md gate `HA`), including `On_Critical`
on a critical hit.

Since gate `HA`, the legacy `Shoot.Sound.*`/`Reload.Sound.*` slots are lowered into their `Effects:` hook by the
loader and played by `EffectRunner`/the plugin's listeners rather than by `bartizan-api`'s `Reload` itself — a
weapon's own `Effects:` list for a hook still replaces the lowered entry entirely, never merges with it.

### Damage parity (`weapon.dto.{DamageData, DropoffStep, ThrowableData}`, `weapon.modifiers.DamageMath`, `EffectHook`, gate `HF`)

`EffectHook` gained four zone hooks: `ON_ARMS`, `ON_LEGS`, `ON_FEET`, `ON_BACK` (`ON_HEADSHOT` is unchanged and
still covers the head zone; a body-zone hit fires no extra hook). `DamageData` gained `getDropoff()`
(`List<DropoffStep>`, never `null`), `getBodyDamage()`/`getArmsDamage()`/`getLegsDamage()`/`getFeetDamage()`/
`getBackDamage()` (hit-zone deltas alongside the existing `getHeadDamage()`), `getArmorDamage()` (armour
durability damage per hit, guns only), `isOwnerImmunity()`/`isIgnoreTeams()`, and `getKnockback()` (`@Nullable
Double` — `null` leaves vanilla knockback untouched; any present value, `0` included, replaces it with
`shotDir * knockback` on a direct hit). `ThrowableData` gained the same `isOwnerImmunity()`/`isIgnoreTeams()`
pair plus a primitive `getKnockback()` (an additive falloff vector, not a vanilla-knockback override — grenades
have no "disable vanilla knockback" concept). `DropoffStep(double distance, double delta)` is a new record; its
`parse(String)` reads one `"<distance> <delta>"` `Damage.Dropoff` entry, returning `null` on malformed input.
`DamageMath` (new, `weapon.modifiers`) holds the pure math: `dropoff(List<DropoffStep>, double distance)`,
`percentMultiplier(double percentSum)` (the `settings.yml Damage_Modifiers` formula, floored at `0`), and
`explosionKnockbackFactor(double knockback, double distance, double radius)` (shared by rocket and grenade
explosions). The hit-zone classification itself (a `back` flag plus which zone — `HEAD`/`BODY`/`ARMS`/`LEGS`/
`FEET`) and the `Owner_Immunity`/`Ignore_Teams` skip rule live in `bartizan-plugin` (`raytrace.HitZone`,
`weapon.DamageRules`), but the zone enum itself moved to `bartizan-api.weapon.BodyZone` at gate `HK` —
`WeaponEntityDamageEvent#getZone()` needed it in its own public signature, so unlike `HitZone`/`DamageRules`
(neither referenced by an api type, so neither moved) it could not stay plugin-only. `HitZone`'s own `zone()`
accessor now returns `BodyZone` rather than a plugin-local enum.

### Explosion parity (`weapon.dto.ExplosionData`, `weapon.modifiers.ExplosionMath`, gate `HI-a`)

Unifies the two explosion paths guns (rockets) and throwables (grenades) used to run independently into one
`ExplosionData` both `GunWeapon` and `ThrowableWeapon` carry (`getExplosionData()`, default-constructed like
`getDamageData()`/`getThrowableData()`). `ExplosionData` holds `radius`, `damage`, `fireTicks`, `shape`
(`SPHERE`/`CUBE`/`FLAT` — see below for the per-category default), `exposure` (`Exposure.DISTANCE` default |
`LINE_OF_SIGHT` — a blocked line of sight to the blast centre zeroes a victim's damage regardless of distance;
the check ignores passable blocks such as grass/torches/signs), `blockDamage` (default `false`), `knockback`
(`@Nullable Double`, same falloff-vector semantics as `DamageData#getKnockback()`), `ownerImmunity`/`ignoreTeams`,
and three nested records: `@Nullable Cluster(count, speed, delayTicks)` and `@Nullable Airstrike(count, height,
radius, delayTicks)` (sub-munitions spawned on detonation, depth-guarded so a sub-munition never itself spawns
another), and `Detonation(Set<Trigger> impactWhen, delayAfterImpactTicks, fuseTicks)` — `Trigger` is
`BLOCK`/`ENTITY`/`SPAWN` (`SPAWN` reserved, nothing produces it yet; `BLOCK` fires on a landing *or* a wall/ceiling
hit, not landing only). The legacy `Damage.Explosion_*`/`Throw.Explosion_*` YAML keys still populate their own
DTOs (`DamageData`, `ThrowableData`) unchanged — a consumer reading those keeps working — but the explosion
*runtime* (`bartizan-plugin`'s `ExplosionHandler`) reads only `ExplosionData`, lowered from those legacy keys by
the plugin's `ExplosionSectionParser` so an unconfigured weapon behaves exactly as it did before this gate. That
legacy lowering's per-category defaults matter for `shape`/`knockback`/`fireTicks` specifically: guns default to
`Shape.SPHERE` (the old rocket linear falloff) with an explosion `fireTicks` of `0` (the old rocket blast never
set victims on fire — only a direct hit does, via `DamageData.fireTicks`); throwables default to `Shape.FLAT`
(full `damage` anywhere inside `radius`, the old grenade behaviour) and a `knockback` of `2.0` when `Throw
.Knockback` is unset (the old grenade code's hardcoded thrower-push strength, now applied to every victim in
range via the unified handler, not just the thrower). Since gate `HI-a` deletes the vanilla
`World#createExplosion` blast a throwable used to fire *in addition to* its own damage loop, a grenade's
`Explosion_Damage` is now the full amount a victim inside `radius` takes — retune it on a server that relied on
the extra vanilla-blast damage stacking on top.

`ExplosionMath` (new, `weapon.modifiers`, pure like `DamageMath`) holds `damageAt(Shape, radius, damage, Vector
offset)` (Euclidean distance for `SPHERE`/`FLAT`, Chebyshev/max-axis distance for `CUBE`; `SPHERE`/`CUBE` taper
linearly to `0` at `radius`, `FLAT` deals full `damage` anywhere inside `radius` and `0` outside) and
`sphereContains`/`cubeContains`.

Throwables (grenades) detonate through `ThrowableAction`'s own item-physics flight loop, never through
`WeaponRaytracer` — unlike rockets (`SteppedProjectileTask`), a throwable never fires `WeaponRaytraceImpactEvent`;
listen for `WeaponEntityDamageEvent`/`ON_EXPLODE` instead.

### Wearables (`wearable.Wearable`, `item.AttributeModifiers`, gate `HL`)

`Wearable` gained four fields, all parsed by the now-`NodeReader`/`ConfigReport`-backed `WearableAddon` loader
(`bartizan-plugin`) instead of the old bare-`log.warn` one — a bad entry is reported like a bad weapon:

- `getAttributes()` (`List<weapon.dto.HandlingData.AttributeEntry>`, never `null`) — the wearable's `Attributes:`
  map (`Armor: 4.0`, `Armor_Toughness`, `Knockback_Resistance`, `Max_Health`, …), each entry an `ADD_NUMBER`
  modifier. Stamped by `buildItem()` via the new `item.AttributeModifiers.apply(ItemStack, List<AttributeEntry>,
  EquipmentSlotGroup, String keyPrefix)` — the same helper `Weapon#applyAttributeModifiers` now delegates to, so a
  weapon's `Information.Attributes` and a wearable's `Attributes:` stamp modifiers identically (stable
  per-attribute `NamespacedKey`, survives a rebuild without duplicating). `Wearable.armorSlotGroup(Material)`
  derives the slot group from the piece's own material (`*_HELMET`→`HEAD`, `*_CHESTPLATE`/`ELYTRA`→`CHEST`,
  `*_LEGGINGS`→`LEGS`, `*_BOOTS`→`FEET`) rather than the weapon path's fixed `MAINHAND`.
- `getSet()` (`@Nullable String`, lower-case) — the `Sets.<name>` this piece counts a worn piece towards.
- `getEffectsWhileWorn()` (`List<String>`, never `null`) — raw `EFFECT-duration-amplifier` tokens, re-applied
  periodically while worn by `bartizan-plugin`'s new `wearable.WearableEffectsService` (duration always replaced
  by a short refresh window; a raw amplifier of `-1` skips that one token).
- `getEffects()` (`weapon.dto.EffectsData`, never `null`) — a wearable's own `Effects:` block, parsed by the same
  `EffectsSectionParser` a weapon's `Effects:` goes through, scoped to three hooks: `ON_EQUIP`, `ON_UNEQUIP`
  (new), `ON_HIT_TAKEN` (new).

Three new trait keys join the existing seven in `Wearable`'s `[maxLevel, perLevel]` table: `insulated` (`3, 0.08`
— beam/energy damage reduction), `night_vision` (`1, 0` — worn effect only, no numeric bonus), `swift` (`3, 0.05`
— a `MOVEMENT_SPEED` `ADD_SCALAR` modifier the loader appends to `getAttributes()` at load time, not read from
YAML directly). `Wearable.traitBonusForLevel(String key, int level)` (new, `public static`) is the arithmetic half
of the existing per-piece trait math, exposed so `WearableService` can apply it to a body-wide resolved level
(every worn piece's own level of a trait, plus an active set bonus, capped once at `traitMaxLevel`) instead of
only one piece's own level — `WearableService.resolveTraitLevels(LivingEntity)` is the one place that resolution
happens now, and `applyWearableReduction`/`traitLevel`/`reduceCritBonus`/`reduceFireTicks`/the new
`applyInsulatedReduction` all read through it. `WearableService.SetTier`/`registerSet`/`activeSetTiers` (the
`Sets:` runtime) and `WearableEffectsService` are plugin-only — not part of the api surface, since nothing in
`WearableCatalog`'s own signature requires them.

`weapon.dto.EffectHook` gained `ON_UNEQUIP` and `ON_HIT_TAKEN` (28 hooks total). `effect.EffectContext.getWeapon()`
(`bartizan-plugin`) is now `@Nullable` — a wearable's equip/unequip/hit-taken context has no weapon — and
`%weapon%` falls back to a new `ownerName` field (the wearable's own display name) when `weapon` is absent.
`effect.EffectRunner` gained a `run(EffectsData, String ownerName, EffectHook, EffectContext)` overload alongside
the existing `run(Weapon, EffectHook, EffectContext)`, for exactly this non-weapon-owner case.

### Events (`org.luckyraven.bartizan.api.event`)

`WeaponEvent`, `WeaponShootEvent`, `WeaponRaytraceImpactEvent` (cancelling suppresses damage only — penetration and
ricochet counters still advance), `WeaponEntityDamageEvent`, `WeaponKillEntityEvent`, `WeaponAssistEvent` (gate
`HK`), `WeaponReloadEvent` / `WeaponReloadStartEvent` / `WeaponReloadCompleteEvent`, `WeaponChangeSelectiveFireEvent`,
`WeaponChargeLevelEvent`, `WeaponBeamFireEvent`.
`WeaponStatusApplyEvent`, `WeaponStatusExpireEvent`.

`WeaponStatusApplyEvent` (weapon, `@Nullable` shooter, victim, `level`; cancellable) fires before
`StatusEffectService` (re)applies a biological status — cancelling suppresses the whole application: no stacking,
no feedback, no potion payload. `WeaponStatusExpireEvent` (weapon, victim, `reason`; not cancellable) fires
whenever a status stops being active — `reason` is `EXPIRED`, `CURED` (a `Status.Cure.Items`-listed item
consumed), `DEATH`, or `QUIT` (weapons-roadmap.md gate `HB`, §2.2).

`WeaponChargeLevelEvent` (weapon, player, `level`, `maxLevel`; not cancellable) fires on every charge-level
increment for a charge-then-release weapon — `bartizan-plugin`'s `ChargeController` (biological and beam, gate
`HC`) raises it alongside the `On_Charge_Level`/`On_Charge_Full` effect hooks.

`WeaponBeamFireEvent` (weapon, player, `level`, `origin`, `direction`; cancellable) fires just before a beam's ray
is cast, gate `HC`, before ammo is consumed (fixed at gate `HK` review — previously the magazine was decremented
first with no refund on cancel) — cancelling it, or the `WeaponShootEvent` fired right after, costs the shooter
nothing. It deviates from
weapons-roadmap.md §3.2's description of an event that "carries level and the ordered target list": the beam ray
streams hits one at a time through `RaytraceRequest`'s impact handler rather than pre-computing a target list
before firing, so there is no target list to carry — only `level`, `origin` and `direction` at fire time.

`WeaponReloadCompleteEvent#isInterrupted()` (new at gate `HA`) is `true` when the completion was raised by a
swap-cancelled reload (`Reload#endReloading(Player, boolean)`) rather than a normal reload finishing — Bartizan's
own `WeaponReloadListener` skips `ON_RELOAD_END` in that case, since `ON_RELOAD_CANCEL` is the feedback hook for it.

`WeaponEntityDamageEvent.weaponName()` / `.kind()` replace the old `ThrowableAction` static maps
(`pendingKillerWeapon`, `pendingVehicleExplosionDamage`) — the firing action stamps both at construction time, so a
listener never looks up a short-lived side table keyed by entity UUID. `kind()` returns a `DamageKind` enum
(`DIRECT`, `EXPLOSION`, `FIRE`, `BIOLOGICAL`, `MELEE`). As of gate `HK` every value is actually produced:
`DIRECT` by the default raytracer damage path (guns) and by `BeamAction`'s short-circuited impact handler,
`EXPLOSION` by the unified `ExplosionHandler` (gate `HI-a`) for every living-entity hit of any explosion — rockets
and throwables alike, `FIRE` by `IncendiaryAction`, `BIOLOGICAL` by `BiologicalAction`, and `MELEE` by
`MeleeAction` — each fires it only for a `Player` shooter, after it has applied its own damage. `getZone()`
(`@Nullable` `BodyZone` — `HEAD`/`BODY`/`ARMS`/`LEGS`/`FEET`, promoted from the plugin-only `raytrace.HitZone`
record) and `getDistance()` are new at gate `HK`; both are `null`/`0` unless the firing path computed them (guns
and beams carry a real zone, the other three custom-path actions carry `null` — they have no single impact
direction to classify a zone against). The pre-`HK` six-argument constructor still exists and delegates with
`null`/`0`, so an existing caller compiles unchanged.

`WeaponAssistEvent` (`weaponName`, `assister: Player`, `victim: LivingEntity`, `@Nullable killer: Entity`; not
cancellable, gate `HK`) is fired by `stats.StatsService` when a player who damaged a victim within
`settings.yml Stats.Assist_Window_Ticks` of that victim's death (recorded off `WeaponEntityDamageEvent`, keyed by
`victim -> attacker -> last hit`) did not land the kill themselves.

`stats.StatsService`/`WeaponStat`'s accuracy (`hits * 100.0 / shots`, `StatsCommand`): `shots` counts one
`WeaponShootEvent` per trigger pull, but a `SPREAD` (shotgun) shot can register several `hits` — one per pellet
that lands — from that single pull, so a `SPREAD` weapon's accuracy can read above 100%. Deliberately not
"fixed" by counting `Pellets` shots per pull instead — `WeaponShootEvent` fires once per pull, and inflating
`shots` to match `hits`' granularity would just move the mismatch onto every other stat that assumes one shot per
pull (e.g. ammo/damage-per-shot displays elsewhere).

### `raytrace.WeaponRaytracer`

`getVisualSpawner()`, `fireInstant(RaytraceRequest)`, `advanceSegment(RaytraceContext, Location from, Location to)`,
plus the static `isRaytraceDamageInProgress()` / `setRaytraceDamageInProgress(boolean)` pair (a thread-local flag a
raytracer implementation sets around its own `LivingEntity#damage` call, so a listener reacting to
`EntityDamageByEntityEvent` can skip and rely on `WeaponRaytraceImpactEvent` instead). Everything else under
`org.luckyraven.bartizan.api.raytrace` (`RaytraceContext`, `RaytraceRequest`, `WeaponVisualSpawner`) supports this
interface's own signature; `WeaponShooting`, `WeaponMuzzle` and `SteppedProjectileTask` are **not** part of the api
— they moved to `bartizan-plugin`'s `org.luckyraven.bartizan.raytrace` package at gate GD once their only external
caller (a Gangland NPC combat delegate) became Bartizan's own `NpcWeaponControllerImpl`.

Since gate `HA`, `fireInstant` returns `boolean` — `hitEntity`, whether a living entity took the hit (the impact
event was not cancelled and, on the default damage path, the damage was not blocked). The raytracer itself no
longer fires `ON_MISS`; the firing action decides whether and when to run it off this return value.

### `raytrace.WeaponVisualSpawner` — `spawnVisual` (gate `HI` part b)

`spawnVisual(VisualData, Location, Vector direction)` spawns the cosmetic entity a stepped slow projectile
(`SteppedProjectileTask` in `bartizan-plugin`) drives for `Shoot.Projectile.Visual` — `FIREBALL`/`FIREWORK` (the
historical ROCKET/FLARE visuals) plus four new entity kinds: `DROPPED_ITEM`, `FALLING_BLOCK`, `ARMOR_STAND` and
`PRIMED_TNT`. Unlike the older `spawnCosmetic(Class<T extends Projectile>, LivingEntity, Location, Vector)` (kept
as-is for callers that still want vanilla-physics-driven Projectile visuals), the caller repositions the returned
entity itself every tick — "server path is the truth" — so `spawnVisual` only sets up a plausible first frame
(silent, no gravity, no vanilla interaction) and takes no `shooter` parameter. Every entity either method returns
is registered cosmetic the same way, so `ProjectileDamageListener` ignores it regardless of which method spawned
it.

`VisualData(VisualType type, @Nullable Material item, int customModelData, @Nullable Material block)` and
`BouncyData(double defaultMultiplier, Map<Material, Double> perMaterial)` (`weapon.dto`) are the two new records
backing this — `ProjectileData#getVisual()`/`#getBouncy()`. Both are parsed by `bartizan-plugin`'s
`GunWeaponParser`, which always fills in a non-`null` `VisualData` (type-based default when `Visual:` is absent or
malformed) for any `ProjectileData` it builds; a `null` `visual`/`bouncy` therefore only occurs on a bare
`ProjectileData` built directly by a test fixture or other code outside that parser.

## Item vocabulary (`weapon:` / `ammo:` / `wearable:` strings)

Bartizan registers a Keystone `org.luckyraven.keystone.item.spi.ItemVocabulary` (`namespace() == "bartizan"`) so
loot chests, shops and signs elsewhere keep resolving `weapon:<name>`, `ammo:<name>` / `ammunition:<name>` and
`wearable:<name>` item definition strings after installing Bartizan as a separate plugin:

| Kind | Registered names / predicate | Priority |
|---|---|---|
| converter | `"weapon"` | — |
| converter | `"ammunition"`, `"ammo"` (alias) | — |
| converter | `"wearable"` | — |
| serializer | `WeaponItemPredicates.WEAPON` | `0` |
| serializer | `WeaponItemPredicates.AMMUNITION` | `0` |
| serializer | `BartizanItemPredicates.WEARABLE` | `0` |
| refresher | weapon refresher | `10` |
| refresher | wearable refresher | `10` |
| refresher | ammunition refresher | `0` |

The two priority-`10` refreshers outrank a consumer's own generic item refresher (conventionally priority `0`);
the ammunition refresher sits behind it. These three numbers are load-bearing — a consumer that also registers a
priority-`0` refresher for the same predicate space must not expect it to run before weapon/wearable refreshing.

This resolution is **soft-dependency ordered**: if a server removes Bartizan, any `weapon:` / `ammo:` / `wearable:`
item definition elsewhere on that server silently resolves to nothing (not an error — a supported configuration).
Bartizan logs one INFO line naming the vocabulary namespace it published at boot so an administrator can confirm
it is present.

## What Bartizan does *not* use

Bartizan does not use `keystone-module` — it is a plain Spigot plugin, not a Keystone module host. There is no
`ModuleLoader`, no `module.yml`, no `Host_Api`, no `Plugins:` descriptor key. `plugin.yml` declares
`depend: [Keystone]` and `softdepend: [ViaVersion, PlaceholderAPI, NBTAPI]`.
