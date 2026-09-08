# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What Bartizan is

A standalone Spigot plugin for weapons, ammunition, wearables and projectile combat, split out of Gangland
Warfare's `gangland-features/gangland-weapon` module (2026-09-08, "Bartizan wave") so weapons can run on any
Keystone-powered server. Two Maven modules only: `bartizan-api` (the public model + contracts) and
`bartizan-plugin` (the runtime, shaded into `Bartizan-<version>.jar`). Depends on Keystone
(`org.luckyraven:keystone-*`) at `provided` scope; ships zero version-compat modules of its own — recoil goes
through Keystone's reflective `PacketAdapter.relativeCameraRotation`, not hand-written NMS adapters.

## Server platform

**Spigot only — not Paper.** Do NOT use Paper-specific APIs (e.g. `io.papermc.paper.event.player.PlayerJumpEvent`
or any `io.papermc.*` import). Use Spigot/Bukkit equivalents instead.

## The api/plugin split (`bartizan-api` vs `bartizan-plugin`)

- `bartizan-api` is the ONLY artifact a consumer plugin depends on (`provided` scope). It carries **zero**
  `net.minecraft.*` / `org.bukkit.craftbukkit.*` symbols and **zero** `org.luckyraven.gangland.*` symbols — the
  jar is never Paper-remapped, so an NMS symbol here breaks every consumer on Paper.
- `bartizan-plugin` is the runtime: services, listeners, commands, persistence, item vocabulary registration. It
  is never a dependency of anything — nothing outside `bartizan-plugin` names its classes.
- A type moves to `bartizan-api` only if a consumer needs to reference it directly (a domain type, a DTO, an
  event, a service contract) or if another api type's public signature requires it transitively. Everything else
  belongs in `bartizan-plugin`.
- Bartizan does **not** use `keystone-module` — it is a plain plugin, not a Keystone module host. No
  `ModuleLoader`, no `module.yml`, no `Host_Api`.

## Cross-plugin discovery

Bartizan publishes on Bukkit's `ServicesManager`: `BartizanApi`, `org.luckyraven.keystone.item.spi.ItemVocabulary`
(namespace `bartizan`), `WeaponRaytracer`. It pulls `CombatEligibility` from the `ServicesManager` (a consumer may
register one; Bartizan falls back to `CombatEligibility.DEFAULT` when absent). Every lookup is resolved **lazily,
at the point of use** — never cached in a field set at bean construction — because a consumer plugin may enable
before or after Bartizan.

## Build Commands

```
mvn clean install                # bartizan-plugin/target/Bartizan-<version>.jar
mvn clean install -DskipTests    # skip tests
mvn -pl bartizan-api -am install -DskipTests   # api only
```

Keystone (`org.luckyraven:keystone-*`) must already be installed to `~/.m2` before this reactor resolves — build
it with `mvn clean install` in the sibling `Keystone` repo first.

## Code Style

### Method braces

Every method body must have its opening and closing curly brackets on their own lines — never collapsed onto one
line.

**Wrong:**

```java
@Override
public String getSecond() { return "s"; }
```

**Correct:**

```java
@Override
public String getSecond() {
	return "s";
}
```

This applies to all methods regardless of how short the body is.

### Logging

Use Lombok `@CustomLog` (backed by Keystone's `org.luckyraven.keystone.logging.Logger`, configured in
`lombok.config`) and `log.warn/info/error`; never `Bukkit.getLogger()`. `module.properties` sets the logger
prefix.

### Nullability

`@Nullable` from `org.jetbrains.annotations`.

### Version-drifting enums

`Material` / `Particle` / `Sound` / `PotionEffectType` resolve through XSeries (`XMaterial`, `XParticle`,
`XPotion`) or Keystone's `org.luckyraven.keystone.sound.SoundEffect` — never raw `valueOf` and never
`player.playSound(..., Sound.X, ...)`.

### Chat and color codes

Colored chat goes through `BartizanChatUtil.color()` with `&` codes; never emit a literal `§` in source.

### YAML

Block-style maps only — never inline `{ key: value }` flow syntax, every key on its own line.
`Capitalized_Underscore_Separated` keys (lookup ids inside values stay lowercase). Never
`FileConfiguration.setDefaults()` / `copyDefaults()` for YAML fallbacks — it strips comments and breaks
user-facing docs. Bartizan reads config through Keystone's `FileHandlerReader` / `FileInitializer`, not Bukkit's
`getConfig()`.

## Testing

JUnit 5 + Mockito. `com.viaversion:viaversion-api` stays on the `provided`/test classpath even though Bartizan's
own code names no ViaVersion type after the reflective-recoil rewrite — `plugin.yml` keeps
`softdepend: [ViaVersion]`, and Mockito's inline mock maker retransforms the full type hierarchy of any mocked
type, so a `com.viaversion` field type anywhere in that hierarchy throws `TypeNotPresentException` the moment
anyone mocks a type that touches it. Do not remove that dependency as "unused" — see the comment in
`bartizan-plugin/pom.xml`.
