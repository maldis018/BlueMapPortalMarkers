# BlueMapPortalMarkers

A [Paper](https://papermc.io/) plugin that adds a **toggleable "Nether Portals" layer** to [BlueMap](https://bluemap.bluecolored.de/). The layer updates automatically as portals are lit, discovered, and broken, and is persisted across restarts.

<!-- TODO: add a screenshot of the Nether Portals layer in the BlueMap web UI -->

## How detection works

Portals reach the map through several complementary mechanisms, all feeding a single deduplicated store:

1. **New portals — `PortalCreateEvent`.** When a frame is lit (`FIRE`) or created by nether-pairing (`NETHER_PAIR`), the portal blocks are clustered into one marker immediately.
2. **Existing portals — upfront POI sweep.** On enable (after a short delay), the plugin queries Paper's Point-of-Interest API (`World.locateAllPoiInRange` with `PoiTypes.NETHER_PORTAL`) around each world's spawn and every online player, within `discovery.sweep-radius`. Minecraft already tracks every nether portal as a POI, and POI data loads lazily from region files **without loading full chunks**, so this is comparatively cheap.
3. **Roaming coverage — per-chunk POI query.** When a chunk loads, its POI data is already in memory, so the plugin runs a cheap POI query scoped to that chunk (spanning the full world height). This incrementally covers wherever players explore beyond the startup radius. Each loaded chunk is queried at most once; the bookkeeping set is pruned on chunk unload.
4. **Removal — `BlockBreakEvent`.** Breaking any `NETHER_PORTAL` block collapses the whole frame in vanilla; the broken block's coordinates are matched against each portal's stored frame bounding box, so the correct marker is removed regardless of frame size.

**Clustering & dedup.** A POI query returns one result per portal *block*, so adjacent blocks are flood-fill clustered into a single portal (centroid + bounding box). Re-detections of the same portal are deduplicated by bounding-box overlap (with a small centroid-distance safety net), so a portal yields exactly one marker no matter how many times or ways it's found.

Each portal is rendered as a clickable **POI marker** at the frame centroid, with an HTML detail popup showing the world and coordinates.

## Requirements

- **Paper 26.1.2+** server (the POI API used here was added in Paper 26.1 — it is not available on Spigot or older Paper)
- **Java 25+** runtime (required by paper-api 26.1.x)
- The **BlueMap** plugin installed and enabled

## Installation

1. Build the jar (see below) or download a release.
2. Drop `BlueMapPortalMarkers-<version>.jar` into your server's `plugins/` folder, alongside BlueMap.
3. Start/restart the server. A `plugins/BlueMapPortalMarkers/config.yml` is created on first run.
4. Open BlueMap and toggle the **Nether Portals** layer in the layer/marker menu.

## Configuration

`plugins/BlueMapPortalMarkers/config.yml`:

| Key | Default | Description |
| --- | --- | --- |
| `markers.label` | `"Nether Portals"` | Label of the toggleable marker set in the BlueMap UI. |
| `markers.default-hidden` | `false` | Whether the layer starts hidden (users toggle it on). |
| `markers.icon` | `""` | Custom icon asset address relative to the BlueMap web root. Empty = BlueMap's default POI icon. |
| `markers.icon-anchor-x` | `25` | Icon anchor X in pixels (only used when a custom icon is set). |
| `markers.icon-anchor-y` | `45` | Icon anchor Y in pixels (only used when a custom icon is set). |
| `discovery.sweep-radius` | `256` | Block radius of the upfront POI sweep around spawn and online players. Larger = more coverage, more work. |
| `discovery.scan-on-chunk-load` | `true` | Also query POIs when a chunk loads (cheap; covers areas players roam into). |
| `storage.file` | `"portals.json"` | File (under the plugin data folder) where discovered portals are persisted. |
| `logging.debug` | `false` | When true, verbose diagnostics are printed to the console with a `[DEBUG]` prefix. Normal events always log at INFO. |

Config is read at startup. Changing it currently requires a server restart (a `/reload`-style command is planned — see Roadmap).

### Logging

- **INFO (always):** plugin enable/disable, the upfront-sweep total, and each individual portal registered or removed.
- **DEBUG (`logging.debug: true`):** high-frequency diagnostics — per-chunk discoveries, marker-set rebuilds, and per-world sweep counts. Emitted at INFO level with a `[DEBUG]` prefix so they appear on the console without lowering the server log level.

## Persistence

Discovered portals are stored in `plugins/BlueMapPortalMarkers/portals.json` as a list of records:

```json
[
  {
    "worldId": "….",
    "worldName": "world_nether",
    "x": 12.0, "y": 65.0, "z": -40.0,
    "minX": 11, "minY": 64, "minZ": -40,
    "maxX": 13, "maxY": 67, "maxZ": -40
  }
]
```

- Writes are **atomic** (written to a temp file, then moved into place) so a crash mid-write can't corrupt the file. Saves are coalesced and run off the main thread.
- BlueMap markers themselves are not persistent and are re-registered from this file every time BlueMap (re)enables.
- **Migration:** files written by v0.1 (centroid only, no `min*`/`max*`) load fine — a 1-block bounding box is synthesized at the centroid.

## Building

Requires a JDK (the build is verified on **JDK 26**) — no separate JDK install is needed beyond that, the Gradle wrapper handles the rest.

```sh
./gradlew build
```

The plugin jar is written to `build/libs/BlueMapPortalMarkers-<version>.jar`.

Notes:
- The wrapper pins **Gradle 9.4.0**, the first Gradle release able to *run* on JDK 26.
- Sources compile with `--release 25` (paper-api 26.1.x requires a Java 25+ runtime).
- `paper-api` and `bluemap-api` are `compileOnly` — they are provided by the server at runtime and never bundled.

## Compatibility & limitations

- **Paper 26.1.2+ only.** Detection is built entirely on the Paper POI API; there is no Spigot / older-Paper fallback.
- **Legacy regions.** The POI sweep only finds portals Minecraft has registered as POIs. For modern (1.14+) worlds that's effectively all of them; portals in never-reprocessed pre-1.14 regions may be missed until those chunks are touched by the game.
- **Bounded sweep.** The startup sweep covers a radius around spawn/players, not the entire world. Roaming coverage via chunk-load queries fills in the rest as players explore.

## Roadmap

Planned for a later release:

- Admin commands (`/bmportals reload`, `sweep [radius]`, `stats`, `purge [world]`) and a permission node.
- Per-world include/exclude filtering.
- Marker tuning: view min/max distance, sorting, and customizable label/detail templates.
