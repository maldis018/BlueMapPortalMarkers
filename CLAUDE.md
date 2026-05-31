# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A Paper (Minecraft server) plugin that adds a toggleable "Nether Portals" marker layer to [BlueMap](https://bluemap.bluecolored.de/), kept in sync as portals are lit, discovered, and broken, and persisted across restarts. Single-module Gradle project; package root `dev.aldis.bluemapportalmarkers`.

## Build & verify

```sh
./gradlew build          # compiles + jars → build/libs/BlueMapPortalMarkers-<version>.jar
```

- **There is no test suite** and no test framework wired in — `./gradlew test` is a no-op. Don't claim tests pass; there are none to run.
- Verification of data-layer logic (e.g. the `portals.json` migration) has been done with throwaway **`jshell`** scripts run against `build/classes/java/main` plus the gson jar from `~/.gradle/caches`. That's the established pattern for exercising pure logic without a live server; full behavior requires a real Paper 26.1 + BlueMap server (not available in this environment).

## Toolchain constraints (these are load-bearing — do not "simplify" them)

- **Sources compile with `--release 25`**, not a Java toolchain. `paper-api` 26.1.x publishes Gradle metadata requiring a **JVM runtime of 25+**, so a Java 21 target fails dependency resolution. `build.gradle.kts` deliberately uses `tasks.withType<JavaCompile> { options.release.set(25) }` and **no `java.toolchain` block** (the build host only has JDK 26; a toolchain would try to provision a separate JDK).
- **The Gradle wrapper is pinned to 9.4.0** because that is the first Gradle release able to *run* on JDK 26. Don't downgrade it.
- Both dependencies are **`compileOnly`** (`io.papermc.paper:paper-api`, `de.bluecolored:bluemap-api`) — the server provides them at runtime; never bundle/shade them.

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
- **`PoiSweeper`** — discovery via the **Paper POI API** (`World.locateAllPoiInRange`). Used for the upfront sweep and per-chunk queries.
- **`PortalListener`** — the four detection/removal triggers (see below).
- **`BlueMapBridge`** — `Consumer<BlueMapAPI>`; rebuilds the toggleable marker set from the store and does live add/remove.
- **`NetherPortalMarkersPlugin`** — wiring, config, the delayed upfront sweep, and coalesced async saves.
- **`Log`** — INFO/DEBUG split (see Logging).

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
- `map.getMarkerSets()` and `MarkerSet.getMarkers()` are live/concurrent maps; mutating them changes the web map. Use `computeIfAbsent` for the marker set.

## Logging

`Log` wraps the plugin logger. `info()` always reaches the console; `debug()` only emits when `logging.debug: true` in config, printed at INFO level with a `[DEBUG]` prefix (Minecraft consoles suppress `Level.FINE`, so don't use `logger.fine` for diagnostics). Keep operational milestones at `info`, high-frequency diagnostics at `debug`.

## Conventions

- Persisted JSON is versioned by shape, not a version field: new optional fields are boxed (`Integer`) so absent values deserialize to `null` and a migration path in `PortalDto.toPortal()` handles older files. Preserve backward-compatible loading when changing the schema.
- Reverse-DNS namespace root is `dev.aldis` (the earlier `email.aldis` was wrong and was renamed in v0.2).
- The README "Roadmap" lists deliberately deferred features (admin commands, per-world filtering, marker tuning) — check it before assuming something is missing by accident.
