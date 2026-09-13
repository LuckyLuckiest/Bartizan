# Weapons roadmap: feedback, beams, WeaponMechanics parity, import

> **How to use this file.** A design plan for Bartizan's next wave (gates `HA`–`HN`), written 2026-09-13 against
> the working tree at `0e18356` plus the in-progress database removal (0.2.0). It is grounded in two code traces:
> Bartizan's own runtime (`bartizan-api` / `bartizan-plugin`) and a shallow clone of
> [WeaponMechanics](https://github.com/WeaponMechanics/WeaponMechanics) (`weaponmechanics-core`, 4.1.x, master on
> 2026-09-11) plus its [wiki](https://cjcrafter.gitbook.io/weaponmechanics/) and the
> [Mechanics](https://cjcrafter.gitbook.io/mechanics/) DSL wiki.
>
> - Citations are `File.java` names, not line numbers; grep the symbol before editing.
> - Each gate lists **scope**, **config**, **classes**, **tests**, and **skipped** (with the trigger to un-skip).
> - YAML examples follow the house rules: block-style maps, `Capitalized_Underscore` keys, `&` colour codes,
>   3-space indent like the shipped weapon files.
> - Status legend in the parity matrix: **Have** (exists and works), **Partial** (exists, narrower than WM),
>   **Missing**, **Dead** (config key parsed but never read), **Skip** (deliberately not doing, reason given).
>
> Rendered page with the gate strip and status chips: https://claude.ai/code/artifact/193bb934-53aa-428f-8c9d-63a234e9a10a

---

## 0. Where Bartizan stands today (what the plan builds on)

The full trace lives in the session that produced this file; the parts that shape the design:

| Area | Today | Why it matters for this plan |
|---|---|---|
| Hit detection | One server-side engine, `WeaponRaytracerImpl`. `RaytraceRequest.impactHandler` short-circuits the default damage pipeline; biological, incendiary, melee and throwable already route through it. | Every new on-hit behaviour (status effects, beam pierce, hit zones) plugs into this one seam. |
| Feedback | Shooter: reload/charge/selective-fire action bars, one hardcoded crit sound. Victim: **nothing** beyond vanilla. No title, boss bar, hit marker, kill feed, ammo HUD. | The single biggest gap versus WM, and the root of the "biological weapons give no feedback" complaint. |
| Biological | `BiologicalAction`: hold RMB to charge (`Charge_Time_Per_Level` × `Max_Charge_Level`), release fires one 1-target ray, applies **only** `Effects_Per_Level[level-1]` as raw `PotionEffect`s. Nothing tracked, nothing persisted, no kill credit for poison/wither deaths. | The user-facing bug. Also has an off-by-one landmine when `Effects_Per_Level` is shorter than `Max_Charge_Level`. |
| Ray gun | `ray_gun.yml` is `Category: biological` with flashier potions. No beam concept exists anywhere (no charge for guns, no pierce, no sustained fire, no heat). | Beam weapons are greenfield, but the charge machinery in `BiologicalAction` and the press-hold watchdog in `WeaponInteract` are reusable. |
| Config | 22 self-contained weapon files, no defaults/inheritance. Modifier DSL is positional dash strings. `Flyby_*` sound keys are parsed and never read. `WeaponKillEntityEvent` is declared and never fired. `NpcWeaponController.aimErrorDegrees` is stored and never used. | Dead surfaces get wired in the gate that needs them, not deleted. |
| Persistence | The `weapon` table, autosave and cleanup are being deleted in the current working tree; live state (ammo, durability, selective fire) rides in item NBT. | Nothing below may assume a database. Stats (`HK`) use flat files. |
| Wearables | Percentage reductions via seven traits; `Extra_Tags` are data only (jetpack is consumed by Gangland, not Bartizan). Loader bypasses the `ConfigReport` pipeline. | Wearable gate (`HL`) adds runtime effects and the attribute keys WM's ArmorMechanics has. |
| NPCs | `NpcWeaponFactory` fires guns through the same `WeaponShooting.fire` path as players. | WM only has a MythicMobs skill here; Bartizan is already ahead. |
| Tests | Plain JUnit 5 + Mockito, no MockBukkit. `WeaponAddon` and all six parsers have **zero** tests; `WeaponRaytracerImpl` is untested. Fixtures are duplicated across the two modules. | Every gate below adds a loader test for its new keys; `HA` adds the missing "every bundled YAML loads" test first. |

### 0.1 Cheap fixes to land before anything else

These are one-file fixes surfaced by the trace. Do them in `HA` so later gates start clean.

1. `BiologicalAction`: clamp the `Effects_Per_Level` index and treat an empty list as "no effects" instead of throwing.
2. `WeaponRaytracerImpl.handleEntityImpact`: `applyWearableReduction(damage, living, isProjectile = true)` is hardcoded `true` for every ray. Pass `weapon.getType() == GUN` so melee/beam/biological rays use the generic path.
3. `WeaponEntityDamageEvent` fires only on the throwable path; `WeaponRaytraceImpactEvent` fires everywhere. Fire `WeaponEntityDamageEvent` from the default pipeline too, after damage is applied, so one listener sees all weapon damage. Fire the dead `WeaponKillEntityEvent` from `WeaponDeathListener`.
4. `WeaponAddonTest`: load every YAML under `src/main/resources/weapon/` through `WeaponAddon.registerWeapon` and assert zero `ConfigReport` errors. This is the regression net for every config key added below.
5. Merge the duplicated `WeaponFixtures` / `BukkitRegistryFixture` into `bartizan-api`'s test-jar (`maven-jar-plugin` `test-jar` goal) and depend on it from `bartizan-plugin`.

---

## 1. Concept A — Effects: one feedback engine for every hook (gate `HA`)

**Problem.** Every piece of feedback today is hardcoded Java (`ActionBarManager.send(shooter, "&cBroken")`, the crit
`ITEM_SHIELD_BREAK`). WM instead exposes ~20 hook points per weapon, each taking a list of "Mechanics"
(`Sound{...} @Target{}`). Bartizan needs the same shape or every gate below re-invents feedback, and the importer
has nowhere to put WM's Mechanics.

**Decision.** Add an `Effects` section: a map of hook → list of effect entries. Entries are **block-style maps**,
not WM's `Name{k=v}` strings, because the house YAML rule forbids inline `{}` and a map is what `NodeReader`
already parses. The importer translates WM's string DSL into these maps.

```yaml
Effects:
   On_Shoot:
      - Type: Sound
        Sound: ENTITY_GENERIC_EXPLODE
        Volume: 1.0
        Pitch: 1.4
        Target: source
      - Type: Particle
        Particle: FLAME
        Count: 4
        Offset: 0.05 0.05 0.05
        At: muzzle
   On_Hit:
      - Type: Sound
        Sound: ENTITY_ARROW_HIT_PLAYER
        Target: source
   On_Headshot:
      - Type: Action_Bar
        Text: "&c✚ Headshot"
        Target: source
   On_Kill:
      - Type: Title
        Title: "&cKILL"
        Subtitle: "&7%victim%"
        Fade_In: 2
        Stay: 20
        Fade_Out: 5
        Target: source
```

**Hooks (v1).** `On_Shoot`, `On_Hit`, `On_Headshot`, `On_Kill`, `On_Miss`, `On_Empty`, `On_Deny`, `On_Equip`,
`On_Holster`, `On_Reload_Start`, `On_Reload_End`, `On_Reload_Cancel`, `On_Scope_In`, `On_Scope_Out`,
`On_Explode`, `On_Charge_Level`, `On_Charge_Full`, `On_Beam_Fire`, `On_Status_Apply`, `On_Status_Expire`.
Unknown hook names are a `ConfigReport` warning, not an error, so old files keep loading.

**Effect types (v1).** `Sound`, `Custom_Sound` (Keystone `SoundEffect` custom path), `Particle`, `Potion`,
`Action_Bar`, `Title`, `Boss_Bar` (timed, Bukkit `Bukkit.createBossBar`), `Message`, `Command` (console or
player, `%player%`/`%victim%` substitution), `Push` (velocity along look or away from source), `Camera_Shake`
(Keystone `PacketAdapter.relativeCameraRotation`, the same call recoil uses), `Ignite`, `Cooldown`
(`Player#setCooldown`, shows the vanilla item-cooldown overlay), `Firework`, `Lightning` (effect-only strike).
Fourteen small classes behind one `Effect` interface; each is 15–40 lines because Keystone already wraps sound,
particles, action bars and chat colour.

**Targets.** `Target: source | victim | nearby` (+ `Radius`), `At: source | muzzle | impact | victim` for
location-bound effects. Placeholders available in text: `%player%`, `%victim%`, `%weapon%`, `%damage%`,
`%distance%`, `%level%`, `%ammo_left%`, `%ammo_max%`, `%deny_reason%`.

**Classes.** `bartizan-api`: `weapon/dto/EffectsData` (hook → `List<EffectSpec>`), `EffectSpec` record
(type + `Map<String,String>` args). `bartizan-plugin`: `effect/EffectContext`, `effect/Effect` +
`effect/impl/*`, `effect/EffectRunner`, `configuration/parser/EffectsSectionParser`. `Weapon` gains
`effects()`; the six existing actions call `effectRunner.run(weapon, Hook.ON_SHOOT, ctx)` at the points where they
already play sounds. Existing `Shoot.Sound.*` keys keep working and are internally lowered to `On_Shoot`/`On_Empty`
effects, so no shipped YAML changes.

**Global defaults.** `settings.yml` gains `Default_Effects:` with the same shape; a weapon's list replaces the
default for that hook (no merging, keeps it predictable).

**Tests.** `EffectsSectionParserTest` (every type parses, unknown type reports), `EffectRunnerTest` with a fake
`Effect` counting invocations, and the `WeaponAddonTest` net from §0.1.

**Skipped.** WM's targeter shapes (`Sphere{}`, `Box{}`, `Scatter{}`) and conditions (`?Biome`, `?LightLevel`);
add when a config actually needs a shaped particle field. `Flinch`/hurt animation needs packets on Spigot < 1.20.2;
skip.

---

## 2. Concept B — Biological weapons that tell you what is happening (gate `HB`)

**Problem.** Charge feedback exists for the shooter (action bar + ring). On hit, the victim only sees vanilla
potion icons, the shooter gets no confirmation, poison/wither deaths are not credited to the weapon, and charging
to level 3 silently drops levels 1–2's effects.

**Decision.** Model the hit as a tracked **status** (infection, radiation, whatever the weapon names it), owned by
a `StatusEffectService`, and drive all feedback from that object. Potion effects become the *mechanical* payload of
a status; the status is what the player sees and what kill credit is attached to.

### 2.1 Config (extends today's `Shoot:` for `Category: biological`)

```yaml
Shoot:
   Charge:
      Time_Per_Level: 20
      Max_Level: 3
      Min_Level_To_Fire: 1
      Auto_Fire_At_Max: false
   Range: 30.0
   Base_Damage: 4.0
   Cumulative_Levels: true
   Effects_Per_Level:
      - "POISON-60-1"
      - "POISON-100-1,SLOWNESS-80-1"
      - "WITHER-60-1,NAUSEA-100-1"
   Status:
      Name: "&2Infected"
      Icon: "☣"
      Duration_Per_Level: 200
      Stacking: escalate
      Max_Level: 3
      Kill_Credit_Window: 200
      Contagion:
         Radius: 3.0
         Chance: 0.15
         Interval: 40
         Level_Drop: 1
      Cure:
         Items:
            - MILK_BUCKET
         Wearable_Trait: sealed
   Feedback:
      Victim:
         Title: "&2☣ INFECTED"
         Subtitle: "&7by %shooter% · %weapon%"
         Boss_Bar:
            Text: "&2☣ %status% &7Lv %level% · %seconds%s"
            Color: GREEN
            Style: SEGMENTED_10
         Ambient_Particle: SPELL_MOB
         Ambient_Color: "#3FA34D"
         Ambient_Interval: 10
         Sound_Apply: ENTITY_ZOMBIE_VILLAGER_CURE
         Sound_Expire: ENTITY_EXPERIENCE_ORB_PICKUP
         Message_Expire: "&aThe infection has passed."
      Shooter:
         Hit_Marker_Sound: ENTITY_EXPERIENCE_ORB_PICKUP
         Action_Bar: "&2☣ %victim% &7infected · Lv %level%"
         Message_Spread: "&2☣ %victim% &7caught it from &2%carrier%"
   Charge_Feedback:
      Sound_Level_Up: BLOCK_NOTE_BLOCK_PLING
      Pitch_Per_Level: 0.25
      Sound_Full: BLOCK_BEACON_ACTIVATE
      Tracer_Color: "#3FA34D"
```

Old files without `Charge:`/`Status:`/`Feedback:` keep loading: `Charge_Time_Per_Level`/`Max_Charge_Level` are
read as aliases, `Cumulative_Levels` defaults to `false`, and `Status` defaults to a minimal object named after
the weapon with a boss bar and hit marker only. So `syringe_gun.yml` and `ray_gun.yml` get victim feedback the day
`HB` lands, before their YAML is touched.

Every `Feedback.*` key is sugar over `Effects` hooks (`On_Status_Apply`, `On_Status_Expire`, `On_Charge_Level`,
`On_Charge_Full`, `On_Hit`); the parser lowers them into `EffectsData`, so servers that want something exotic
write the hook list directly.

### 2.2 Runtime

- `StatusEffectService` (plugin): `Map<UUID, ActiveStatus>`; `ActiveStatus` = victim, shooter, weapon, level,
  expiry tick, boss bar handle. One `RepeatingTimer` every 10 ticks: tick boss-bar progress and action bar,
  ambient particles at the victim (visible to everyone, so allies see who is infected), contagion roll, expiry.
- `BiologicalAction.fireRay`'s impact handler calls `statusService.apply(victim, shooter, weapon, level)`.
  Stacking: `refresh` (reset timer), `extend` (add duration), `escalate` (level +1 up to `Max_Level`, re-apply
  potions for the new level), `ignore`.
- `Cumulative_Levels: true` applies entries `[0..level-1]` merged, keeping the strongest amplifier per potion.
- Kill credit: `WeaponDeathListener` (already open in the IDE) asks `statusService.activeOn(victim)` when the
  death has no weapon damager; within `Kill_Credit_Window` ticks of the last application the shooter gets the
  kill, the weapon's `Death_Messages` are used, and `WeaponKillEntityEvent` fires.
- Cure: `PlayerItemConsumeEvent` on a listed item clears the status; a worn wearable with the `sealed` trait
  reduces incoming level by its trait level (new trait, see `HL`).
- Cleanup: `PlayerQuitEvent` and `PlayerDeathEvent` remove the boss bar; statuses are **not** persisted (they are
  seconds long; a relog is an acceptable cure).
- Tracer: `flushTracer` is currently gun-only. Generalise it to any weapon with a tracer colour so the biological
  ray draws a line.

**Classes.** `bartizan-api`: `weapon/dto/BiologicalData` grows (`ChargeData`, `StatusData`, `Cumulative_Levels`),
new events `WeaponStatusApplyEvent` (cancellable), `WeaponStatusExpireEvent`. `bartizan-plugin`: `status/`
package (`StatusEffectService`, `ActiveStatus`, `StatusListener`), parser changes in `BiologicalWeaponParser`.

**Tests.** `StatusEffectServiceTest` with an injected clock: apply/stack/expire/cure/contagion roll with a seeded
`Random`; `BiologicalWeaponParserTest` for aliases and defaults; `WeaponDeathListenerTest` gains the
status-credit case.

**Skipped.** Persisting statuses across restarts; add if a status ever lasts minutes. Per-status custom potion
icons (needs resource pack). Contagion between NPCs (players only in v1).

---

## 3. Concept C — Beam weapons (gate `HC`)

**Problem.** The ray gun is a re-skinned syringe gun: one target, potion payload, no visual beam. The request is
charge → visible build-up in front of the muzzle → release → a beam that pierces and hits hard.

**Decision.** New `Category: beam` with its own action, sharing the charge controller with biological weapons and
adding **entity pierce** to the raytracer (which also gives guns WM's `Through.Entities` for free).

### 3.1 Config

```yaml
Information:
   Name: "&bArc Lance"
   Category: beam
   Material: SPYGLASS
Shoot:
   Charge:
      Time_Per_Level: 8
      Max_Level: 4
      Min_Level_To_Fire: 1
      Auto_Fire_At_Max: false
   Beam:
      Mode: burst
      Range: 60.0
      Width: 0.6
      Ammo_Per_Level: 1
      Pierce:
         Entities: -1
         Blocks: 0
         Damage_Multiplier_Per_Target: 0.85
      Damage:
         Base: 6.0
         Per_Level: 5.0
         Head: 4.0
         Knockback: 0.8
         Fire_Ticks: 0
      Preview:
         Particle: END_ROD
         Length_Per_Level: 1.5
         Interval: 2
         Guide_Line: false
      Render:
         Core_Particle: DUST
         Core_Color: "#66CCFF"
         Glow_Particle: END_ROD
         Thickness: 0.25
         Step: 0.25
         Duration: 8
      Impact:
         Particle: FLASH
         Sound: ENTITY_LIGHTNING_BOLT_IMPACT
         Scorch_Blocks: true
   Recoil:
      Amount: 1.5
      Push: 0.2
      Power_Up: 0.0
      Pattern: []
   Sound:
      Default_Sound:
         Sound: ENTITY_GUARDIAN_ATTACK
         Volume: 1.0
         Pitch: 0.8
Ammunition:
   Ammo_Type: energy_cell
   Capacity: 8
   Consume: 1
   Restore: 8
Reload:
   Cooldown: 3
   Type: instant
Modifiers:
   Break_Blocks:
      - "glass-1-DESTROY"
```

### 3.2 Runtime

1. **Charge** — `WeaponInteract` routes `BEAM` to the same press-hold watchdog biological uses. A new
   `ChargeController` (extracted from `BiologicalAction`) owns the timer, level, action-bar meter, `On_Charge_Level`
   / `On_Charge_Full` effects and the release callback. `BiologicalAction` is refactored to use it (net deletion).
2. **Preview** — every `Preview.Interval` ticks while charging: a particle segment from `WeaponMuzzle.compute`
   along the look vector, length `Length_Per_Level × level`, plus the existing `ParticleUtil.spawnChargeRing` at
   the muzzle scaled by level. `Guide_Line: true` additionally draws a faint dotted line to `Range` so the player
   can aim the pierce. Sound pitch rises per level via `Charge_Feedback` (same keys as biological).
3. **Fire** — on release (or at max when `Auto_Fire_At_Max`): consume `Ammo_Per_Level × level`, then one
   `RaytraceRequest` with `maxDistance = Range`, `hitboxExpansion = Width / 2`, `maxEntityHits = Pierce.Entities`,
   `maxBlockPenetration = Pierce.Blocks`, `baseDamage = Base + Per_Level × (level − 1)`. The impact handler applies
   damage per target multiplied by `Damage_Multiplier_Per_Target ^ index`, head bonus via the hit-zone helper from
   `HF` (until then the existing `isHeadPosition`), knockback along the beam direction, fire ticks, then runs
   `On_Hit`/`On_Headshot`. `WeaponBeamFireEvent` (cancellable, carries level and the ordered target list) fires
   before damage.
4. **Render** — `BeamRenderer` draws the beam once per tick for `Duration` ticks: core `DUST` at `Step` spacing,
   glow particle offset by `Thickness` around the axis, impact burst and sound at the end point. Uses
   `ParticleUtil.spawnLine` where its signature fits; otherwise a 30-line loop in the renderer.
5. **Scorch** — `Scorch_Blocks: true` runs the existing `BlockDamageManager` in `CRACK` mode on the end block, so
   block regeneration settings apply unchanged.

**Raytracer change (the only shared-engine edit).** `RaytraceRequest` gains `maxEntityHits` (default 1, keeps
current behaviour). In `WeaponRaytracerImpl.advanceRay`, an entity impact records the entity in the context's
hit set, calls the impact handler with `hitIndex`, and continues the ray from the exit point when hits remain.
The same field backs `Modifiers.Pierce_Entities` for guns in `HE`.

**Sustained mode (`Mode: sustained`, gate `HN`).** After charge completes, hold to keep the beam on: re-fire the ray
every `Tick_Rate` ticks with `Damage_Per_Tick`, consume ammo every `Ammo_Interval`, render continuously, stop on
release or empty. Reuses `handleIncendiaryAuto`'s loop shape. Pairs with the optional `Heat:` section below.

**Heat (optional, `HN`).** `Heat.Per_Shot`, `Heat.Cooling_Per_Tick`, `Heat.Overheat_Lockout`, `Heat.Boss_Bar`.
Written once, usable by beam, gun (minigun spin-up becomes `Heat.Warmup_Ticks`) and incendiary.

**Classes.** `bartizan-api`: `weapon/BeamWeapon`, `weapon/dto/BeamData`, `weapon/dto/ChargeData`,
`WeaponType.BEAM`, `event/WeaponBeamFireEvent`, `event/WeaponChargeLevelEvent`; `RaytraceRequest.maxEntityHits`.
`bartizan-plugin`: `configuration/parser/BeamWeaponParser`, `weapon/action/ChargeController`,
`weapon/action/BeamAction`, `raytrace/BeamRenderer`; `WeaponInteract.handleBeamCharge`; `WeaponAddon` dispatch.

**Tests.** `ChargeControllerTest` (levels per tick, min level, auto-fire); `BeamWeaponParserTest`;
`RaytraceContext`/pierce bookkeeping test (hit set, per-index multiplier) — the raytracer itself stays
Bukkit-bound and untested, so keep the pierce arithmetic in a pure helper.

**Skipped.** Reflecting beams off blocks (ricochet modifier already exists for bullets; reuse if wanted).
Beam-to-beam interaction. Client-side beam entity (guardian laser packet) — particles are enough and need no
packets.

---

## 4. WeaponMechanics parity matrix

WM's per-weapon modules and where Bartizan lands. "Gate" is where the gap closes.

### 4.1 Info

| WM key | Bartizan | Status | Gate |
|---|---|---|---|
| `Weapon_Item.Type/Name/Lore/Custom_Model_Data` | `Information.Material/Name/Lore/Custom_Model_Data` | Have | — |
| `Weapon_Item.Item_Model` | — | Missing (1.21.2+ only) | `HJ` |
| `Weapon_Item.Attributes` (e.g. movement speed) | — | Missing | `HE` (`Information.Attributes` list, Bukkit `AttributeModifier`) |
| `Weapon_Item.Unbreakable/Hide_Flags/Deny_Use_In_Crafting` | Keystone item builder handles unbreakable/flags; crafting deny missing | Partial | `HE` (`PrepareItemCraftEvent` cancel) |
| `Weapon_Info_Display.Action_Bar/Boss_Bar/Show_Ammo_In` | Ammo only in the item name | Missing | `HD` |
| `Dual_Wield` | — | Missing | `HN` (low value on Spigot; off-hand triggers) |
| `Weapon_Converter_Check` | Not needed: Bartizan identity is NBT | n/a | importer handles WM items (`HM`) |
| `Weapon_Get/Equip/Holster_Mechanics` | — | Missing | `HA` hooks `On_Equip`/`On_Holster` (+ `PlayerItemHeldEvent`) |
| `Weapon_Equip_Delay` | — | Missing | `HE` |
| `Cancel.Drop_Item/Swap_Hands/Break_Blocks/Arm_Swing` | Drop is the reload key (sneak + Q) | Partial | `HE` (`Information.Cancel.*`) |

### 4.2 Skin

| WM key | Bartizan | Status | Gate |
|---|---|---|---|
| `Skin.Default/Scope/Reload/Sprint/No_Ammo` (state CMD) | Single CMD | Missing | `HJ` — `Skins.<State>` swapped by the existing item refresher on ammo/scope/reload change |
| Named skins + `/wm skin` | — | Missing | `HJ` phase 2 |
| Attachments (WMP) | — | Missing | `HN` optional: `Attachments.<slot>` modifying `Spread/Recoil/Damage` |

### 4.3 Shoot

| WM key | Bartizan | Status | Gate |
|---|---|---|---|
| `Trigger.Main_Hand/Off_Hand` (20 trigger types) | RMB hardcoded; LMB = scope | Missing | `HE` — `Trigger:` with `right_click, left_click, drop_item, swap_hands, start_sneak, double_sneak, start_sprint` (all plain Bukkit events) |
| `Trigger.Circumstance` DENY/REQUIRED + `Deny_Mechanics` | — | Missing | `HE` — `Sneaking, Sprinting, Swimming, In_Midair, Reloading, Zooming, Ammo_Empty` + `On_Deny` |
| `Projectile_Speed`, `Delay_Between_Shots`, `Fully_Automatic_Shots_Per_Second`, `Burst` | `Projectile.Speed/Cooldown/Per_Shot` + WM's own shots-per-second table in `FullAutoTask` | Have | — |
| `Selective_Fire` | `Selective_Fire` + `Allowed_Modes` | Have | — |
| `Projectiles_Per_Shot` | Hardcoded 8 pellets for `SPREAD` | Partial | `HE` (`Projectile.Pellets`) |
| `Consume_Item_On_Shoot`, `Destroy_When_Empty`, `Ammo_Per_Shot`, `Reset_Fall_Distance` | `Weapon_Consumed`, `Consumed_Amount` | Partial | `HE` (two booleans) |
| `Spread.Base_Spread/Changing_Spread` | `Spread.Starting_Spread/Change` | Have | — |
| `Spread.Modify_Spread_When` (zoom/sneak/midair/sprint %) | — | Missing | `HE` |
| `Spread.Spread_Image` (PNG patterns) | — | Skip | needs an image pipeline; revisit if a shotgun pattern is ever requested |
| `Recoil` (mean/variance/damping model) | Pattern list + push | Partial | `HE` — `Recoil.Random.Mean_X/Mean_Y/Variance_X/Variance_Y` as an alternative to `Pattern`; importer maps 1:1 |
| `Offsets` (muzzle position) | Fixed 0.3 right / 0.2 down in `WeaponMuzzle` | Partial | `HE` (`Muzzle_Offset.Right_Hand/Left_Hand/Scope`) |
| `Custom_Durability` | `Information.Durability` | Have | — |
| `Attract_Mobs` (WMP), `Haptic` (Bedrock) | — | Skip | not worth it on a Java Spigot server |

### 4.4 Reload and Ammo

| WM key | Bartizan | Status | Gate |
|---|---|---|---|
| `Magazine_Size`, `Reload_Duration`, `Ammo_Per_Reload` | `Ammunition.Capacity`, `Reload.Cooldown`, `Reload.Type: num` | Have | — |
| `Unload_Ammo_On_Reload`, `Shoot_Delay_After_Reload`, `Auto_Reload_When_Empty` | — | Missing | `HG` |
| `Start/Finish/Out_Of_Ammo_Mechanics` | Sounds + action bar | Partial | `HA` hooks |
| `Ammo.Item_Ammo` | `Ammo_Type` + `ammunition.yml` | Have | — |
| Multiple `Ammo_Types` + switch trigger | One type per weapon | Missing | `HG` (`Ammunition.Types` list; `swap_hands` cycles) |
| No ammo section = infinite | `Ammo_Type` is required | Missing | `HG` (`Ammo_Type: none`) — needed by the importer |
| `Money/Experience_As_Ammo_Cost` | — | Skip (money needs Vault) | `HG` optional: exp only |
| `Show_Time.Reload` (item cooldown, exp bar, action-bar bar) | Action-bar text | Partial | `HD` (`Player#setCooldown` is one line) |
| `Firearm_Action` (SLIDE/PUMP/LEVER/REVOLVER open/close) | `Reload.Type: one` + "Opening" action bar | Partial | `HG` optional: `Firearm_Action.Type/Open/Close` as pre/post-shot delays with effects |

### 4.5 Scope

| WM key | Bartizan | Status | Gate |
|---|---|---|---|
| `Zoom_Amount` (real FOV) | `Scope.Level` = SLOWNESS amplifier | Partial | `HH` — v1 `Player#setWalkSpeed` negative (pure Bukkit, zooms FOV, slows movement); v2 client-only attribute via Keystone `PacketAdapter` if walk-speed side effects bite |
| `Night_Vision` | — | Missing | `HH` |
| `Zoom_Stacking` | — | Missing | `HH` |
| `Shoot_Delay_After_Scope` | — | Missing | `HH` |
| Spread bonus while scoped | Scoping does nothing to spread | Missing | `HE` (`Modify_Spread_When.Zooming`) |
| `Pumpkin_Overlay` (WMC) | — | Skip | needs helmet-slot swap packets |

### 4.6 Damage

| WM key | Bartizan | Status | Gate |
|---|---|---|---|
| `Base_Damage`, `Fire_Ticks`, `Base_Explosion_Damage` | `Damage.Base/Fire_Ticks/Explosion_Damage` | Have | — |
| `Critical_Hit` | Bartizan-only | Have (+) | — |
| `Dropoff` (distance → delta) | None for bullets | Missing | `HF` (`Damage.Dropoff` list of `"distance delta"`) |
| `Head/Body/Arms/Legs/Feet/Back` bonus + mechanics | `Damage.Head` via `|Δy| > 1.4` | Partial | `HF` — hit zone from impact height ÷ entity height (head ≥ 0.75, legs ≤ 0.35, feet ≤ 0.12), back from `dot(victimFacing, shotDir)`; arms approximated as torso hits near the hitbox edge |
| `Armor_Damage` | — | Missing | `HF` (damage worn armour durability) |
| `Enable_Owner_Immunity`, `Ignore_Teams` | Explosions can self-damage | Missing | `HF` (scoreboard team check is plain Bukkit) |
| `Damage_Modifiers` (global: per armour point, sneaking/walking/sprinting/midair/shielding) | Wearable traits cover armour | Partial | `HF` (`settings.yml Damage_Modifiers`) |
| `Assists_Event` | — | Missing | `HK` |
| `Kill` mechanics | — | Missing | `HA` `On_Kill` + `WeaponKillEntityEvent` wired in §0.1 |
| `Explosion.Knockback_Multiplier`, `Disable_Vanilla_Knockback` | Melee only | Partial | `HF` (`Damage.Knockback` on guns/explosions) |

### 4.7 Projectile and Explosion

| WM key | Bartizan | Status | Gate |
|---|---|---|---|
| Hitscan vs physical | `ProjectileType` BULLET/SPREAD (hitscan) vs ROCKET/FLARE (cosmetic entity + `SteppedProjectileTask`) | Have | — |
| `Projectile_Settings.Type` (ARMOR_STAND model, DROPPED_ITEM, FALLING_BLOCK, PRIMED_TNT, FIREBALL…) | Fireball/Firework cosmetics; Item entity for throwables | Partial | `HI` (`Projectile.Visual.Type/Item/Custom_Model_Data` via `WeaponVisualSpawner`) |
| `Gravity`, `Drag`, `Minimum/Maximum speed` | `Projectile.Gravity` (parabolic drop) | Partial | `HI` |
| `Sticky` | Throwables only | Partial | `HI` (physical projectiles too) |
| `Bouncy` per-material multipliers + `Rolling` | `Bounces/Max_Bounces` | Partial | `HI` |
| `Through.Blocks` | `Modifiers.Penetration` | Have | — |
| `Through.Entities` | — | Missing | `HC` (`maxEntityHits`) exposed as `Modifiers.Pierce_Entities` in `HE` |
| `Maximum_Alive_Ticks/Travel_Distance`, `Extinguish_In_Water`, `Incendiary_Projectile` | `Projectile.Distance` | Partial | `HI` |
| `Explosion_Shape` DEFAULT/SPHERE/CUBE/PARABOLA + `Explosion_Exposure` | Sphere, linear falloff | Partial | `HI` (`Explosion.Shape`, `Exposure: distance | line_of_sight`) |
| `Detonation.Impact_When Spawn/Entity/Block + Delay_After_Impact` | `Throw.Fuse_Time`, `Sticky` | Partial | `HI` |
| `Block_Damage` + `Regeneration` for explosions | `Break_Blocks` modifier + global regeneration (bullets) | Partial | `HI` (explosions use the same `BlockDamageManager`) |
| `Cluster_Bomb`, `Airstrike` | — | Missing | `HI` |
| `Flashbang` | `Throw.Type: stun` | Have | — |
| `Trail` particles | `Modifiers.Tracer` (dust line, bullets) | Partial | `HI` (`Projectile.Trail` for physical projectiles) |
| `Cosmetics.Muzzle_Flash` | — | Missing | `HE` (3-line particle burst at muzzle) |
| `Cosmetics.Bullet_Zip` (fly-by) | `Flyby_*` keys | **Dead** | `HE` — wire: any ray passing within `Flyby_Range` of a player plays the fly-by sound to them |
| `Cosmetics.Splash`, block-specific impact particles | Impact sound only | Partial | `HE` (`BLOCK_CRACK` particle with the hit block's data) |
| `Third_Person_Pose` | — | Skip | packets |

### 4.8 Melee

| WM key | Bartizan | Status | Gate |
|---|---|---|---|
| `Enable_Melee`, `Melee_Range`, `Melee_Hit_Delay` | `Attack.Damage/Range/Cooldown/Knockback`, 5-ray cone | Have | — |
| `Melee_Attachment` (bayonet on a gun) | — | Missing | `HN` (`Attack:` allowed on a gun; trigger `left_click` while sneaking) |
| `Melee_Miss` mechanics + `Consume_On_Miss` | — | Missing | `HA` `On_Miss` hook |

### 4.9 Everything outside the weapon file

| WM feature | Bartizan | Status | Gate |
|---|---|---|---|
| Commands `give/get/list/info/reload/wiki/ammo/stats/test` | `weapon/ammo/wearable give|info|list`, `reload`, `debug` | Partial | `HD` adds `get`; `HK` adds `stats`; `HM` adds `import` |
| PlaceholderAPI (`ammo_left`, `reload`, `firearm_state`, `selective_fire`, `durability`, `weapon_title`) | soft-depend declared, no expansion | Missing | `HD` |
| Statistics (`WeaponStat`: shots, hits per zone, kills, assists, longest kill…) | — | Missing | `HK` |
| Events (30) | 9 (one dead) | Partial | each gate adds its events; §0.1 wires the dead one |
| API (`generateWeapon`, `shoot(entity, weapon, dir)`, `isScoping`, `isReloading`, `tryReload`, `setSkin`) | `BartizanApi` + `NpcWeaponFactory` | Partial | `HD`/`HH`/`HJ` add `isScoping/isReloading/tryReload/setSkin`; `NpcWeaponController` already covers `shoot` |
| MythicMobs skill/condition | `NpcWeaponFactory` (any `LivingEntity`) | Have (+) | `HN` optional MythicMobs soft hook wrapping the factory |
| WorldGuard flags | `CombatEligibility` SPI | Have (+) via consumer | — (document the flag adapter as a consumer example) |
| Block regeneration article | Global `Block_Regeneration` settings | Have | — |
| Vivecraft, Geyser haptics | — | Skip | — |

### 4.10 Where Bartizan is already ahead

Keep and advertise: biological/status weapons (`HB`), beams (`HC`), incendiary cone weapons, the wearable trait
system, block groups in `Break_Blocks`, armor-piercing math that pre-compensates vanilla armour, ricochet with
per-material lists, NPC weapon controllers for any `LivingEntity`, and the `CombatEligibility` SPI.

---

## 5. Concept D — Wearables worth having (gate `HL`)

WM's companion ArmorMechanics sells set bonuses, bullet/explosion resistance and worn effects. Bartizan has the
resistance half. Add:

```yaml
hazmat_chest:
   Material: LEATHER_CHESTPLATE
   Name: "&2Hazmat Suit"
   Base_Damage_Reduction: 0.05
   Attributes:
      Armor: 4.0
      Armor_Toughness: 1.0
      Knockback_Resistance: 0.1
   Traits:
      sealed: 2
      insulated: 1
   Effects_While_Worn:
      - "SLOWNESS-0-0"
   Set: hazmat
   Effects:
      On_Equip:
         - Type: Sound
           Sound: ITEM_ARMOR_EQUIP_LEATHER
      On_Hit_Taken:
         - Type: Particle
           Particle: SPELL_MOB
           At: victim
Sets:
   hazmat:
      Pieces_2:
         Traits:
            sealed: 1
      Pieces_4:
         Traits:
            sealed: 2
            fire_resistant: 1
         Effects_While_Worn:
            - "REGENERATION--1-0"
```

- **New traits.** `sealed` (reduces incoming status level, `HB`), `insulated` (beam/energy damage reduction,
  `HC`), `night_vision`, `swift` (walk-speed attribute). All go through the existing `[maxLevel, perLevel]`
  table in `Wearable`.
- **Attributes.** `Armor`, `Armor_Toughness`, `Knockback_Resistance`, `Max_Health` as Bukkit `AttributeModifier`s
  stamped on the item; today wearables only reduce percentages, so a wearable on a non-armour-looking material
  gives no vanilla armour points.
- **Set bonuses.** `Sets.<name>.Pieces_N` merged into the resolved traits by `WearableService` when N pieces of
  the set are worn; recomputed on `PlayerArmorChange`-equivalent (Spigot has no such event; use
  `InventoryClickEvent` on armour slots + `PlayerInteractEvent` right-click-equip + a 20-tick sanity task).
- **Worn effects.** Potion list re-applied every 40 ticks while worn (amplifier `-1` skipped).
- **Armour damage.** `Damage.Armor_Damage` from `HF` reduces the worn wearable's durability.
- **Loader.** Move `WearableAddon` onto the `NodeReader`/`ConfigReport` pipeline the weapon parsers use, so a bad
  wearable is reported like a bad weapon.
- **Jetpack.** Stays data-only in Bartizan; Gangland consumes the tags. Not re-implemented here.

**Tests.** `WearableServiceTest` finally pins the damage math (currently untested); set-bonus resolution with
fake inventories.

---

## 6. Concept E — `/bartizan import weaponmechanics` (gate `HM`)

**Goal.** A server owner drops Bartizan next to an existing WeaponMechanics install and gets every weapon, ammo
and (where a mapping exists) every mechanic as Bartizan YAML, plus their players' existing WM items keep working.

### 6.1 Command

```
/bartizan import weaponmechanics [--dry-run] [--force] [path]
```

- Default `path` is `plugins/WeaponMechanics`. Reads `weapons/**/*.yml`, `projectiles/*.yml`, `ammos/*.yml`,
  `config.yml` (only `Damage_Modifiers`, `Placeholder_Symbols`).
- Writes `plugins/Bartizan/weapon/<title>.yml` and appends to `items/ammunition.yml`. Never overwrites an existing
  file without `--force`. `--dry-run` writes only the report.
- Report at `plugins/Bartizan/import/weaponmechanics-<date>.txt`: per weapon, every WM key that was mapped,
  approximated, or dropped, so nothing disappears silently. Approximations also land in the generated YAML as
  `# imported: <WM key> -> approximated as ...` comments, which is why the emitter writes text rather than going
  through `YamlConfiguration` (which drops comments).
- Runs on the main thread in chunks of 5 weapons per tick; 24 defaults finish in under a second.

### 6.2 Mapping table (the translator's spec)

| WM | Bartizan | Note |
|---|---|---|
| top-level title | file name + `Information.Name` | MiniMessage → `&` codes (`<gold>`→`&6`, `<gray>`→`&7`, `<bold>`→`&l`, `<reset>`→`&r`; `<#RRGGBB>`→`&#RRGGBB` if Keystone supports hex, else nearest code; unknown tags stripped) |
| `Info.Weapon_Item.*` | `Information.*` | `Attributes` → `Information.Attributes` (`HE`) |
| `Info.Weapon_Info_Display.Action_Bar.Message` | `HUD.Action_Bar` (`HD`) | placeholders `<ammo_left>`→`%ammo_left%`, `<firearm_state>`→`%reload_state%`, `<reload>`→`%reload%`, `<selective_fire>`→`%selective_fire%` |
| `Info.Weapon_Equip_Delay`, `Cancel.*` | `Shoot.Equip_Delay`, `Information.Cancel.*` | `HE` |
| `Info.*_Mechanics` | `Effects.On_Equip/On_Holster/…` | via the Mechanics translator |
| `Skin.Default` (+ `ADD n` states) | `Information.Custom_Model_Data`, `Skins.<State>` | `HJ`; before `HJ` only `Default` |
| `Projectile: "<ref>"` | inlined from `projectiles/*.yml` | `Type` with `Explosion` present → `ROCKET`; `ARMOR_STAND`/`DROPPED_ITEM` with a model → `Projectile.Visual` (`HI`); else `BULLET`. `Gravity`→`Projectile.Gravity`. `Through.Blocks`→`Modifiers.Penetration`. `Through.Entities`→`Modifiers.Pierce_Entities`. `Bouncy`→`Ricochet` (guns) or `Throw.Bounces` (throwables). `Sticky`→`Throw.Sticky` |
| `Shoot.Trigger` | `Shoot.Trigger` | `HE`; before that, right-click only + report |
| `Shoot.Projectile_Speed` | `Projectile.Speed` | WM divides by 10 to get blocks/tick in its `Shoot` serializer; convert to Bartizan's unit (verify `ProjectileData.speed` semantics in `WeaponShooting.fireSlow` before coding) |
| `Fully_Automatic_Shots_Per_Second` | `Selective_Fire: auto` + `Projectile.Cooldown = 1 / sps` | `FullAutoTask` already carries WM's table |
| `Burst.Shots_Per_Burst/Ticks_Between_Each_Shot` | `Projectile.Per_Shot`, `Projectile.Cooldown` | |
| `Delay_Between_Shots` | `Projectile.Cooldown` (semi) | |
| `Selective_Fire.Modes` | `Allowed_Modes` | |
| `Spread.Base_Spread`, `Changing_Spread.*` | `Spread.Starting_Spread`, `Spread.Change.*` | `Modify_Spread_When` → `HE` |
| `Recoil.Mean_X/Y, Variance_X/Y` | `Recoil.Random.*` (`HE`) | fallback before `HE`: generate a 12-entry `Pattern` from mean ± variance |
| `Reload.Magazine_Size/Reload_Duration/Ammo_Per_Reload` | `Ammunition.Capacity`, `Reload.Cooldown`, `Reload.Type` | `Reload_Duration` is ticks; confirm `Reload.Cooldown`'s unit against `Reload.java` before converting |
| `Reload.Ammo.Item_Ammo.Bullet_Item` | `ammunition.yml` entry + `Ammo_Type` | `Magazine_Item` merged into the same entry; magazine semantics dropped (reported) |
| no `Reload.Ammo` | `Ammo_Type: none` | requires `HG`; before that generates `wm_<title>` ammo and reports it |
| `Damage.Base_Damage/Head.Bonus_Damage/Fire_Ticks/Base_Explosion_Damage` | `Damage.Base/Head/Fire_Ticks/Explosion_Damage` | |
| `Damage.Dropoff`, `Armor_Damage`, `Enable_Owner_Immunity`, `Ignore_Teams`, `Body/Arms/Legs/Feet/Back` | `Damage.*` | `HF` |
| `Explosion.Explosion_Type_Data.Radius|Yield|Depth` | `Explosion_Radius` | shape approximated (reported); exact from `HI` |
| `Explosion` + `Shoot.Consume_Item_On_Shoot` | `Category: throwable`, `Throw.*` | `Detonation.Delay_After_Impact` with `Impact_When.Spawn` → `Fuse_Time`; `Flashbang` → `Throw.Type: stun`; `Cluster_Bomb`/`Airstrike` → `HI`, else reported |
| `Explosion.Block_Damage/Regeneration` | `Modifiers.Break_Blocks` + global regeneration | per-explosion regeneration → `HI` |
| `Scope.Zoom_Amount/Night_Vision/Zoom_Stacking` | `Scope.Level` (v1: `round(zoom)`), `Scope.Zoom/Night_Vision/Stacks` | `HH` |
| `Melee.Enable_Melee` | `Category: melee`, `Attack.*` | `Melee_Attachment` → `HN` |
| `Firearm_Action` | `Reload.Type: one` for REVOLVER/PUMP/LEVER, else reported | `HG` optional |
| `Trail.Particles` (DUST colour) | `Modifiers.Tracer` | |
| `Cosmetics.Muzzle_Flash`, `Bullet_Zip` | `Shoot.Muzzle_Flash`, `Shoot.Sound.Flyby_*` | `HE` |
| `Show_Time.Reload.*` | `HUD.Reload.*` | `HD` |
| `*Mechanics` lists | `Effects.<hook>` | see 6.3 |
| `Death_Messages` | generic default emitted | WM has none |

### 6.3 Mechanics translator

Parses WM's `Name{key=value, ...} @Targeter{...}` strings (a 40-line recursive-descent parser: identifier, brace
block, `key=value` pairs, optional `@Target`). Maps:

| WM mechanic | Effect `Type` |
|---|---|
| `Sound{sound, volume, pitch, noise, delayBeforePlay, listeners}` | `Sound` (`noise` → `Pitch_Variance`, `delayBeforePlay` → `Delay`) |
| `CustomSound{sound,...}` | `Custom_Sound` |
| `Particle{particle, count, noise, color, size, fadeColor}` | `Particle` |
| `Potion{potion, time, level, particles}` | `Potion` |
| `ActionBar{message}`, `Title{title, subtitle, fadeIn, stay, fadeOut}`, `Message{message}`, `BossBar{...}` | `Action_Bar`, `Title`, `Message`, `Boss_Bar` |
| `Command{command, console}` | `Command` |
| `Push{speed, direction}`, `Leap{...}` | `Push` |
| `Ignite{ticks}`, `Firework{...}`, `Lightning{}` | `Ignite`, `Firework`, `Lightning` |
| `CameraShake{...}` | `Camera_Shake` |
| `Damage{amount}`, `Blinding{...}`, `Shockwave`, `Skybeam`, `ExplosionCloud`, `SculkBloom`, `WardenDisturbance`, `FakeItem`, `DropItem` | not mapped → `# unmapped:` comment + report line |
| `@Source{}` / `@Target{}` / `@World{range}` | `Target: source | victim | nearby` + `Radius` |
| `listeners=Source{}` | `Target: source` (sound audible to the shooter only) |

### 6.4 Live item conversion

WM stamps its items with a `PersistentDataContainer` key in the `weaponmechanics` namespace (`weapon-title`,
plus `ammo-left`, `selective-fire`; confirm the exact key names against `utils/CustomTag.java` at build time).
A `WmItemConverterListener` on `PlayerItemHeldEvent` / `InventoryClickEvent` / `PlayerJoinEvent` reads those
keys with plain Bukkit (`new NamespacedKey("weaponmechanics", "weapon-title")`, no WM dependency), looks up the
imported Bartizan weapon of the same title, and rebuilds the stack through `WeaponItemApi` carrying ammo-left
over. Enabled by `settings.yml Import.Convert_WeaponMechanics_Items: true` (set by the import command, off by
default).

### 6.5 Classes and tests

`bartizan-plugin/importer/wm/`: `WmImportCommand`, `WmWeaponImporter` (the mapping table), `WmMechanicsTranslator`,
`WmColorTranslator`, `WmYamlEmitter` (block-style, comment-preserving, ~60 lines), `WmImportReport`,
`WmItemConverterListener`. No new dependency: WM files are read with Bukkit's `YamlConfiguration`.

Golden test: vendor WM's 24 default weapon files + `Default_Projectiles.yml` + `Default_Ammos.yml` into
`src/test/resources/wm/` (MIT licence, keep the header). `WmWeaponImporterTest` imports all of them, asserts the
report has no *errors*, then loads every generated file through `WeaponAddon` and asserts zero `ConfigReport`
errors. `WmMechanicsTranslatorTest` covers every mechanic above with one string each.

**Skipped.** Importing WM's `config.yml` global spread/recoil defaults (Bartizan has no globals until `HA`'s
`Default_Effects`; report them). Importing WMC/WMP-only sections (`Attachments`, `Pumpkin_Overlay`): reported.
Reverse export: no.

---

## 7. Gate schedule

Order is by user value, then by what later gates depend on. Sizes are relative (S ≈ a day of focused work, M ≈ a
few days, L ≈ a week+), not commitments.

| Gate | Title | Size | Depends on | Delivers |
|---|---|---|---|---|
| `HA` | Effects engine + §0.1 fixes + loader regression test | M | — | Hooks, 14 effect types, `Default_Effects`, `WeaponAddonTest`, unified damage event, `WeaponKillEntityEvent` fires |
| `HB` | Biological status + feedback | M | `HA` | `StatusEffectService`, victim boss bar/title/particles, shooter hit marker, cumulative levels, stacking, contagion, cure, poison-death kill credit, biological tracer |
| `HC` | Beam weapons (burst) | M | `HA` | `ChargeController` (shared with `HB`), `BeamWeapon`, preview, pierce, renderer, `WeaponBeamFireEvent`, `arc_lance.yml` sample, `ray_gun.yml` re-categorised to `beam` |
| `HD` | HUD + placeholders + `get` | S | `HA` | `HUD.Action_Bar/Boss_Bar/Show_Ammo_In`, reload item-cooldown/exp bar, PlaceholderAPI expansion, `/bartizan weapon get`, `isScoping/isReloading` on the API |
| `HE` | Shoot parity | M | `HA` | `Trigger` + `Circumstance` + `On_Deny`, `Modify_Spread_When`, `Recoil.Random`, `Muzzle_Offset`, `Equip_Delay`, `Cancel.*`, `Attributes`, `Pellets`, `Destroy_When_Empty`, `Reset_Fall_Distance`, muzzle flash, fly-by (wires dead keys), block-crack impact particles, `Pierce_Entities` |
| `HF` | Damage parity | M | `HA` | `Dropoff`, hit zones (head/body/arms/legs/feet/back) with bonus + effects, `Armor_Damage`, owner immunity, `Ignore_Teams`, global `Damage_Modifiers`, gun/explosion `Knockback` |
| `HG` | Reload/ammo parity | S | — | `Unload_Ammo_On_Reload`, `Shoot_Delay_After_Reload`, `Auto_Reload_When_Empty`, `Ammo_Type: none`, multiple ammo types + switch, optional `Firearm_Action` |
| `HH` | Scope parity | S | `HE` | FOV zoom, night vision, zoom stacking, shoot delay after scope, `tryReload` on the API |
| `HI` | Projectile/explosion parity | L | `HA` | Unified physical projectile (visual entity types, drag, sticky, bouncy per material, rolling, alive ticks), explosion shapes/exposures, detonation rules, explosion block damage + regeneration, cluster bomb, airstrike, trails |
| `HJ` | Skins | M | `HD` | State skins (Default/Scope/Reload/Sprint/No_Ammo), named skins, `/bartizan weapon skin`, `setSkin` API, `Item_Model` on 1.21.2+ |
| `HK` | Stats + assists | M | `HA`, `HF` | Per player-weapon counters (WM's `WeaponStat` list), assists window, `/bartizan stats`, Gson flat files under `plugins/Bartizan/stats/` (no database — it was just removed) |
| `HL` | Wearables | M | `HB`, `HF` | Attributes, set bonuses, worn effects, `sealed`/`insulated`/`night_vision`/`swift` traits, equip effects, armour damage, `ConfigReport` loader |
| `HM` | WeaponMechanics import | L | `HA`, `HD`, `HE`, `HF`, `HG` (for lossless mapping; can ship earlier with more report lines) | Command, translator, emitter, report, live item conversion, golden test over WM's defaults |
| `HN` | Stretch | — | `HC` | Sustained beam mode, `Heat:` (minigun spin-up), bayonet, dual wield, attachments, MythicMobs hook |

Every gate ends with: `mvn clean install` green, the new keys documented in the shipped YAML's header comments
(the house rule: comments are the user docs), `README.md` package map updated, and a `documentation/` note if a
public API surface changed.

---

## 8. Deliberately not doing

- **A string Mechanics DSL.** Block maps instead; the importer bridges. Cheaper to parse, matches the house YAML
  rule, and `NodeReader` already validates maps.
- **Packet-level cosmetics** (third-person poses, pumpkin overlays, hurt-animation flinch, client-side beam
  entities). Spigot-only with reflective `PacketAdapter` for recoil is the agreed ceiling; particles and Bukkit
  APIs cover the rest.
- **Spread images**, **Vivecraft**, **Bedrock haptics**, **money-as-ammo** (Vault): no demand, real cost.
- **Config inheritance / `extends:`** — tempting with 22+ near-identical files, but `Default_Effects` in `HA`
  covers the repetitive half (sounds); revisit when a second repeated block hurts.
- **Persisting statuses or heat** across restarts.
