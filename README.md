# Bartizan

Standalone Spigot weapons plugin: weapons, ammunition, wearables, projectiles and reflective recoil. Split out of
Gangland Warfare's `gangland-features/gangland-weapon` module in the Bartizan wave (2026-09-08) so weapons can be
installed on any Keystone-powered server, not just Gangland's.

## Install

Drop `Bartizan-0.1.0.jar` beside `Keystone-1.9.0.jar` in `/plugins`. Bartizan `depend`s on Keystone — Keystone must
already be installed and enabled. Soft-depends on `ViaVersion`, `PlaceholderAPI`, `NBTAPI`.

## Data folder

```
plugins/Bartizan/settings.yml
plugins/Bartizan/message/message_en.yml
plugins/Bartizan/weapon/<22 files>.yml
plugins/Bartizan/items/ammunition.yml
plugins/Bartizan/items/wearables.yml
plugins/Bartizan/database/bartizan.db          (SQLite; table `weapon`, columns uuid + type)
plugins/Bartizan/.weapon-import-done           (one-shot import marker — see documentation/migration.md)
```

## Modules

| Artifact | Purpose |
|---|---|
| `bartizan-api` | The weapon/ammo/wearable model and service contracts (`BartizanApi`, `WeaponCatalog`, events, `NpcRangedAttack` SPI impl surface). `provided` scope in every consumer; zero `net.minecraft`, zero `org.bukkit.craftbukkit`, zero `org.luckyraven.gangland` symbols. |
| `bartizan-plugin` | The runtime: services, listeners, commands, persistence, item vocabulary. Shaded into `Bartizan-0.1.0.jar`. Never a dependency of anything else. |

Consumers (e.g. Gangland Warfare's `gangland-features/*` modules) depend on `bartizan-api` at `provided` scope and
discover `BartizanApi` through Bukkit's `ServicesManager` at runtime — Bartizan never names a consumer's types, and
a consumer never names Bartizan's plugin-side (`bartizan-plugin`) types.

See `documentation/bartizan-api.md` for the service table and a worked resolution example, and
`documentation/migration.md` for what server owners moving off an older Gangland Warfare weapon module need to do.

## Build

```
mvn clean install            # bartizan-plugin/target/Bartizan-0.1.0.jar
mvn clean install -DskipTests
```

Keystone (`org.luckyraven:keystone-*:1.9.0`) must already be installed to `~/.m2` (`mvn clean install` in the
Keystone repo) before this reactor resolves.
