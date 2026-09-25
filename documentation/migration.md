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

### `items/wearables.yml` edit — `Jetpack:` → `Extra_Tags:` (superseded by §12 — historical, 0.1.0–0.3.0 installs only)

**Superseded as of 0.4.0**: the jetpack itself left `wearables.yml` entirely — see §12. This section stays for an
install still mid-upgrade from a pre-0.2.0 `Jetpack:` block on a Bartizan version before 0.4.0.

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

`/bartizan` has alias `/btz` (the old `/weapon` alias was removed in 0.5.1 — use `/bartizan weapon …` or
`/btz weapon …`).

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
- **Compiled against `spigot-api 1.16.5-R0.1-SNAPSHOT` — Keystone's API floor (`bukkit.version` in the root pom).**
  Newer Bukkit members are used only when the running server has them: `ItemMeta#setItemModel` (1.21.2+), the
  3-arg `Player#sendBlockDamage`, `Material#getDefaultAttributeModifiers` and `Firework#setMaxLife` (all 1.19.4+)
  go through cached reflective lookups that are null on an older server; `Particle.BLOCK` resolves through
  `XParticle`; the protection enchantments are looked up by namespaced key (`Enchantment.getByKey`, present on
  both ends of the range); attribute modifiers use the five-argument `UUID` constructor, the one shape shared by
  1.16.5 and 1.21; `isClimbing()` became the `CLIMBABLE` block tag and `INFINITE_DURATION` became
  `Integer.MAX_VALUE`. Known ceilings below 1.19.4 are marked with `ponytail:` comments at the call sites
  (firework visuals self-detonate after the vanilla fuse, a wearable with `Attributes:` loses its vanilla armour
  points, crack overlays are keyed per viewer). Anything added later that needs a newer API follows the same
  pattern: reflective or XSeries lookup, never a direct reference.
- **bStats plugin id ships as `0`.** Bartizan has not yet been registered on bstats.org; `0` is bStats' no-op id
  (metrics silently do nothing rather than throwing). The `number_of_weapons` chart is wired and will start
  reporting the moment a real id is set.

## 10. 0.3.0 (gates HA–HC) — the effects engine, beam weapons, biological status

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
- A biological weapon's hit is now a tracked **status** (`BiologicalData#getStatus()`, never `null`), owned by the
  new `status.StatusEffectService`: a boss bar and ambient particles the victim's allies can see, a shooter hit
  marker and action bar, a contagion roll that can spread the status to nearby players, and a consumed-item or
  worn-wearable (`sealed` trait) cure. Old files without `Shoot.Status:`/`Shoot.Feedback:` keep loading — they get
  a minimal status named after the weapon with a boss bar and hit marker only, no config edit required.
- `Shoot.Cumulative_Levels: true` (default `false`) makes a release at charge level N apply every
  `Effects_Per_Level` entry from `1..N` merged (strongest amplifier, longest duration per potion type) instead of
  only level N's own entry, so charging to the top level no longer silently drops the earlier levels' effects.
- `WeaponDeathListener` now credits a killer-less death (the common shape of a poison/wither finish) to the last
  shooter who applied the victim's active status, provided the last application landed within
  `Status.Kill_Credit_Window` ticks and that shooter is still online — same `WeaponKillEntityEvent` /
  `Death_Messages` / `On_Kill` path a direct kill already used.
- New api types: `weapon.dto.StatusData` (+ nested `Stacking`, `ContagionData`, `CureData`, `BossBarData`),
  `event.WeaponStatusApplyEvent` (cancellable), `event.WeaponStatusExpireEvent`. New `wearable.Wearable` trait:
  `sealed` — reduces the incoming level of a biological status rather than a damage/duration percentage.
- `Shoot.Charge_Feedback.Tracer_Color` now also defaults `Modifiers.Tracer` when the weapon declares no explicit
  tracer of its own, so a released biological shot draws a coloured line without any other config change.
- Ammo ids keep their Gangland spelling, commas included (`7,62`, `5,56`, or your own `1,25`): nothing to rename,
  and ammo items already in circulation keep resolving. `/bartizan ammo info` and `/bartizan weapon info` now print
  the id in quotes so Keystone's `JsonFormatter` no longer breaks the line at the comma.

## 11. Moving off WeaponMechanics (gate `HM`)

Not a Gangland-specific migration — for a server owner moving off the WeaponMechanics plugin entirely, or running
both side by side while switching over weapon by weapon.

`/bartizan import weaponmechanics [--dry-run] [--force] [path]` reads WM's `weapons/**/*.yml`,
`projectiles/*.yml` and `ammos/*.yml` (default `path`: `plugins/WeaponMechanics`) and writes a Bartizan
`plugins/Bartizan/weapon/<title>.yml` per weapon, translating what has a Bartizan equivalent and reporting
everything that doesn't (approximated or dropped, never silent) to
`plugins/Bartizan/import/weaponmechanics-<date>.txt`. `--dry-run` only writes that report; a weapon file is never
overwritten without `--force`. See `org.luckyraven.bartizan.importer.wm` in the package map below for the
translator classes, and `documentation/weapons-roadmap.md` §6 for the full mapping table.

Coverage, by design (see the roadmap's §6 "Deliberately not doing" list and the importer's own report lines for
specifics): gun/throwable/melee weapons import; WM's spring-back recoil model (`Speed`/`Damping`/`Smoothing`),
spread images, multi-layer airstrikes, per-block explosion regeneration tuning, `Firearm_Action`'s open/close
animation and `WeaponMechanicsCosmetics`-only sections (`Trail`, `Bullet_Zip`, `Third_Person_Pose`) have no
Bartizan equivalent and are reported, not translated. A WM weapon shaped like neither a gun, a throwable nor a
melee weapon (no `Shoot.Projectile_Speed`, no `Explosion`, no `Melee.Enable_Melee` — e.g. a consumable like WM's
own `Stim`) is skipped entirely.

Players' existing WM items keep working without being reissued: `WmItemConverterListener` (gated by
`settings.yml`'s `Import.Convert_WeaponMechanics_Items`, flipped on for the running session by a successful
import) rebuilds a held/clicked/carried item that still carries WM's own `weaponmechanics:weapon-title` NBT tag
into its imported Bartizan equivalent, carrying `ammo-left` over, the moment it's next held, clicked, or the
player logs in.

## 12. 0.4.0 — jetpack leaves Bartizan entirely (Gangland WS7)

The `jetpack:` entry left `items/wearables.yml` (deleted, not renamed) along with the `FUEL_EFFICIENT`-jetpack
comment and the legacy `Jetpack:`→`Extra_Tags:` migration converter (`legacyJetpackToExtraTags`, superseding §3.4
above). The jetpack is now a Gangland-owned item, defined in `gangland-gadget`'s own `items/jetpacks.yml`
(Gangland 0.9.2+) — Bartizan no longer knows about it as a catalogued wearable.

**Armour/trait loss, and the opt-in fix**: the jetpack's `Base_Damage_Reduction: 0.05` and
`Traits: {REINFORCED: 1, LIGHTWEIGHT: 2}` are gone by default — a rehomed jetpack is a plain fuel/thrust
chestplate with vanilla `IRON_CHESTPLATE` protection only. A server owner who wants those values back configures
an optional `Bartizan_Traits:` block in Gangland's `items/jetpacks.yml`; when Bartizan is installed, Gangland
registers that definition into Bartizan's wearable catalog through the new `WearableCatalog.register(String,
Wearable)` api method (added this version) so Bartizan's existing damage-reduction path applies to the jetpack
exactly as it did before — Bartizan gains no jetpack-specific code to do this, it is a generic external
registration hook any soft-dependent plugin can use.

**Data**: any shop/loot-chest entry that still references `wearable:jetpack` stops resolving — re-add it as
`jetpack:<id>` through Gangland's own item vocabulary. Bartizan keeps no database, so there is nothing to migrate
on this side.

**Fuel survives the move.** A jetpack chestplate given out before 0.4.0 still carries its old, Bartizan-stamped
`fuel_max` value (typically `3600`, the stock 0.3.0 default) — Gangland's re-stamp-on-equip migration
(`JetpackService.migrateLegacyJetpack`, Gangland 0.9.2 G4) only ever touches the item-identity tag, never
`fuel_max`/`fuel_current`, so a player's fuel and their maximum both carry over unchanged even if the item's
catalogue definition (now `items/jetpacks.yml`'s `Max_Fuel:`) has since been set to a different value. See
Gangland's own [`documentation/migration-0.9.2.md`](../../gangland-0.9.2/documentation/migration-0.9.2.md) for the
full re-stamp mechanics and every other Gangland-side change this version brings.

## 13. 0.5.0 (gates HO, HP) — staged reload, spyglass scope, crossbow aim pose

No file has to change. Two behaviours do change without a config edit, so read the first two bullets before
deploying.

- **Every instant reload now runs in three timed stages** (`open` 25 %, `insert` 60 %, `close` 15 % of
  `Reload.Cooldown`, rounded to whole timer periods). The magazine item is still consumed exactly once, at the
  `insert` commit, but the mid-reload sound now plays at a quarter of the cooldown instead of half, and short
  cooldowns (0–3 s and 6 s) have a zero-length `close`. Numbered (shell-by-shell) reloads keep their timing.
- **An interrupted reload resumes by default.** A swap, drop, death or lost magazine records the last committed
  stage; a reload pressed within `Reload.Stages.Resume_Window` ticks (default 60) skips the committed stages and
  restarts the interrupted one from its beginning. Set `Resume_Window: 0` on a weapon to keep the old
  restart-from-zero behaviour. Nothing is refunded or duplicated; an interrupt at `close` starts a fresh reload.
- New keys, all optional and documented in `rifle.yml`: `Reload.Stages.Resume_Window`,
  `Reload.Stages.Open/Insert/Close.Share` (normalised, instant reloads only). New hook `On_Reload_Stage`
  (`settings.yml` hook list), new HUD/PlaceholderAPI placeholders `%reload_stage%` (1-based) and
  `%reload_stage_max%`, new api event `WeaponReloadStageEvent`.
- **`Scope.Type: spyglass`** (1.17+): with `Information.Material: SPYGLASS` the scope becomes the vanilla spyglass
  use (zoom, raised arm for everyone watching, use slowdown), `Scope.Level` may be 0, and fire while scoped is
  the `F` key (the client swallows attack clicks during an item use). Set `Shoot.Trigger: left_click` on such
  weapons (`right_click` loads with a warning); `Zoom_Stacking` is ignored with a warning. On a server below 1.17
  the weapon loads as `Type: slowness` after one warning. Existing `Scope:` sections (`Type: slowness`, the
  default) behave exactly as before. Sample: the new bundled `weapon/scout.yml`.
- **A weapon whose `Information.Material` is `CROSSBOW` now carries one arrow in its item meta**, which makes
  the client draw the charged-crossbow hold pose while it is merely held. Existing crossbow weapon items gain the
  arrow on their next refresh; Bartizan denies the vanilla use on every right-click, so the arrow never fires.
  Prefer `Shoot.Trigger: left_click` on such weapons so the client does not predict a crossbow shot.
- Not yet play-tested on a real server: the spyglass skin rewrite keeping the vanilla use on 1.17 and 1.21, and
  the new instant-reload stage timing. Both are flagged in the roadmap's §9/§10 *As built* notes.

## 14. 0.5.0 → 0.5.1 — the docket fix wave

No `.yml` file has to change and no weapon/ammo/wearable definition needs an edit — every default-preserving fix
in this wave only closes a failure mode reachable by a hand-authored or future config, or by play that hit a bug.
Full detail, the per-system breakdown and every behaviour change is in
[`documentation/docket-fix-wave-0.5.1.md`](docket-fix-wave-0.5.1.md); this section is the condensed admin/consumer
checklist.

### Behaviour to re-check before/after upgrading

- **Off-hand weapon uses are inert** until gate HN's `Dual_Wield` lands — a right-click with the off hand no
  longer fires, throws, charges or sprays anything on any weapon type. A server that relied on off-hand guns,
  throwables or charge weapons (the off-hand path used to duplicate items and hand out unlimited grenades) loses
  that until HN.
- **A protection plugin now sees weapon-caused block breaks.** A vanilla `BlockBreakEvent` (as a
  `WeaponBlockBreakEvent`, see below) fires for every player-attributed weapon block break — raytrace hits and
  rocket/grenade explosion block damage alike — so WorldGuard/GriefPrevention/etc. can veto it exactly like a
  hand-mined block. A claim that previously "protected" against everything except weapons now also blocks weapon
  fire from breaking blocks inside it, if the region plugin's own rules say so.
- **`Armor_Piercing` values changed twice this wave** (first under-delivering, then over-correcting on toughness
  armor, now toughness-aware) — re-check any `Armor_Piercing` tuning against diamond/netherite-armored targets.
- **Spread bloom now actually accumulates.** `Shoot.Spread.Time`'s reset window is now honoured in ticks as
  documented (it was misread as milliseconds, so spread reset to `Starting_Spread` on nearly every shot), so any
  weapon with a non-zero `Spread.Change.Base` blooms further under sustained fire than it did in 0.5.0 — the
  shipped automatics (`minigun`, `mp5`, `rifle`, `golden_ak47`, `steyr_aug`, …) included. Re-tune `Time`/
  `Change.Base`/`Change.Bounds` if the old always-reset feel was relied on (`BZ-WM-02`).
- **`CombatEligibility` is now enforced for the victim**, not just the shooter, on every weapon damage path (guns,
  beams, incendiary, melee, biological, explosions). A consumer already registering `CombatEligibility` for
  downed-player gating gets it enforced for free with no code change; nothing to do unless you relied on the
  previous gap.
- **`PlaceholderAPI` placeholders are now read-only** — `%bartizan_*%` never registers a weapon or mutates its
  ammo/durability/fire mode as a side effect of being read from PAPI's own thread.
- **A give command amount above the target material's max stack size now mints one uuid per physical item**,
  instead of one uuid shared across a whole stack — a shop/kit integration that gives large stacks through
  Bartizan's give path should expect N separate items back, not one N-sized stack.
- **`/weapon` no longer dispatches.** The `/bartizan` `weapon` alias was removed (it shadowed `WeaponCommand`'s
  own `weapon` subcommand); use `/bartizan weapon …` or `/btz weapon …`.
- **Wearable permission enforcement is now complete.** Hotbar-swap and plain right-click equip now go through
  `bartizan.wearables.<key>` the same as drag/shift-click always did — a server relying on the gap to let
  ungranted players wear a restricted wearable via those paths no longer can.
- **`Bartizan.onDisable`/`/reload` no longer resets Keystone's shared `PacketBridge`.** Disabling or reloading
  Bartizan no longer silently downgrades recoil/packet handling to a no-op for other Keystone-powered plugins
  still running on the same server.

### `bartizan-api` deltas

- **New: `weapon.modifiers.WeaponBlockBreakEvent extends org.bukkit.event.block.BlockBreakEvent`.** Fired by
  `BlockDamageManager.applyDamage(Block, BlockBreakModifier, Player)` (a new 3-arg overload; the existing 2-arg
  overload is unchanged and fires no event) for a weapon-caused block break with an attributable player. A
  consumer that needs to tell a synthetic weapon break apart from a real player punch checks
  `event instanceof WeaponBlockBreakEvent`, the same way Bartizan's own `WeaponInteract.onBlockBreak` does.
- **`event.WeaponChangeSelectiveFireEvent.getHandlerList()` is now `public`** (was `private`, unlike every other
  sibling event class) — Bukkit's reflection-based listener registration could never actually find this event
  before, so a consumer plugin can now genuinely listen for it for the first time.
- **`weapon.reload.ReloadType.createInstance(Weapon, Ammunition)` gained a required third `int amount` parameter**,
  and `ReloadType.getAmount()`/`setAmount()` were removed. The per-weapon reload amount used to live as mutable
  state on the shared `ReloadType` enum constant, so every weapon of the same `ReloadType` silently collided on
  whichever was parsed last; it now lives on `weapon.dto.ReloadData#getAmount()` (new field, default `1`). Only
  `Weapon`'s own constructor called `createInstance` in this codebase — a consumer that called it or the removed
  accessors directly needs to update the call site.
- **`wearable.Wearable.NBT_TRAIT_PREFIX` (`"wt_"`) and `NBT_BASE_REDUCE` (`"wr_base"`) constants were removed**,
  and `buildItem()` no longer stamps those tags — they were write-only, nothing in Bartizan or Gangland ever read
  them back (every consumer already resolves traits/base reduction live from the registry by
  `Wearable.NBT_KEY` alone). Any external tooling reading those raw NBT keys off a wearable item needs to stop.
- **`weapon.recoil.RecoilManager`'s public `clone()` override was removed.** It always threw
  (`RecoilManager` never implemented `Cloneable`) and a repo-wide grep found no caller; a future caller wanting a
  real shallow copy needs to add `Cloneable` back itself.
- **`Weapon#getModifiersData()` now always returns a non-null `ModifiersData`** for a weapon YAML with no
  `Modifiers:` section, instead of sometimes returning `null`. A caller that null-checked before reading it can
  drop the check.

See `documentation/docket-fix-wave-0.5.1.md` §3 for the full numbered behaviour-change list (config parser
warnings, importer changes, kill-credit fixes, stats/HUD fixes, and more) and §4 for what was deliberately left
deferred or out of scope.
