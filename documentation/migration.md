# Migrating off Gangland Warfare's weapon module

Weapons, ammunition, wearables and projectile combat left Gangland Warfare's `gangland-features/gangland-weapon`
module (last shipped in Gangland 0.8.4) and became **Bartizan**, a standalone Spigot plugin. Gangland 0.9.0 drops
the weapon feature entirely — there is no bridge module. This page is for a server owner moving an existing
install from "Gangland with the weapon module" to "Gangland + Bartizan".

## 1. What moved

Weapons, ammunition, wearables, their 22+2 YAML definition files, the weapon database table, the recoil NMS
adapters (`gangland-compatibility/version-*`), and the `/glw weapon` / `/glw ammo` / `/glw item wearable` /
`/glw debug weapon` commands all leave Gangland and become Bartizan.

## 2. Install

1. Install **Keystone 1.9.0** (`Keystone-1.9.0.jar`) if not already present — Bartizan `depend`s on it.
2. Install **NBT-API** (`NBTAPI.jar`) if not already present — Bartizan `depend`s on it too (M3, gate-GG-review
   final review): Keystone's `NbtBridge.detect()` falls back to a no-op accessor when NBT-API is absent, which
   makes every Bartizan item inert, so this is a hard requirement, not optional.
3. Drop `Bartizan-0.1.0.jar` into `/plugins`, beside `Keystone-1.9.0.jar`, `NBTAPI.jar` and
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

Every other wearable field (`Material`, `Custom_Model_Data`, `Name`, `Base_Damage_Reduction`, `Leather_Color`,
`Lore`, `Traits:`) is unchanged.

## 4. Database — the weapon table

### SQLite — automatic, one-shot

If Bartizan is configured for SQLite (the default), it reads `plugins/Gangland_Warfare/database/gangland.db`'s
`weapon` table (columns `uuid`, `type` — unchanged) on its first boot, upserts every row into its own
`plugins/Bartizan/database/bartizan.db`, and writes a marker file:

```
plugins/Bartizan/.weapon-import-done
```

The import runs at most once: once the marker exists, Bartizan never re-reads the Gangland database again, even
if rows there change. **Delete the marker file to force a re-import** (e.g. after fixing a bad copy). If the
source Gangland database is absent or unreadable, Bartizan logs the failure and still writes the marker — a
failed import costs re-minted weapon UUIDs on next use, not a boot failure.

### MySQL — manual, one statement

Bartizan does **not** auto-import from MySQL. With both schemas reachable from the same MySQL server, run once:

```sql
INSERT INTO bartizan.weapon (uuid, type)
SELECT uuid, type FROM gangland.weapon
ON DUPLICATE KEY UPDATE type = VALUES(type);
```

Then drop `gangland.weapon` after confirming the row counts match.

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

Bartizan's `settings.yml` carries only the settings the weapon module actually read, plus the infrastructure
sections every standalone Keystone plugin needs:

| Key | Meaning |
|---|---|
| `Auto_Save.Time` (minutes, default `10`; `<= 0` disables autosave) | Drives `WeaponAutoSaveTask`, which calls `RepositoryRegistry.saveAll()` on every registered repository (weapon UUIDs included) on this cadence, plus once more on plugin disable — **not** the weapon table cleanup schedule (see below). |
| `Clean_Up.Time` (**days**, default `30`) | How often `WeaponDataCleanupTask` prunes stale weapon rows. Distinct unit and distinct key from `Auto_Save.Time` — do not confuse the two when porting a customised value from Gangland's old settings. |
| `Block_Regeneration.*` | Unchanged from the old `Block_Regeneration` section. |
| `Database.*` | Its own `Type` / `MySQL.*` / `SQLite.*` block, independent of Gangland's own database configuration — Bartizan is a separate plugin with a separate database connection, even when both point at the same MySQL server. |

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

## 9. Known limitations (0.1.0)

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
  `NoSuchFieldError` / `NoSuchMethodError` at the call site rather than failing to load — Bartizan 0.1.0 is only
  verified against 1.21.x. Lowering the compile floor to 1.16.5 (XSeries lookups, reflection, or feature-gating)
  is a later wave, not part of 0.1.0.
- **bStats plugin id ships as `0`.** Bartizan has not yet been registered on bstats.org; `0` is bStats' no-op id
  (metrics silently do nothing rather than throwing). The `number_of_weapons` chart is wired and will start
  reporting the moment a real id is set.
- **`WeaponDataCleanupTask`'s period is computed once, at bean construction.** It reads `Clean_Up.Time` into a
  fixed `Timer` period when `WiringConfig` builds it; `/bartizan reload` re-reads every other setting but does not
  recreate this timer, so a changed `Clean_Up.Time` only takes effect on the next full restart. Low urgency —
  30-day cadence — and not fixed as part of the R-B-FINAL review pass (m5).
