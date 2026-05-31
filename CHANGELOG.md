# Changelog

All notable changes to this project are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

_Nothing yet — `main` is on `0.4.0-SNAPSHOT`._

## [0.3.0] - 2026-05-31

The first feature release since launch — admin tooling, dimension linking,
metrics, and a real CI/test pipeline.

### Added

- `/bmportals` admin commands (permission `bmportals.admin`, default OP) for
  managing the marker layer live, without a restart or hand-editing
  `portals.json`:
  - `reload` — re-read `config.yml` and apply it on the fly (marker appearance,
    sweep radius, chunk-scan toggle, debug, linking). Settings that still need a
    restart (`storage.file`) are reported.
  - `sweep [radius]` / `sweep me [radius]` / `sweep <player> [radius]` /
    `sweep <x> [y] <z>` — force a portal scan. Coordinate sweeps are
    full-height, so altitude can't cause a miss.
  - `stats` — total and per-world portal counts.
  - `purge [world]` — drop stored portals and their markers for one world or
    all (useful after removing portals with WorldEdit, which doesn't fire a
    break event).
- Overworld ↔ Nether portal linking: each portal popup shows its predicted
  counterpart (vanilla 8:1 rule) and, when a matching portal is already known, a
  clickable BlueMap deep-link to it. Configurable via `linking.enabled` and
  `linking.search-tolerance`.
- Anonymous usage metrics via [bStats](https://bstats.org/) (`metrics.enabled`).
- Startup update notifier that checks GitHub Releases and logs a single line
  when a newer version exists (`update-check.enabled`); runs off-thread and
  fails silently when offline.

### Changed

- Most configuration options are now hot-reloadable with `/bmportals reload`
  instead of requiring a server restart (exception: `storage.file`).
- The build now shades and relocates bStats into the plugin jar via the Shadow
  plugin; `./gradlew shadowJar` (run by `build`) produces the distributable.

### Internal

- Added a JUnit 5 test suite covering the core domain logic, including the
  `portals.json` v0.1→v0.2 migration path.
- Added GitHub Actions CI that builds and tests on every push/PR and publishes
  the jar to a GitHub Release on `v*` tags.

## [0.2.0] - 2026-05-31

### Changed

- Bounding-box portal model (frames map to a single marker; precise
  break-removal regardless of frame size).
- Chunk-set tracking fix and INFO/DEBUG logging split.
- Reverse-DNS namespace corrected to `dev.aldis`.

## [0.1.0]

### Added

- Initial release: a toggleable "Nether Portals" marker layer for BlueMap,
  kept in sync as portals are lit, discovered, and broken, and persisted across
  restarts.

[Unreleased]: https://github.com/maldis018/BlueMapPortalMarkers/compare/v0.3.0...HEAD
[0.3.0]: https://github.com/maldis018/BlueMapPortalMarkers/releases/tag/v0.3.0
[0.2.0]: https://github.com/maldis018/BlueMapPortalMarkers/commit/5229b94
[0.1.0]: https://github.com/maldis018/BlueMapPortalMarkers/commit/6b6c481
