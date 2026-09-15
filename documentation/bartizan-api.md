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

## `BartizanApi`'s five accessors, plus three gate-`HD` convenience methods

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
}
```

`getHeldWeapon`/`isScoping`/`isReloading` (added at gate `HD`, alongside the HUD feature) are a shortcut over
`weapons().validateAndGetWeapon(player, item)` for the common "what is this player currently holding/doing" query —
`getHeldWeapon` checks the main hand, then the off hand; `isScoping`/`isReloading` read that weapon's
`ScopeData`/`Reload` state. Like `validateAndGetWeapon`, `getHeldWeapon` is **not** read-only: it can mint and
register a live `Weapon` instance for an item that has no runtime registry entry yet.

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
needs no edit), `BartizanItemPredicates.WEARABLE`.

`Weapon#getHandlingData()` (nullable, gate `HE`) carries the interaction-handling rules — `Equip_Delay`,
`Deny_Use_In_Crafting`, `Cancel.*`, `Attributes`, `Trigger`, `Circumstance`, `Destroy_When_Empty`,
`Reset_Fall_Distance` — parsed from a weapon's `Information:`/`Shoot:` sections.

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
explosions). The hit-zone classification itself (`HEAD`/`BODY`/`ARMS`/`LEGS`/`FEET` + a `back` flag) and the
`Owner_Immunity`/`Ignore_Teams` skip rule live in `bartizan-plugin` (`raytrace.HitZone`, `weapon.DamageRules`) —
neither is referenced by an api type's public signature, so neither moved to `bartizan-api`.

### Events (`org.luckyraven.bartizan.api.event`)

`WeaponEvent`, `WeaponShootEvent`, `WeaponRaytraceImpactEvent` (cancelling suppresses damage only — penetration and
ricochet counters still advance), `WeaponEntityDamageEvent`, `WeaponKillEntityEvent`, `WeaponReloadEvent` /
`WeaponReloadStartEvent` / `WeaponReloadCompleteEvent`, `WeaponChangeSelectiveFireEvent`, `WeaponChargeLevelEvent`,
`WeaponBeamFireEvent`.
`WeaponStatusApplyEvent`, `WeaponStatusExpireEvent`.

`WeaponStatusApplyEvent` (weapon, `@Nullable` shooter, victim, `level`; cancellable) fires before
`StatusEffectService` (re)applies a biological status — cancelling suppresses the whole application: no stacking,
no feedback, no potion payload. `WeaponStatusExpireEvent` (weapon, victim, `reason`; not cancellable) fires
whenever a status stops being active — `reason` is `EXPIRED`, `CURED` (a `Status.Cure.Items`-listed item
consumed), `DEATH`, or `QUIT` (weapons-roadmap.md gate `HB`, §2.2).

`WeaponChargeLevelEvent` (weapon, player, `level`, `maxLevel`; not cancellable) fires on every charge-level
increment for a charge-then-release weapon — `bartizan-plugin`'s `ChargeController` (biological and beam, gate
`HC`) raises it alongside the `On_Charge_Level`/`On_Charge_Full` effect hooks.

`WeaponBeamFireEvent` (weapon, player, `level`, `origin`, `direction`; cancellable — cancelling suppresses the shot
but does not refund ammo already consumed) fires just before a beam's ray is cast, gate `HC`. It deviates from
weapons-roadmap.md §3.2's description of an event that "carries level and the ordered target list": the beam ray
streams hits one at a time through `RaytraceRequest`'s impact handler rather than pre-computing a target list
before firing, so there is no target list to carry — only `level`, `origin` and `direction` at fire time.

`WeaponReloadCompleteEvent#isInterrupted()` (new at gate `HA`) is `true` when the completion was raised by a
swap-cancelled reload (`Reload#endReloading(Player, boolean)`) rather than a normal reload finishing — Bartizan's
own `WeaponReloadListener` skips `ON_RELOAD_END` in that case, since `ON_RELOAD_CANCEL` is the feedback hook for it.

`WeaponEntityDamageEvent.weaponName()` / `.kind()` replace the old `ThrowableAction` static maps
(`pendingKillerWeapon`, `pendingVehicleExplosionDamage`) — the firing action stamps both at construction time, so a
listener never looks up a short-lived side table keyed by entity UUID. `kind()` returns a `DamageKind` enum
(`DIRECT`, `EXPLOSION`, `FIRE`, `BIOLOGICAL`, `MELEE`), but **as of 0.1.0 only `ThrowableAction` fires this event,
and always with `DamageKind.EXPLOSION`** (both its area-damage site and its direct-hit site) — the other four
values exist for a future firing action to use, not because anything currently produces them. Do not branch on
`kind()` expecting the other four values to occur yet.

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
