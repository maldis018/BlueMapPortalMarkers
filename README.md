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

Each portal is rendered as a clickable **POI marker** at the frame centroid, with an HTML detail popup showing the world and coordinates. When **portal linking** is enabled, the popup also shows the predicted Overworld↔Nether counterpart (vanilla 8:1 rule) and, when a portal is already known near that spot, a deep-link that flies the BlueMap viewer to it. Making that deep-link *clickable* needs a one-time BlueMap web-app tweak — see [Enabling clickable deep-links](#enabling-clickable-deep-links-bluemap-web-setup).

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
| `linking.enabled` | `true` | Add a "predicted link" section (and deep-link) to each portal popup, pairing it with its counterpart in the other dimension. |
| `linking.search-tolerance` | `128` | Horizontal radius (blocks) around the predicted target within which a known portal counts as the link. |
| `storage.file` | `"portals.json"` | File (under the plugin data folder) where discovered portals are persisted. |
| `logging.debug` | `false` | When true, verbose diagnostics are printed to the console with a `[DEBUG]` prefix. Normal events always log at INFO. |
| `metrics.enabled` | `true` | Anonymous aggregate usage statistics via [bStats](https://bstats.org/). Opt out here or server-wide in `plugins/bStats/config.yml`. |
| `update-check.enabled` | `true` | On enable, check GitHub Releases for a newer version and log one INFO line if found (off-main, fails silently when offline). |

Most settings can be re-applied at runtime with `/bmportals reload` (see Commands). `storage.file` is the exception — changing it still needs a restart.

## Enabling clickable deep-links (BlueMap web setup)

> Only needed if `linking.enabled` is on **and** you want the **"Go to linked portal"** deep-link in popups to be clickable. The rest of the plugin (markers, popups, predicted-link text) works without this.

BlueMap doesn't make links inside marker-detail HTML usable out of the box — two things get in the way, so a plain link in a popup is unclickable:

1. The popup's container inherits `pointer-events: none` from BlueMap's renderer, so the link never receives clicks.
2. Even with that fixed, BlueMap's map controls cancel the link's default navigation, and the popup's "click-away" listener throws after the deep-link switches maps.

This repo ships two small web-app addons that fix both ([`webapp/my-custom-style.css`](webapp/my-custom-style.css) and [`webapp/my-custom-script.js`](webapp/my-custom-script.js)). To install:

1. **Copy both files** into your BlueMap **webroot** (the `webroot` set in `plugins/BlueMap/webapp.conf`, default `bluemap/web/`):
   ```
   bluemap/web/my-custom-style.css
   bluemap/web/my-custom-script.js
   ```
2. **Register them** in `plugins/BlueMap/webapp.conf`. The `?v=1` is a cache-buster (see the note below):
   ```
   scripts: [
     "my-custom-script.js?v=1"
   ]

   styles: [
     "my-custom-style.css?v=1"
   ]
   ```
3. **Apply:** run `/bluemap reload light` in the server console, then **hard-refresh** the map in your browser (Ctrl/Cmd+Shift+R).

You should now be able to click a portal marker to open its popup, then click **"Go to linked portal"** to fly to the counterpart — while clicking the icon itself still just opens the popup.

> **Caching gotcha.** BlueMap injects these files via `<link>`/`<script>` tags, and most setups (especially behind a CDN/reverse proxy) cache them aggressively. **Every time you edit either file, bump the version query** (`?v=1` → `?v=2`, …) in `webapp.conf` and reload, or purge your proxy/CDN cache — otherwise the old copy keeps being served.

## Commands

All `/bmportals` subcommands require the `bmportals.admin` permission (default: ops).

| Command | Description |
| --- | --- |
| `/bmportals reload` | Re-read `config.yml` and apply marker appearance, sweep radius, chunk-scan toggle, debug, and linking settings live. Reports anything (e.g. `storage.file`) that needs a restart. |
| `/bmportals sweep [radius]` | Force a sweep around every world spawn + online player. |
| `/bmportals sweep me [radius]` / `<player> [radius]` | Sweep around you / a named player. |
| `/bmportals sweep <x> [y] <z>` | Full-height sweep at coordinates in your world (Y is ignored on purpose, so altitude can't make it miss). Player-only. |
| `/bmportals stats` | Show the total and per-world portal counts. |
| `/bmportals purge [world]` | Remove stored portals (one world, or all) and their markers, then persist. Useful after removing portals with WorldEdit (which doesn't fire `BlockBreakEvent`). |

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
./gradlew build       # compiles, runs the JUnit suite, and builds the shaded jar
```

The plugin jar is written to `build/libs/BlueMapPortalMarkers-<version>.jar` (the shaded jar — it bundles bStats).

Notes:
- The wrapper pins **Gradle 9.4.0**, the first Gradle release able to *run* on JDK 26.
- Sources compile with `--release 25` (paper-api 26.1.x requires a Java 25+ runtime). CI builds on JDK 25.
- `paper-api` and `bluemap-api` are `compileOnly` — provided by the server at runtime, never bundled. **bStats** is the sole bundled dependency: it's shaded and relocated to `dev.aldis.bluemapportalmarkers.bstats` by `./gradlew shadowJar`.
- A JUnit 5 suite covers the pure domain logic (`Portal`, `PortalClustering`, `PortalStore` incl. migration, `PortalLinker`, `VersionCompare`); run it with `./gradlew test`. GitHub Actions builds + tests on every push/PR and publishes the jar to a Release on `v*` tags.

## Compatibility & limitations

- **Paper 26.1.2+ only.** Detection is built entirely on the Paper POI API; there is no Spigot / older-Paper fallback.
- **Legacy regions.** The POI sweep only finds portals Minecraft has registered as POIs. For modern (1.14+) worlds that's effectively all of them; portals in never-reprocessed pre-1.14 regions may be missed until those chunks are touched by the game.
- **Bounded sweep.** The startup sweep covers a radius around spawn/players, not the entire world. Roaming coverage via chunk-load queries fills in the rest as players explore.

## Roadmap

Shipped in v0.3: admin commands (`/bmportals`), Overworld↔Nether portal linking, bStats metrics + update checker, a JUnit suite, and GitHub Actions CI/releases.

Planned for a later release:

- Background/periodic sweeping with per-tick work budgeting (deferred to v0.4 — needs live performance testing).
- Per-world include/exclude filtering.
- Marker tuning: view min/max distance, sorting, and customizable label/detail templates.

## License

Copyright © 2026 Max Aldis.

BlueMapPortalMarkers is free software: you can redistribute it and/or modify it under the terms of the **GNU General Public License v3.0** as published by the Free Software Foundation. See [LICENSE](LICENSE) for the full text.

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
