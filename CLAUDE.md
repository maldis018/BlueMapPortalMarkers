# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A Paper (Minecraft server) plugin that adds a toggleable "Nether Portals" marker layer to [BlueMap](https://bluemap.bluecolored.de/), kept in sync as portals are lit, discovered, and broken, and persisted across restarts. Single-module Gradle project; package root `dev.aldis.bluemapportalmarkers`.

## Build & verify

```sh
./gradlew build          # compiles + tests + shaded jar → build/libs/BlueMapPortalMarkers-<version>.jar
./gradlew test           # runs the JUnit 5 suite
./gradlew shadowJar      # produces the shaded, distributable jar (bStats bundled)
```

- **There is a JUnit 5 test suite** (since v0.3) under `src/test/java`, covering the pure domain logic: `Portal`, `PortalClustering`, `PortalStore` (incl. save/load + v0.1→v0.2 migration), `PortalLinker` (8:1 pairing), and `VersionCompare`. `./gradlew build` runs it. Bukkit/BlueMap-touching classes are deliberately *not* unit-tested (no MockBukkit) — keep new pure logic in Bukkit-free classes so it stays testable (this is exactly why `VersionCompare` is split out of `UpdateChecker`, which holds a `JavaPlugin` and so can't load on the test classpath).
- The **plain `jar` task is disabled**; `shadowJar` (classifier `""`) is the single canonical output. The distributable now bundles **only** bStats, relocated to `dev.aldis.bluemapportalmarkers.bstats`.
- For ad-hoc pure-logic checks the old **`jshell`** pattern still works, but prefer adding a JUnit test. **v0.2 was validated end-to-end on a real Paper + BlueMap server (2026-05-31).** No live server is available *in this dev environment*, so runtime-dependent changes (events, commands, BlueMap rendering, threading, bStats, the update checker) still need a real server to confirm.

## Toolchain constraints (these are load-bearing — do not "simplify" them)

- **Sources compile with `--release 25`**, not a Java toolchain. `paper-api` 26.1.x publishes Gradle metadata requiring a **JVM runtime of 25+**, so a Java 21 target fails dependency resolution. `build.gradle.kts` deliberately uses `tasks.withType<JavaCompile> { options.release.set(25) }` and **no `java.toolchain` block** (the build host only has JDK 26; a toolchain would try to provision a separate JDK).
- **The Gradle wrapper is pinned to 9.4.0** because that is the first Gradle release able to *run* on JDK 26. Don't downgrade it.
- The Paper/BlueMap dependencies are **`compileOnly`** (`io.papermc.paper:paper-api`, `de.bluecolored:bluemap-api`) — the server provides them at runtime; never bundle/shade them. **The one exception is bStats** (`org.bstats:bstats-bukkit`, `implementation`): the server does *not* provide it, so it is shaded **and relocated** into the plugin jar by `shadowJar`. gson is also still provided by the server (used `compileOnly` via paper-api in main; on the test classpath via `testImplementation`) — do not shade it.

## Architecture (the big picture)

Everything funnels through one in-memory `PortalStore`; BlueMap is a downstream view that gets rebuilt from it.

```
detection sources ──▶ PortalStore (canonical, deduped) ──▶ BlueMapBridge ──▶ BlueMap marker set
                              │                                                   ▲
                              └──▶ portals.json (persist)      BlueMapAPI.onEnable ┘ (rebuild on (re)enable)
```

- **`PortalStore`** — the single source of truth. A `ConcurrentHashMap<markerId, Portal>` with `synchronized` mutators. Dedup is by **bounding-box overlap** (plus a small centroid epsilon), so one physical portal yields exactly one entry no matter how many detection sources hit it. Persists/loads a list of plain DTOs via gson (atomic temp-file-then-move write).
- **`Portal`** — immutable; carries both a centroid (used for the marker position and the stable `markerId()`) **and** the frame's integer bounding box. `contains()` drives precise break-removal; `overlaps()` drives dedup. Pure domain object — no Bukkit/BlueMap imports.
- **`PortalClustering`** — collapses the many per-block POI/event hits of one frame into a single `Cluster` (centroid + min/max) via flood fill. `overlaps()` is intentionally **inclusive** (`<=`/`>=`) because portals are 1 block thick (min==max on the thickness axis) — a strict comparison would break same-portal dedup.
- **`PoiSweeper`** — discovery via the **Paper POI API** (`World.locateAllPoiInRange`). Used for the upfront sweep, per-chunk queries, and the full-height `sweepColumn` (manual coordinate sweeps).
- **`PortalListener`** — the four detection/removal triggers (see below), plus a `WorldLoadEvent` handler that records a world's dimension for linking.
- **`BlueMapBridge`** — `Consumer<BlueMapAPI>`; rebuilds the toggleable marker set from the store and does live add/remove. Marker appearance is mutable (`updateMarkerConfig` + `refresh()`) so `/bmportals reload` re-applies it live. Builds the popup HTML, including the optional predicted-link section.
- **`PortalLinker`** — pure (no Bukkit) Overworld↔Nether 8:1 pairing: predicts a portal's counterpart coords and finds the nearest known portal there. Used by `BlueMapBridge` **off-main**, so it takes a caller-supplied `worldName → Dimension` map instead of touching Bukkit.
- **`PortalsCommand`** — the `/bmportals` admin command (`reload`/`sweep`/`stats`/`purge`) + tab completer; gated by `bmportals.admin`.
- **`UpdateChecker`** / **`VersionCompare`** — off-main GitHub-Releases update notice; the pure version comparison is split into `VersionCompare` so it's unit-testable without paper-api on the classpath.
- **`NetherPortalMarkersPlugin`** — wiring, config (+ hot `reloadConfigAndApply`), the delayed upfront sweep, coalesced async saves, the per-world dimension map, bStats `Metrics`, and the update checker.
- **`Log`** — INFO/DEBUG split (see Logging); `debug` is mutable for reload.

### Detection model (how portals get found)

1. `PortalCreateEvent` (FIRE / NETHER_PAIR) — new portals, immediately.
2. Upfront `PoiSweeper.sweep` around spawn + players on enable — existing portals within `discovery.sweep-radius`.
3. Per-chunk POI query on `ChunkLoadEvent` — roaming coverage; each loaded chunk queried once, tracked in a per-world packed-`long` set that's pruned on `ChunkUnloadEvent`.
4. `BlockBreakEvent` on a `NETHER_PORTAL` block — removal via `PortalStore.removeContaining` (matches the broken block against frame boxes).

Detection is **Paper 26.1+ only** (the POI API has no Spigot/older-Paper fallback) and POI-based, so pre-1.14 unprocessed regions may be missed.

### Two things that bite if you forget them

- **BlueMap markers are NOT persistent.** They must be re-registered every time `BlueMapAPI.onEnable(consumer)` fires (it can fire repeatedly across BlueMap reloads). `BlueMapBridge.accept` is the idempotent rebuild; it uses `retainAll` + `put` (never a full `clear`) to avoid a window where a concurrent live-add gets wiped. Always `unregisterListener` on plugin disable.
- **Threading:** Bukkit event handlers run on the **main thread**, but the `BlueMapAPI.onEnable` consumer (`BlueMapBridge.accept`) runs **off-main**. Hence `PortalStore` mutators are `synchronized`, and `accept()` resolves worlds via the thread-safe **`resolveWorldByName`** (BlueMap-side lookup) — the `Bukkit.getWorld(UUID)` fallback in `resolveWorld` is main-thread-only and used solely by `addPortal`/`removePortal`. Keep new off-main code on the name-based path.

## Paper / BlueMap API specifics (verified, easy to get wrong)

- Use `PoiTypes.NETHER_PORTAL` (a ready `PoiType`) — **not** `PoiTypeKeys.NETHER_PORTAL` (a `TypedKey`).
- `locateAllPoiInRange` returns a `List<PoiSearchResult>`; results use record-style accessors `location()` / `poiType()`. Occupancy is `PoiType.Occupancy.ANY` (an interface constant, not an enum).
- BlueMap `POIMarker` position is a flowpowered `com.flowpowered.math.vector.Vector3d`, distinct from Bukkit `Location` — convert manually. `POIMarker` has no `color()`.
- `map.getMarkerSets()` and `MarkerSet.getMarkers()` are live/concurrent maps; mutating them changes the web map. Use `computeIfAbsent` for the marker set. `MarkerSet` has live setters (`setLabel`/`setToggleable`/`setDefaultHidden`) used by `BlueMapBridge.refresh()`.
- **BlueMap deep-link URL hash** (verified against `BlueMapApp.js` `updatePageAddress`/`loadPageAddress`): exactly **10** colon-separated components — `#<mapId>:<x>:<y>:<z>:<distance>:<rotation>:<angle>:<tilt>:<ortho>:<state>`. The parser rejects any other count, so all 10 must be present; the linker emits `#<mapId>:<x>:<y>:<z>:1000:0:0:0:0:perspective`. The `mapId` comes from `BlueMapMap.getId()` (a world can have several maps; we take the first).

## Logging

`Log` wraps the plugin logger. `info()` always reaches the console; `debug()` only emits when `logging.debug: true` in config, printed at INFO level with a `[DEBUG]` prefix (Minecraft consoles suppress `Level.FINE`, so don't use `logger.fine` for diagnostics). Keep operational milestones at `info`, high-frequency diagnostics at `debug`.

## Conventions

- Persisted JSON is versioned by shape, not a version field: new optional fields are boxed (`Integer`) so absent values deserialize to `null` and a migration path in `PortalDto.toPortal()` handles older files. Preserve backward-compatible loading when changing the schema.
- Reverse-DNS namespace root is `dev.aldis` (the earlier `email.aldis` was wrong and was renamed in v0.2).
- The README "Roadmap" lists deliberately deferred features (background/periodic sweeping → v0.4, per-world filtering, marker tuning) — check it before assuming something is missing by accident. Admin commands, portal linking, bStats + update checker, the JUnit suite, and CI all shipped in v0.3.
