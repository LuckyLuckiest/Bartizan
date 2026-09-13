# Migrating off Gangland Warfare's weapon module

Weapons, ammunition, wearables and projectile combat left Gangland Warfare's `gangland-features/gangland-weapon`
module (last shipped in Gangland 0.8.4) and became **Bartizan**, a standalone Spigot plugin. Gangland 0.9.0 drops
the weapon feature entirely — there is no bridge module. This page is for a server owner moving an existing
install from "Gangland with the weapon module" to "Gangland + Bartizan".

## 1. What moved

Weapons, ammunition, wearables, their 22+2 YAML definition files, the recoil NMS
adapters (`gangland-compatibility/version-*`), and the `/glw weapon` / `/glw ammo` / `/glw item wearable` /
`/glw debug weapon` commands all leave Gangland and become Bartizan.

## 2. Install

1. Install **Keystone 1.9.0** (`Keystone-1.9.0.jar`) if not already present — Bartizan `depend`s on it.
2. Install **NBT-API** (`NBTAPI.jar`) if not already present — Bartizan `depend`s on it too (M3, gate-GG-review
   final review): Keystone's `NbtBridge.detect()` falls back to a no-op accessor when NBT-API is absent, which
   makes every Bartizan item inert, so this is a hard requirement, not optional.
3. Drop `Bartizan-0.2.0.jar` into `/plugins`, beside `Keystone-1.9.0.jar`, `NBTAPI.jar` and
   `Gangland_Warfare-0.9.0.jar`.
4. Restart the server (Bartizan, like every Keystone-based plugin here, wires its bean graph at `onEnable` — no
   hot-reload of the jar itself).

Bartizan soft-depends on `ViaVersion` and `PlaceholderAPI`; neither is required.

## 3. Data folder — copying customised YAML

On first boot Bartizan writes its own defaults to:

```
plugins/Bartizan/settings.yml
plugins/Bartizan/message/message_en.yml
plugins/Bartizan/weapon/<22 files>.yml
plugins/Bartizan/items/ammunition.yml
plugins/Bartizan/items/wearables.yml
```

A server that customised the old files under `plugins/Gangland_Warfare/` copies them to `plugins/Bartizan/` at the
**same relative paths** (`weapon/rifle.yml` stays `weapon/rifle.yml`, etc.) before first boot, then applies the one
required edit below. Do this before starting the server with Bartizan installed — Keystone's file recovery only
regenerates a *missing* file from the jar's bundled defaults, it does not merge an existing one.

### `items/wearables.yml` edit — `Jetpack:` → `Extra_Tags:`

The wearable's jetpack-specific block was renamed and its keys lower-cased/flattened. Rename the block and its
child keys; values carry over unchanged. Before (Gangland 0.8.4):

```yaml
jetpack:
   ...
   Jetpack:
      Fuel_Key: "gasoline"
      Fuel_Consumption_Rate: 1
      Ascend_Power: 0.2
      Glide_Descent_Rate: -0.05
      Max_Speed_Y: 0.45
      Sound:
         Thrust:
            Default_Sound:
               Sound: ENTITY_BLAZE_SHOOT
               Volume: 0.6
               Pitch: 1.8
         Glide:
            Default_Sound:
               Sound: ITEM_ELYTRA_FLYING
               Volume: 0.4
               Pitch: 1.0
```

After (Bartizan 0.1.0):

```yaml
jetpack:
   ...
   Extra_Tags:
      fuel: "gasoline"
      fuel_current: 3600
      fuel_max: 3600
      jetpack_fuel_consumption_rate: 1
      jetpack_ascend_power: 0.2
      jetpack_max_speed_y: 0.45
      Sounds:
         Thrust:
            Default_Sound:
               Sound: ENTITY_BLAZE_SHOOT
               Volume: 0.6
               Pitch: 1.8
         Glide:
            Default_Sound:
               Sound: ITEM_ELYTRA_FLYING
               Volume: 0.4
               Pitch: 1.0
```

`fuel_current` / `fuel_max` are new explicit keys (the old code derived the current fuel from `Max_Fuel` at parse
time — Bartizan's default ships both at `3600`, matching the old default). Any other wearable that carries no
`Jetpack:`/`Extra_Tags:` block needs no edit at all.

Since 0.2.0 a wearable that still has the old `Jetpack:` block (and no `Extra_Tags:`) is translated at load time
with the 0.8.4 defaults and a `WARN` naming the wearable, so an un-migrated file keeps working; migrate it anyway
so the warning goes away and the values are visible where the code reads them.

Every other wearable field (`Material`, `Custom_Model_Data`, `Name`, `Base_Damage_Reduction`, `Leather_Color`,
`Lore`, `Traits:`) is unchanged.

## 4. Database — nothing to migrate

Gangland's `weapon` table only mirrored two values every weapon item already carries in its own NBT: the `uuid`
and `weapon` tags. Bartizan keeps no database at all — an item is recognised and its runtime instance rebuilt
from those tags on first use, so Gangland-era items keep working untouched. Leave `gangland.weapon` alone or
drop it; Bartizan never reads it.

Bartizan 0.1.0 briefly shipped its own copy of that table (`plugins/Bartizan/database/bartizan.db`) plus a
`.weapon-import-done` marker. 0.2.0 ignores both; delete them.

## 5. Permissions — the wearable node rename

Wearable permission nodes changed prefix because Bartizan is a standalone plugin, not a Gangland feature:

```
gangland.wearables.<key>   →   bartizan.wearables.<key>
```

(e.g. `gangland.wearables.police_vest` → `bartizan.wearables.police_vest`). Update any permission plugin
configuration (group files, LuckPerms templates, etc.) that references the old node — Bartizan does not read the
old prefix at all, so an ungranted new node behaves as "no permission", not as a fallback to the old grant.

Every other Bartizan permission is new and plugin-local (`bartizan.command.main`).

## 6. Settings that moved

Bartizan's `settings.yml` carries only the settings the weapon module actually read — `Language`, `Money_Symbol`
and `Block_Regeneration.*` (unchanged from the old section) — plus a `Debug.Enable` flag. There is no `Auto_Save`,
`Clean_Up` or `Database` section: Bartizan keeps no database (see §4). A leftover section from a 0.1.0
`settings.yml` is reported as an unknown key at boot and can be deleted.

## 7. Commands that moved

| Old (`/glw`, Gangland) | New (`/bartizan`, Bartizan) |
|---|---|
| `/glw weapon …` | `/bartizan weapon …` |
| `/glw ammo …` | `/bartizan ammo …` |
| `/glw item wearable …` | `/bartizan wearable …` |
| `/glw debug weapon …` | `/bartizan debug …` |

`/bartizan` has aliases `/btz` and `/weapon`.

## 8. Signs

`[WEAPON-BUY]`, `[WEAPON-SELL]`, `[AMMO-BUY]`, `[AMMO-SELL]`, `[WEARABLE-BUY]`, `[WEARABLE-SELL]` sign types no
longer exist as such — this half of the migration happens on the **Gangland side**: Gangland 0.9.0's
`LegacySignRewriter` recognises signs of these old types already placed in the world and rewrites them to the
generic `[ITEM-BUY]` / `[ITEM-SELL]` sign types with a `weapon:` / `ammo:` / `wearable:` item definition string, so
existing placed signs keep working without the owner re-placing them. This rewrite is not part of Bartizan itself
— see Gangland 0.9.0's own migration notes for its exact trigger conditions.

## 9. Known limitations (0.2.0)

- **Argument-framework errors are not localised.** Bad-argument / no-permission / not-implemented messages from
  Keystone's command argument tree use the framework's built-in English defaults — Bartizan's own
  `BartizanMessages` table does not (yet) supply overrides for them.
- **PlaceholderAPI is declared, not consumed.** `plugin.yml` soft-depends on it and `Placeholder` parameters exist
  in the relevant constructors, but no adapter wires a live `%bartizan_*%` placeholder resolver yet — every
  `Placeholder` argument is `null`.
- **`WeaponEntityDamageEvent.kind()` is effectively single-valued today.** Only `ThrowableAction` fires this
  event, and it always passes `DamageKind.EXPLOSION` — the other four enum values (`DIRECT`, `FIRE`, `BIOLOGICAL`,
  `MELEE`) exist for future firing actions, not because anything currently produces them.
- **Compiled against `spigot-api 1.21.11-R0.1-SNAPSHOT`, not Keystone's 1.16.5 API floor.** The ported weapon code
  uses several 1.21-only Bukkit members (`Enchantment.PROTECTION`, `PotionEffect.INFINITE_DURATION`,
  `Player.isClimbing()`, the 3-arg `Player.sendBlockDamage`, `Particle.BLOCK`). On a pre-1.21 server these throw
  `NoSuchFieldError` / `NoSuchMethodError` at the call site rather than failing to load — Bartizan is only
  verified against 1.21.x. Lowering the compile floor to 1.16.5 (XSeries lookups, reflection, or feature-gating)
  is a later wave.
- **bStats plugin id ships as `0`.** Bartizan has not yet been registered on bstats.org; `0` is bStats' no-op id
  (metrics silently do nothing rather than throwing). The `number_of_weapons` chart is wired and will start
  reporting the moment a real id is set.

## 10. 0.3.0 (gates HA–HC) — the effects engine, and beam weapons

- Shot, impact, empty-magazine, scope and reload-start/end sounds are no longer played by `bartizan-api` directly.
  The loader (`EffectsSectionParser.lowerLegacySounds`) lowers each configured `Shoot.Sound.*`/`Reload.Sound.*`
  slot into the matching `Effects:` hook, and `bartizan-plugin`'s `EffectRunner`/listeners play it from there — a
  weapon's own `Effects:` section, when present, replaces the lowered default entirely for that hook. `Reload`
  (`InstantReload`/`NumberedReload`) no longer plays the reload start/end sounds itself.
- `WeaponReloadCompleteEvent#isInterrupted()` is new: `true` when a reload was ended by a weapon swap rather than
  finishing normally, so a listener can tell a cancelled reload apart from a completed one.
- `WeaponRaytracer#fireInstant(RaytraceRequest)` now returns `boolean` — whether a living entity took the hit. The
  raytracer no longer fires `ON_MISS` itself; the firing action decides off this return value instead.
- **`ray_gun.yml` is re-categorised from `Category: biological` to `Category: beam` (gate `HC`)** — it becomes a
  `BeamWeapon` template instead of a `BiologicalWeapon` one, trading its potion payload for a piercing damage beam
  with the same charge-then-release feel. Existing ray gun items already in players' inventories keep working with
  no item edit needed: a weapon item's identity is its `weapon` NBT tag (the registry file name, `"ray_gun"` —
  see `WeaponItemSerializer#extract` and `Weapon.WeaponTag.WEAPON`), not its category. `WeaponService#getWeapon`
  resolves that tag against the *currently loaded* template (`WeaponAddon#getWeapon(type)`) and mints a fresh
  `copyWithUUID` of whatever type backs it today — now `BeamWeapon` — while ammo-left, durability and
  selective-fire all sync from the item's NBT tags generically, regardless of subclass. So the day this update
  ships, a player's existing ray gun item is simply reinterpreted as a beam weapon on next use; nothing needs to be
  reissued or converted.
- New `arc_lance.yml` (`Category: beam`) and a new `energy_cell` ammo type in `items/ammunition.yml`. `WeaponType`
  gains `BEAM` (`Category: beam` / `laser`).
