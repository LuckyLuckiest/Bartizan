# Docket fix wave 0.5.1 — 108 rows from the LuckyRaven cross-project bug docket

Driven by a 2026-09-24 bug hunt over 0.5.0 (`9fa25c3`): 185 agents revalidated 66 existing docket rows (41 still
present, 5 partially fixed, 13 already fixed, 5 obsolete since the weapon table was dropped in 0.2.0, 2 not-a-bug)
and, after dedup and adversarial verification, filed 62 new rows (14 P0, 28 P1, 18 P2, 2 P3) across three docket
systems the hunt scanned for the first time — `EF` (effects/status), `IM` (WeaponMechanics importer), `HU`
(HUD/stats). 13 fixer agents then worked in parallel, one per system-cluster in its own git worktree, each bug
following the same procedure: confirm it still reproduces, write a test that fails on the old code, make the
smallest fix, and commit as `Bartizan 0.5.1: … (BZ-XX-nn)`. A task review ran over each cluster's merged diff, and
four clusters (`rt-blocks`, `rt-entities`, `wearables`, `interact`) got a follow-up fix round on top of that.

| | |
|---|---|
| Version | `0.5.0 → **0.5.1**` (single `revision` bump in the root pom, commit `f16cfbd`) |
| Branch | `0.5.1`, cut from `master` at `9fa25c3`. Thirteen `fix/0.5.1-<cluster>` branches forked from `f16cfbd` and merged back one at a time; HEAD is the last of those merges. |
| Scope | 108 docket rows: 66 carried over from the 2026-09-10 cross-project docket (revalidated 2026-09-24), 62 found in the same hunt |
| Outcome | **107 fixed**, 1 partially fixed (`BZ-FA-06` — biological and throwable durability fixed, melee's own path was already correct) |
| Tests | **1,628** across both modules, green on `mvn -o -q test` (was 1,407 before the wave) |
| Compatibility | Almost entirely additive. A handful of real API deltas — see §4 of `documentation/migration.md` and the compatibility note in `documentation/bartizan-api.md`. No `.yml` shape changed; every default-preserving fix is listed in §3 below. |

The per-row status notes (commit, tests, root cause, what changed) live in the docket artifact's database
(collection `bugs`, doc id = row id). The sources this wave was driven from are in
`Gangland Warfare/brainstorming/cross-docket-2026-09-10/bartizan/`.

## 1. What was fixed, by system

| System | Rows | Highlights |
|---|---|---|
| `WM` weapon/registry | 15 | Three P0s in the weapon-registry/reload path: `/bartizan reload` left old reload timers running on discarded instances (`BZ-WM-13`); a finished reload wrote its magazine onto *whatever* weapon was in hand instead of its own item (`BZ-WM-14`); a reload commit consumed ammo before checking the weapon was still a top-level inventory item, so a weapon on the cursor/in a crafting grid/in a bundle lost ammo for nothing (`BZ-WM-15`). Also: the per-weapon reload amount is no longer shared mutable state on the `ReloadType` enum (`BZ-WM-03`), the registry is pruned per player on quit (`BZ-WM-04`), a throwable's press-lock and registry slot are now keyed so one holder can't overwrite another's instance (`BZ-WM-06`), and scope/reload potion effects are released on shutdown (`BZ-WM-12`). |
| `FA` firing actions | 8 | Two P0s: BURST follow-up rounds kept firing on the old weapon after a hotbar swap and wrote themselves onto the newly-held weapon (`BZ-FA-10`); a thrown grenade's display item was a real, hopper-collectible entity that minted a genuine item per throw (`BZ-FA-13`). Also: melee/throwable weapons authored with `Ammunition:`/`Reload:` never depleted their magazine (`BZ-FA-03`), fire blocks placed by the plugin were never reverted on shutdown (`BZ-FA-04`), a cancelled shot refunded a hardcoded 1 round regardless of `Consumed_Amount` (`BZ-FA-05`), the static fire-rate lock map was never pruned (`BZ-FA-12`), and a melee armor-piercing follow-up could strand a UUID that made the *next* hit on that entity fall back to vanilla damage (`BZ-FA-11`). `BZ-FA-06` (partial): `Durability_On_Shot` now applies to biological and throwable weapons; melee already applied it per landed hit through a path outside the directory the docket's revalidation grepped. |
| `WE` wearables | 9 | One P0, needing two follow-up fixes: hotbar-swap and right-click armor equip bypassed the `bartizan.wearables.<key>` permission gate entirely (`BZ-WE-01`) — see §2. Also: removing a wearable no longer strips an unrelated active effect of the same `PotionEffectType` (`BZ-WE-10`), a Set tier's `REACTIVE` level now actually rolls (`BZ-WE-08`) and its `SWIFT` level is rejected as inert instead of silently doing nothing (`BZ-WE-09`), and a misspelled `Traits:` key now warns instead of contributing nothing (`BZ-WE-02`). |
| `RT` raytrace/blocks/explosions | 14 | Three P0s, two needing follow-up fixes: no weapon-caused block break ever fired a vanilla `BlockBreakEvent`, so no protection plugin could veto it (`BZ-RT-01`, §2); cancelling `WeaponRaytraceImpactEvent` on a block hit did not actually stop the crack/break (`BZ-RT-14`); cosmetic `DROPPED_ITEM` projectile visuals were hopper-collectible (`BZ-RT-18`). Also: `Armor_Piercing` under-delivered against armored targets and needed a follow-up to become toughness-aware (`BZ-RT-16`, §2); `CombatEligibility` was never consulted for the entity being shot/exploded, only the shooter, and needed a follow-up to cover beam/incendiary/melee too (`BZ-RT-03`/`BZ-RT-20`, §2); an explosion fired its damage event before confirming the damage actually landed (`BZ-RT-19`); a hit target's pre-existing invulnerability flag was cleared and never restored (`BZ-RT-04`); block-damage regen tasks threw after a world unload (`BZ-RT-06`, §2). |
| `CF` config parsing | 16 | No P0s, but the widest silent-failure sweep: a weapon or ammo entry that stopped parsing was never pruned from the catalogue on reload, so it kept serving the stale pre-edit definition (`BZ-CF-12`, P1); `AmmunitionConverter` silently discarded a per-instance name/lore/color override the moment it ran (`BZ-CF-15`, P1). The rest replace silent defaults/coercions with a `ConfigReport` warning or a clean load-time error: an unknown `Category`/unresolvable `Material` (`BZ-CF-06`/`11`), `Consumed_Amount`/`Capacity`/`Restore`/numbered-reload amount of 0 (`BZ-CF-03`/`04`), empty `Effects_Per_Level` on a `BIOLOGICAL` weapon (`BZ-CF-05`), an unrecognised `Selective_Fire`/`Reload.Type`/`Allowed_Modes` entry (`BZ-CF-14`), a malformed `Modifiers.*` DSL entry (`BZ-CF-13`), and an `Explosion.Cluster`/`Airstrike Count` above 32 (`BZ-CF-17`). |
| `CM` commands | 6 | No P0s. A give command amount above the target material's max stack size now mints one uuid per physical item instead of one uuid shared across a whole stack (`BZ-CM-05`, P1). Also: the `/bartizan` `weapon` alias, which shadowed `WeaponCommand`'s own `weapon` subcommand, is gone (`BZ-CM-02`); give amounts are clamped to `[1, MAX_AMOUNT]` (`BZ-CM-01`); a `Skins.Named` entry literally called `default` is rejected as reserved (`BZ-CM-06`). |
| `EV` interact/fire-control | 20 | The largest system and three P0s, all in the shared interact hub: an off-hand throwable was never consumed, giving unlimited grenades (`BZ-EV-11`); an off-hand gun/flamethrower shot duplicated the weapon into the main-hand slot (`BZ-EV-12`); charge release and incendiary AUTO spray kept firing after the weapon left the hand entirely — drop, move, empty the hand mid-charge, it still fired (`BZ-EV-13`). Also: per-weapon state is now cleared on quit (`BZ-EV-01`), async watchdogs were moved to the main thread (`BZ-EV-02`), an F-swap now seeds `Equip_Delay` and runs `ON_HOLSTER`/`ON_EQUIP` (`BZ-EV-15`), an unresolved weapon-tagged item no longer fires a real vanilla crossbow arrow (`BZ-EV-16`), and kill-credit correctness got three fixes (`BZ-EV-19`/`20`/`21`). |
| `NU` plugin lifecycle | 3 | `Bartizan.onDisable` no longer resets Keystone's shared, server-global `PacketBridge` — deleted the call outright rather than the docket's suggested `keystone.version` bump, since bumping the pin and editing README/migration.md were both out of scope for this run (`BZ-NU-01`). Dead soft-dependency "Found/Linked" console logging that never actually linked anything is deleted (`BZ-NU-02`). A destroyed NPC now stops its in-flight burst instead of firing 1–3 more shots after it's gone (`BZ-NU-04`). |
| `EF` effects engine | 6 | Three P0s: `CommandHookEffect` dispatched a mob's player-settable display name straight into console command text — a selector/argument injection risk (`BZ-EF-04`); the `Firework` effect detonated a real, untracked firework whose vanilla splash damage landed on anyone nearby (`BZ-EF-05`); `Target: nearby` never filtered through `DamageRules`, so `Push`/`Potion`/`Ignite`/`Command(As: player)` effects could hit the shooter, teammates or a `CombatEligibility`-protected victim (`BZ-EF-06`). Also: `On_Status_Expire` now gets the infecting shooter as its effect source (`BZ-EF-01`), and boss bars are removed on plugin disable (`BZ-EF-02`). |
| `IM` WeaponMechanics importer | 7 | No P0s. A colliding sanitized ammo id (e.g. `5.56mm`/`5,56mm`) is now reported instead of silently keeping the first and dropping the second's reference (`BZ-IM-04`, P1); an imported gun with no explicit `Selective_Fire` default now starts single-fire, not auto (`BZ-IM-03`, P1); hand-built `ammunition.yml` text now escapes backslashes so a stray one can't corrupt the file (`BZ-IM-02`, P1). Also: `--dry-run` now catches same-run `fileKey` collisions (`BZ-IM-05`), a `Push`/`Leap` mechanic setting both speed and height is reported (`BZ-IM-06`), and an unset `Command{}` console flag now defaults to `As: player` and is reported rather than silently running as console (`BZ-IM-07`). |
| `HU` HUD/stats/placeholders | 4 | One P0: the PlaceholderAPI expansion called the *minting* weapon lookup from PAPI's own thread, so an async placeholder read could register a weapon and overwrite its live NBT-derived state (`BZ-HU-03`) — fixed with a new read-only `WeaponService.peekWeapon` and a `ConcurrentHashMap` registry. Also: a failed quit-time stats save no longer permanently drops that session's stats (`BZ-HU-01`), the HUD boss bar no longer leaks across a quit-time shot (`BZ-HU-02`), and `/bartizan stats`' Deaths field now counts every player death, not only weapon-claimed ones (`BZ-HU-04`). |

## 2. Review follow-ups

Four of the thirteen clusters got a task-review pass after their first batch landed, and every finding was fixed
before merge:

- **`rt-blocks` — `BZ-RT-01`'s own listener vetoed its own synthetic break [Critical], plus a regeneration leak
  [Important].** The synthetic `BlockBreakEvent` `BlockDamageManager` now fires used the shooter as its `Player` —
  and `WeaponInteract.onBlockBreak` cancels every `BlockBreakEvent` from a player holding a
  `Cancel.Break_Blocks: true` weapon (the shipped default), so in the default config Bartizan cancelled its own
  break before any third-party protection plugin ever saw it. Fixed with a new `bartizan-api` type,
  `weapon.modifiers.WeaponBlockBreakEvent extends BlockBreakEvent` — a marker subclass `BlockDamageManager`
  constructs instead of a plain `BlockBreakEvent`, which `WeaponInteract.onBlockBreak` now recognises and skips
  before touching anything else, while a real hand-mined break is still cancelled exactly as before. The same round
  fixed a leak this uncovered: a block that reached its hit threshold but had its break vetoed by a listener kept a
  permanent stage-9 crack and a leaked `damagedBlocks` entry, because neither `destroyBlock` nor
  `breakAndScheduleRestore` called `scheduleRegeneration` on the cancelled path — both now do.
- **`rt-blocks` — `BZ-RT-06`'s `getWorld() == null` guard could never fire [Critical].** The prior round guarded
  `sendBlockDamage`/`clearBlockDamage` with `location.getWorld() == null`, but on the Spigot 1.16.5+ API
  `Location.getWorld()` *throws* `IllegalArgumentException` once the world's weak reference clears — it never
  returns `null`, so the guard was dead code and the original crash still reproduced. Replaced every such guard
  with `!location.isWorldLoaded()` (the method the API itself documents for this check), and hardened the two
  scheduled lambdas that can outlive a world unload (`startSmoothRegeneration`'s repeating task,
  `breakAndScheduleRestore`'s restore task) to check it and clean up their `damagedBlocks` entry instead of ticking
  against an unreachable block.
- **`rt-entities` — `BZ-RT-16`'s armor-piercing fix was itself wrong [Critical].** The first round's
  `piercingDamage / (1 - normalReduction)` correctly inverts a *flat* armor model, but `living.damage()` runs
  Minecraft's real formula, which is quadratic in the damage argument and depends on `Armor_Toughness` — dividing
  by a constant reduction overcorrected roughly 3x against diamond/netherite armor. The follow-up reads
  `Armor_Toughness` via `XAttribute`, re-implements vanilla's real `CombatRules.getDamageAfterAbsorb` formula, and
  bisection-solves for the pre-armor damage that lands the intended post-armor amount once `living.damage()`
  re-reduces it for real.
- **`rt-entities` — `BZ-RT-08`'s tracer fix put particles in the shooter's own crosshair [Important].** Moving the
  tracer's first leg to the raytrace's real origin (the shooter's eye) removed a visual kink but put every tracer
  particle directly on the shooter's own aim point. Reverted to seeding from `WeaponMuzzle.compute(...)` (the
  documented muzzle-offset design), accepting the original minor kink as the tradeoff.
- **`rt-entities` — `BZ-RT-03`'s `CombatEligibility` guard was gun-only [Important].** The shared filter
  (`DamageRules.isProtected`) was fixed, but the predicate that calls it (`advanceRay`'s inline entity lambda) only
  ran the `DamageRules` branch for a `GunWeapon` — beam, incendiary, melee and biological actions never reached it,
  so a downed/protected player was still hittable by those. Extracted the lambda into
  `WeaponRaytracerImpl.isEligibleTarget(Entity, LivingEntity, RaytraceRequest)` with a weapon-agnostic
  `CombatEligibility` clause outside the `GunWeapon`-only branch.
- **`wearables` — `BZ-WE-01`'s right-click gate never actually ran for `RIGHT_CLICK_AIR` [Critical].** Decompiling
  the real Spigot API confirmed a `RIGHT_CLICK_AIR` `PlayerInteractEvent` is `isCancelled() == true` from
  construction (`clickedBlock == null`), and the handler's `ignoreCancelled = true` made Bukkit's listener registry
  skip it for exactly the ordinary "look at open air and right-click to equip" case — the common path stayed wide
  open. Removed `ignoreCancelled`, and switched the denial from `event.setCancelled(true)` (which also blocked an
  unrelated block interaction while the item was merely held) to `event.setUseItemInHand(Event.Result.DENY)`.
- **`wearables` — `BZ-WE-01`'s `HOTBAR_SWAP` handling resolved `getItem(-1)` for an off-hand swap [Important].**
  Pressing F to swap an off-hand item into an armor slot produces `InventoryAction.HOTBAR_SWAP` with
  `getHotbarButton() == -1` (confirmed against decompiled `InventoryClickEvent`), which the fix's
  `player.getInventory().getItem(hotbarButton)` call passed straight through — `IndexOutOfBoundsException` on a
  real server, and an unchecked bypass even where it didn't throw. Fixed by resolving `getItemInOffHand()` whenever
  `getHotbarButton() < 0`.
- **`interact` — `BZ-FA-03`'s throwable half never gated on the empty magazine.** The batch gave `ThrowableAction`
  a `consumeAmmoIfTracked` that depletes a configured magazine, but nothing stopped `activate()` once that
  magazine hit 0 — unlike `MeleeAction`'s existing empty-mag guard, a throwable with `Ammunition:`/`Reload:`
  configured kept throwing, detonating and consuming stack items forever. Added the guard as the first statement
  in `activate()`, playing the standard empty-click sound via `EmptyMagSoundGate.play(...)` and returning before
  the `WeaponShootEvent` fires.

## 3. Behaviour changes consumers/admins must know about

1. **Off-hand weapon uses are inert until gate HN.** A right-click with the off hand no longer fires, throws,
   charges or sprays anything on any weapon type — the vanilla off-hand use stays denied. `Dual_Wield` is listed as
   Missing until gate HN; the off-hand path used to duplicate items and give unlimited grenades. Off-hand reload,
   skin changes, and the API's `getHeldWeapon` are unchanged (`BZ-EV-11`, `BZ-EV-12`).
2. **A weapon's state writes only to the item carrying that weapon's own uuid.** Ammo, fire mode, skin and
   durability are written to the hand (main, then off) whose item's uuid matches — never to a different weapon
   happening to be in hand (`BZ-WM-14`, `BZ-EV-12`).
3. **A vanilla `BlockBreakEvent` (`WeaponBlockBreakEvent`) now fires for a player-attributed weapon block break** —
   raytrace hits and explosion block damage alike — so a protection plugin (WorldGuard, GriefPrevention, …) gets
   the same veto chance it gets for a hand-mined block. Bartizan's own `Cancel.Break_Blocks` listener recognises
   and skips its own synthetic event instead of vetoing it against itself (`BZ-RT-01`, §2).
4. **`CommandHookEffect` no longer dispatches a raw, player-settable entity name as console command text**, closing
   a selector/argument injection risk; a mob's `&` codes are stripped from `%player%`/`%victim%` in every effect's
   placeholder text (`BZ-EF-04`).
5. **`Target: nearby` is now filtered through `DamageRules`** — a `Push`/`Potion`/`Ignite`/`Command(As: player)`
   nearby-target effect skips the shooter and teammates, the same as the direct damage paths already did
   (`BZ-EF-06`).
6. **The `Firework` effect type is visual-only and deals no damage to anyone** — shooter, teammates or bystanders
   (`BZ-EF-05`).
7. **Cosmetic projectile visuals and thrown-grenade display items can no longer be hoppered.** `DROPPED_ITEM`
   cosmetic visuals and a thrown grenade's display `Item` are both PDC-tagged and refused by hopper pickup; the
   `FALLING_BLOCK` half was deliberately not covered (`BZ-RT-18`, `BZ-FA-13`).
8. **PlaceholderAPI placeholders are read-only.** `%bartizan_*%` never registers a weapon or mutates its ammo,
   durability or fire mode from PAPI's own (possibly async) thread; an item unused since boot reads straight off
   its NBT via a new non-minting lookup (`BZ-HU-03`).
9. **A give command amount above the target material's max stack size now mints one uuid per physical item**,
   instead of stamping one shared uuid across a whole stack (`BZ-CM-05`).
10. **New parser warnings and errors**, all newly surfaced (no shipped weapon/ammo file triggers any of them):
    a `Config_Version:` key now warns and loads instead of aborting the whole file (`BZ-CF-02`); an unknown
    `Category` or unresolvable `Material` warns instead of silently falling back to `OTHER`/`FEATHER`/
    `IRON_PICKAXE` (`BZ-CF-06`, `BZ-CF-11`); `Consumed_Amount: 0` now consumes 1 round (`BZ-CF-03`); `Capacity`/
    `Restore`/a numbered-reload amount of `0` is clamped to `1` with a warning (`BZ-CF-04`); an unrecognised
    `Selective_Fire`/`Reload.Type`/`Allowed_Modes` entry now warns instead of silently coercing to a different live
    mode (`BZ-CF-14`); an empty `Effects_Per_Level` on a `BIOLOGICAL` weapon now fails to load instead of shipping
    silently inert (`BZ-CF-05`); a malformed `Modifiers.*` DSL entry now warns instead of vanishing with no trace
    (`BZ-CF-13`); `Break_Blocks` hits of `0` is clamped to `1` with a warning instead of dividing by zero
    (`BZ-RT-13`); `Explosion.Cluster`/`Airstrike Count` above `32` falls back to the default of `3` with an error
    (`BZ-CF-17`).
11. **Worn-effect removal only strips Bartizan's own granted effect.** Taking off a wearable no longer wipes an
    unrelated active effect of the same `PotionEffectType` from another source (a splash potion, a status-weapon
    hit) — only an effect whose amplifier still matches the wearable's grant and is inside its re-apply window is
    removed (`BZ-WE-10`).
12. **`StatsService` keeps the dirty flag on a failed save.** A disk hiccup at the exact moment a player
    disconnects no longer silently discards that session's stats — the write retries on the next autosave or
    shutdown instead of being dropped (`BZ-HU-01`).
13. **Importer changes**: a colliding sanitized ammo id is now reported in the import report instead of silently
    dropping one weapon's reference (`BZ-IM-04`); an imported gun with no explicit `Selective_Fire` default now
    starts single-fire, not auto (`BZ-IM-03`); hand-built `ammunition.yml` text now escapes backslashes
    (`BZ-IM-02`); `--dry-run` now catches same-run `fileKey` collisions too (`BZ-IM-05`); a `Push`/`Leap` mechanic
    setting both speed and height is reported (`BZ-IM-06`); an unset `Command{}` console flag now defaults to
    `As: player` and is reported rather than silently running as console (`BZ-IM-07`).
14. **A weapon or ammo entry that stops parsing is pruned from the catalogue on `/bartizan reload`**, instead of
    continuing to serve its stale pre-edit definition until a full server restart (`BZ-CF-12`).
15. **Wearable equip via hotbar-swap and plain right-click now goes through the same
    `bartizan.wearables.<key>` permission gate** as drag/shift-click — needed the two follow-up fixes in §2
    (`BZ-WE-01`).
16. **Kill credit follows the weapon that actually delivered the fatal blow.** A slow rocket/flare or a burn-based
    death credits the weapon that hit, not whatever the killer currently holds (`BZ-EV-19`); a non-fatal graze
    inside Bukkit's ~5s combat-tracker window no longer steals a poison/wither kill (`BZ-EV-21`); killing a mob
    (not just a player) with a Bartizan weapon now counts in `/bartizan stats` and fires `On_Kill`, though mob-kill
    assists are deliberately not credited (`BZ-EV-20`, §4).
17. **`/bartizan stats`' Deaths field now counts every player death** — fall, drown, lava, void, starvation,
    unarmed PvP, vanilla mob kill included — not only weapon-or-status-claimed ones (`BZ-HU-04`).
18. **`Armor_Piercing` now delivers close to its configured bypass against armored targets**, including
    toughness-bearing diamond/netherite armor — needed the follow-up in §2 to become toughness-aware; the first
    round's fix still overcorrected roughly 3x on toughness armor (`BZ-RT-16`).
19. **`CombatEligibility` is now consulted for the victim, not just the shooter, on every weapon-raytrace and
    explosion damage path** — guns, beams, incendiary, melee (via the §2 follow-up) and biological, plus rocket/
    grenade/cluster/airstrike blasts (`BZ-RT-03`, `BZ-RT-20`, §2).
20. **Disabling or `/reload`-ing Bartizan no longer resets Keystone's shared, server-global `PacketBridge`**, so it
    no longer silently downgrades recoil/packet handling to a no-op for other Keystone-powered plugins still
    running (only observable on a multi-plugin server) (`BZ-NU-01`).
21. **`/weapon` no longer dispatches at all.** The `/bartizan` `weapon` alias was removed because it shadowed
    `WeaponCommand`'s own `weapon` subcommand label — use `/bartizan weapon …` or `/btz weapon …` instead
    (`BZ-CM-02`).
22. **A `Skins.Named` entry literally called `default` is rejected** with a config warning and ignored — `default`
    is the reserved clear-selection keyword in `/bartizan weapon skin <name|default>` and `BartizanApi.setSkin`
    (`BZ-CM-06`).

## 4. Deferred, partial and not done

| Item | State | Why |
|---|---|---|
| `BZ-FA-06` (melee half) | not reproducible | The docket's revalidation grepped `decreaseDurability`/`getDurabilityData` only under `weapon/action/` and concluded melee was broken. It isn't: `WeaponInteract`'s two `MeleeAction` call sites already call `Weapon#applyOnHitDurability` (in `bartizan-api`, outside that directory) after a landed swing. Adding a second call in `MeleeAction` would double-decrement durability — a regression, not a fix. |
| Join/respawn/pickup-into-held-slot `Equip_Delay` | deferred | Only a hotbar-slot change and an F-swap seed `Equip_Delay` (`BZ-EV-15`). None of join/respawn/pickup has a single Bukkit event that unambiguously means "this weapon just entered the main hand", and the docket rated this minor. A player can still skip a weapon's first `Equip_Delay` by joining or respawning with it selected. |
| Burst `SequenceTimer` not cancelled on hotbar swap | deferred | `BZ-FA-10`'s uuid guard makes a holstered weapon's leftover burst rounds do nothing, but the timer itself keeps running. If a player swaps back to the same gun within the burst window, the rest of the burst fires from it again. |
| Mob-kill assist crediting | out of scope | `BZ-EV-20` fires `WeaponKillEntityEvent`/`On_Kill`/stats for a mob kill, but `StatsService.recentDamage` stays player-victim-only — the docket itself called mob-kill assists optional and noted it needs its own despawn leak-sweep. |
| `BeamAction`'s unrestored `setInvulnerable(false)` | flagged, not fixed | The same defect class as `BZ-RT-04` (`WeaponRaytracerImpl`), in a different file outside that bug's listed file scope. |
| `WeaponInteract.equipDelayUntil`/`lastMeleeSwingMs`, `MeleeAction.meleeCooldowns` | flagged, not fixed | Same "never pruned except on swap" defect class `BZ-FA-12` fixed for the fire-rate lock map; each leaks one `Long` per weapon uuid. The same prune-on-write line would fix them. |
| `WeaponSelectiveFireChangeListener`, `WeaponService.setWeaponData` | flagged, not fixed | Still read via `getHeldWeaponItem(player)` (main-hand-first, no uuid match) and write the main-hand slot — the same defect class `BZ-EV-12` fixed elsewhere, outside that batch's file scope. |
| `BlockDispenseArmorEvent` (dispenser-equip) | out of scope | `BZ-WE-01` only covers player-driven equip (hotbar-swap, right-click, drag/shift-click); a dispenser arming an armor stand or player is untouched. |
| `settings.yml`'s stale hook-list comment | not fixed (`.yml`) | Still calls `On_Status_Apply`/`On_Status_Expire`/`On_Beam_Fire` "reserved — fired by later gates", which is out of date; flagged in the `effects` cluster report, belongs to a different bug, and `.yml` files are out of scope for this documentation pass. |
| `BZ-IM-04`/`BZ-IM-06` auto-remediation | rejected | The docket's optional suggestions (auto-suffixing a colliding ammo id; redesigning `Push`/`Leap` to carry an independent vertical component) were both rejected as bigger than the smallest fix; both bugs report the collision instead, at the point where the ambiguity actually originates. |
| `RecoilManager#clone()` | removed, not reimplemented | `BZ-WM-08` deleted the dead, always-throwing override rather than implementing `Cloneable` — no caller exists. A future caller wanting a real shallow copy has to add `Cloneable` back itself. |

## 5. Verification

Every fix went through a red-first test (written to fail against the pre-fix code, confirmed, then made to pass),
and the whole reactor passed on `mvn -o -q test` after every commit in every worktree, and again after the four
review follow-up rounds. Final count: 1,628 tests across `bartizan-api` and `bartizan-plugin`, 0 failures, 0 errors.
No `.java`, `.yml` or `pom.xml` file changed as part of writing this documentation — the code this record describes
was already final at `HEAD` before this pass started.
