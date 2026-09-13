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

## `BartizanApi`'s five accessors

```java
public interface BartizanApi {
    WeaponCatalog weapons();
    WearableCatalog wearables();
    AmmunitionCatalog ammunition();
    NpcWeaponFactory npcWeapons();
    WeaponItemApi items();
}
```

> `NpcWeaponFactory.create` throws `IllegalArgumentException` for a weapon name that is not configured; call `items().isValidWeaponName(name)` first (Gangland's `BartizanNpcWeapons` does, returning `NpcRangedAttack.NONE`).


| Accessor | Returns | Members |
|---|---|---|
| `weapons()` | `weapon.WeaponCatalog` | `getWeaponTemplate(String)`, `getWeaponTemplates()`, `createTransientWeapon(String)`, `isWeapon(ItemStack)` — read-only lookups: each may return `null`/empty for an unknown name, and none of them mints or registers a weapon instance. `validateAndGetWeapon(Player, ItemStack)` is **not** read-only — it resolves the held item's uuid and, when that uuid is not yet in the runtime registry, mints and registers a live `Weapon` instance for it (`WeaponService.getWeapon` → `weapons.put`); use `getWeaponTemplate(...)` instead for a read-only lookup. |
| `wearables()` | `wearable.WearableCatalog` | `getWearable(String)`, `getWearables()`, `resolveWearable(@Nullable ItemStack)`, `applyWearableReduction(double, LivingEntity, boolean)`, `reduceCritBonus(double, LivingEntity)`, `reduceFireTicks(int, LivingEntity)` |
| `ammunition()` | `ammo.AmmunitionCatalog` | `getAmmunitionKeys()`, `getAmmunition(String)` |
| `npcWeapons()` | `npc.NpcWeaponFactory` | `create(LivingEntity shooter, String weaponName, double fireRateMultiplier, double aimErrorDegrees)` → `npc.NpcWeaponController extends org.luckyraven.keystone.npc.spi.NpcRangedAttack` — the sole implementation of that Keystone SPI. A consumer with no Bartizan installed uses `NpcRangedAttack.NONE` instead of calling this accessor. |
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

`weapon.Weapon` and its five subclasses (`GunWeapon`, `MeleeWeapon`, `BiologicalWeapon`, `IncendiaryWeapon`,
`ThrowableWeapon`), `weapon.{WeaponType, ThrowableType, SelectiveFire, WeaponTag, ProjectileType, ProjectileState}`,
the fifteen `weapon.dto.*` records, `ammo.Ammunition`, `wearable.Wearable` (string trait keys via
`traits()`/`traitLevel(String)`; jetpack-style extra data via `extraTags()` — NBT keys `fuel`/`fuel_current`/
`fuel_max` are unchanged from the old `Jetpack:` block, so a consumer's fuel-reading code needs no edit),
`BartizanItemPredicates.WEARABLE`.

### Effects (`weapon.dto.{EffectHook, EffectSpec, EffectsData}`)

Gate `HA`'s feedback engine model, added at the api layer so a consumer can read what a weapon will do without
depending on `bartizan-plugin`. `EffectHook` is the v1 set of ~21 feedback hooks (`ON_SHOOT`, `ON_HIT`, `ON_KILL`,
`ON_CRITICAL`, …); `key()`/`fromKey(String)` round-trip the `Capitalized_Underscore` YAML spelling (`On_Shoot`).
`EffectSpec` is an immutable `(type, args)` record for one configured effect entry, with typed arg accessors (`arg`,
`intArg`, `doubleArg`, `boolArg`). `EffectsData` is the hook → effect-list table (`forHook`, `has`, `put`, `empty()`).
`Weapon#getEffects()` exposes a weapon's parsed `Effects:` section (never `null`, empty when none configured). The
runtime engine that reads these (`effect.EffectRunner` and its 15 hook effects) lives in `bartizan-plugin` and is
wired into every firing action, listener and NPC controller (weapons-roadmap.md gate `HA`).

### Events (`org.luckyraven.bartizan.api.event`)

`WeaponEvent`, `WeaponShootEvent`, `WeaponRaytraceImpactEvent` (cancelling suppresses damage only — penetration and
ricochet counters still advance), `WeaponEntityDamageEvent`, `WeaponKillEntityEvent`, `WeaponReloadEvent` /
`WeaponReloadStartEvent` / `WeaponReloadCompleteEvent`, `WeaponChangeSelectiveFireEvent`.

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
