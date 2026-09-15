# Bartizan

Standalone Spigot weapons plugin: weapons, ammunition, wearables, projectiles and reflective recoil. Split out of
Gangland Warfare's `gangland-features/gangland-weapon` module in the Bartizan wave (2026-09-08) so weapons can be
installed on any Keystone-powered server, not just Gangland's.

## Install

Drop `Bartizan-0.3.0.jar` beside `Keystone-1.9.0.jar` and `NBTAPI.jar` in `/plugins`. Bartizan `depend`s on both
Keystone and NBT-API — both must already be installed and enabled, or Bartizan fails to load (Keystone's
`NbtBridge.detect()` falls back to a no-op accessor when NBT-API is absent, which makes every Bartizan item inert,
so it is a hard `depend:`, not a `softdepend:`). Soft-depends on `ViaVersion`, `PlaceholderAPI`.

## Data folder

```
plugins/Bartizan/settings.yml
plugins/Bartizan/message/message_en.yml
plugins/Bartizan/weapon/<22 files>.yml
plugins/Bartizan/items/ammunition.yml
plugins/Bartizan/items/wearables.yml
```

Bartizan keeps no database. A weapon item's identity and state live in its own NBT (`uuid`, `weapon`, `ammo-left`,
`selective-fire` tags) and the runtime registry is rebuilt from those tags on first use. A `database/` folder or a
`.weapon-import-done` marker left behind by 0.1.0 is ignored and can be deleted.

## Modules

| Artifact | Purpose |
|---|---|
| `bartizan-api` | The weapon/ammo/wearable model and service contracts (`BartizanApi`, `WeaponCatalog`, events, `NpcRangedAttack` SPI impl surface). `provided` scope in every consumer; zero `net.minecraft`, zero `org.bukkit.craftbukkit`, zero `org.luckyraven.gangland` symbols. |
| `bartizan-plugin` | The runtime: services, listeners, commands, item vocabulary. Shaded into `Bartizan-0.3.0.jar`. Never a dependency of anything else. |

Consumers (e.g. Gangland Warfare's `gangland-features/*` modules) depend on `bartizan-api` at `provided` scope and
discover `BartizanApi` through Bukkit's `ServicesManager` at runtime — Bartizan never names a consumer's types, and
a consumer never names Bartizan's plugin-side (`bartizan-plugin`) types.

See [`documentation/bartizan-api.md`](documentation/bartizan-api.md) for the service table and a worked resolution
example, and [`documentation/migration.md`](documentation/migration.md) for what server owners moving off an older
Gangland Warfare weapon module need to do.

## Package map (as-built, 0.3.0)

```
bartizan-api   org.luckyraven.bartizan.api                    BartizanApi
                                       .ammo                   Ammunition, AmmunitionCatalog
                                       .combat                 CombatEligibility
                                       .event                  WeaponEvent, WeaponShootEvent, WeaponRaytraceImpactEvent,
                                                                WeaponEntityDamageEvent (zone/distance, gate HK), WeaponKillEntityEvent,
                                                                WeaponAssistEvent (gate HK),
                                                                WeaponReloadEvent/Start/Complete, WeaponChangeSelectiveFireEvent,
                                                                WeaponChargeLevelEvent, WeaponBeamFireEvent,
                                                                WeaponStatusApplyEvent, WeaponStatusExpireEvent
                                       .item                   WeaponItemApi, AttributeModifiers
                                       .npc                    NpcWeaponFactory, NpcWeaponController
                                       .raytrace                WeaponRaytracer, RaytraceContext, RaytraceRequest, WeaponVisualSpawner
                                       .weapon                 Weapon + 6 subclasses (incl. BeamWeapon), WeaponType, ThrowableType,
                                                                SelectiveFire, WeaponTag, ProjectileType, ProjectileState, WeaponCatalog,
                                                                BodyZone (gate HK), SkinState (gate HJ)
                                       .weapon.dto              27 config records (AmmunitionData, ChargeData, BeamData,
                                                                StatusData, DamageData, DropoffStep, ExplosionData (gate
                                                                HI-a, incl. nested Cluster/Airstrike/Detonation),
                                                                VisualData, BouncyData (gate HI-b), EffectHook (28 hooks,
                                                                incl. On_Block_Hit, the zone hooks and gate HL's
                                                                On_Unequip/On_Hit_Taken), EffectSpec, EffectsData, HudData,
                                                                HandlingData (incl. AttributeEntry), MuzzleOffsetData,
                                                                SkinsData, ...)
                                       .weapon.durability        DurabilityCalculator
                                       .weapon.modifiers          BlockDamageManager, DamageMath, ExplosionMath + action/*
                                       .weapon.recoil            RecoilManager
                                       .weapon.reload            Reload, ReloadType, InstantReload, NumberedReload
                                       .weapon.spread            SpreadManager
                                       .wearable               Wearable (Attributes/Set/Effects_While_Worn/Effects, gate HL),
                                                                WearableCatalog

bartizan-plugin org.luckyraven.bartizan                       Bartizan, BartizanApiImpl
                                       .ammo                   AmmunitionManager
                                       .bootstrap              BartizanContext, DefaultListenerService
                                       .command(.data|.wearable) WeaponCommand/Give/Get/Info/List/Skin, Ammunition*, Wearable*, DebugCommand, StatsCommand,
                                                                WmImportCommand (gate HM - lives here, not importer.wm, so BartizanContext's
                                                                @CommandHandler scan of org.luckyraven.bartizan.command actually finds it)
                                       .config                 KernelConfig, FilesConfig, WiringConfig, ItemConfig
                                       .configuration(.parser) WeaponAddon, AmmunitionAddon, the 15 YAML section parsers (incl. BeamWeaponParser,
                                                                HudSectionParser, ExplosionSectionParser (gate HI-a), SkinSectionParser (gate HJ))
                                       .effect(.impl)          Effect, EffectContext, EffectRunner + 15 *HookEffect
                                       .file                   BartizanSettings, BartizanMessages, WeaponLoader
                                       .fire                   PluginFireRegistry
                                       .hud                    HudService, WeaponPlaceholders, BartizanExpansion, PlaceholderApiSupport
                                       .importer.wm            WmWeaponImporter, WmMechanicsTranslator, WmColorTranslator, WmYamlEmitter,
                                                                WmImportReport, WmItemConverterListener (gate HM; WmImportCommand is in
                                                                .command instead - see that row)
                                       .item                   converters, serializers, refreshers, WeaponItemApiImpl, BartizanItemVocabulary
                                       .listener(.*)           WeaponInteract, ScopeJumpListener, WeaponCraftingListener,
                                                                WeaponSprintListener,
                                                                death/fire/player/projectile/reload/selective/wearable
                                       .npc                    NpcWeaponControllerImpl, NpcWeaponFactoryImpl
                                       .raytrace               WeaponRaytracerImpl, WeaponShooting, WeaponMuzzle, SteppedProjectileTask,
                                                                ExplosionHandler (gate HI-a, unified AOE explosions), ProjectileMotion,
                                                                BeamRenderer, HitZone
                                       .stats                  StatsService, StatsListener, PlayerStats, WeaponStat
                                       .status                 StatusEffectService, ActiveStatus, StatusListener
                                       .util                   BartizanChatUtil, BlockGroupResolver, EmptyMagSoundGate, PotionEffectParser
                                       .weapon(.action)        WeaponService, WeaponManager, DamageRules, CircumstanceRules,
                                                                GunAction/FullAutoTask/MeleeAction/...,
                                                                ChargeController (shared charge-then-release timer), BeamAction
                                       .wearable               WearableAddon, WearableService (SetTier, resolveTraitLevels,
                                                                applyInsulatedReduction, onHitTaken), WearableEffectsService
```

`WeaponShooting`, `WeaponMuzzle` and `SteppedProjectileTask` live in `bartizan-plugin`'s `raytrace` package, not
`bartizan-api` — moved there at gate GD once their only external caller (a Gangland NPC combat delegate) became
Bartizan's own `NpcWeaponControllerImpl`. `RaytraceContext` and `WeaponVisualSpawner` stay in `bartizan-api` because
`WeaponRaytracer`'s own signature names them.

## Importing from WeaponMechanics

`/bartizan import weaponmechanics [--dry-run] [--force] [path]` (default `path`: `plugins/WeaponMechanics`)
translates every WM weapon, ammo reference and (where a mapping exists) mechanic into Bartizan YAML under
`plugins/Bartizan/weapon/`, and writes a per-weapon report of what was mapped, approximated, or dropped to
`plugins/Bartizan/import/weaponmechanics-<date>.txt` — nothing disappears silently. `--dry-run` writes only the
report; an existing weapon file is never overwritten without `--force`. Players' existing WM items keep working
without being reissued (`WmItemConverterListener` rebuilds one into its imported equivalent the moment it's next
held/clicked, gated by `settings.yml`'s `Import.Convert_WeaponMechanics_Items`). See
[`documentation/migration.md`](documentation/migration.md#11-moving-off-weaponmechanics-gate-hm) for coverage
details and [`documentation/weapons-roadmap.md`](documentation/weapons-roadmap.md) §6 for the full mapping table.

## Build

```
mvn clean install            # bartizan-plugin/target/Bartizan-0.3.0.jar
mvn clean install -DskipTests
```

Keystone (`org.luckyraven:keystone-*:1.9.0`) must already be installed to `~/.m2` (`mvn clean install` in the
Keystone repo) before this reactor resolves.
