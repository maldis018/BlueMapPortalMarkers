# BlueMapPortalMarkers — Road to 1.0

Status: planning / north-star. Baseline is v0.2 (live-validated); v0.3 is scoped in
`v0.3-roadmap.md`. This document collects everything between v0.3 and a **1.0** release:
the items deliberately skipped for v0.3, plus the completeness, stability, and
distribution work that a "1.0" label should actually stand for.

**What 1.0 means here:** the layer reliably shows *all* common portal types on the map,
the plugin is configurable and localizable, it's been validated at scale, its compatibility
stance is explicit, it's covered by automated tests, and it's published on a plugin platform
with a versioning/changelog commitment. Features alone don't earn 1.0 — stability and
distribution do.

Version numbers below are **suggested milestones, not commitments** — reorder freely.

---

## Carried over from v0.3 (deliberately skipped there)

- **Per-world include/exclude filtering** — `discovery.worlds.mode: blacklist|whitelist` + a
  list; guard the four detection entry points + the startup sweep. Small lift; high value on
  multi-world servers (skip creative/minigame worlds).
- **Marker presentation tuning** — view min/max distance (declutter at zoom), sorting, and a
  configurable label/detail template. All in `BlueMapBridge.buildMarker()`; `POIMarker`
  supports min/max distance directly.
- **Background sweeping** — already penciled for **v0.4** in `v0.3-roadmap.md` (the substance
  is per-tick work-budgeting, which needs live perf testing). Depends on the v0.3 admin
  `sweep` plumbing.

---

## Theme A — Detection completeness

**A1. Portal-type abstraction (enabling refactor).** Today detection is nether-specific:
`Material.NETHER_PORTAL` and `PoiTypes.NETHER_PORTAL` are hardcoded across `PortalListener`,
`PoiSweeper`, and `Portal`. Introduce a `PortalType` abstraction (id, display label, default
icon, **detection strategy**, marker-set/layer) so new types slot in without forking logic.
This is the prerequisite for A2.

**A2. End portals + End gateways — the headline 1.0 feature.** *(explicitly requested)*

> ⚠ **Research spike required first — do not assume the nether approach transfers.**
> The nether layer works because a nether portal is a first-class POI (`PoiTypes.NETHER_PORTAL`)
> and lights via `PortalCreateEvent (FIRE/NETHER_PAIR)`. End portals/gateways are different:
> - End portal blocks are `Material.END_PORTAL` (frame `END_PORTAL_FRAME`), inside
>   stronghold structures; the End exit portal is created when the dragon dies.
> - End gateways are `Material.END_GATEWAY`, generated on dragon defeat / by ender pearls.
> - It is **unlikely** these are exposed as POI types the way nether portals are, and there is
>   **no clean Bukkit "end portal activated / gateway created" event** equivalent to
>   `PortalCreateEvent` for the nether.
> So detection probably needs a **different strategy** — block-scanning on chunk load for
> `END_PORTAL`/`END_GATEWAY`, and/or structure/event hooks — not the POI sweep. The spike must
> verify: (a) whether any POI type covers them, (b) which events (if any) fire, (c) the cheapest
> reliable detection path. This mirrors how Paper/BlueMap API specifics were verified for v0.1/v0.2
> by reading source rather than trusting memory.

Deliverable after the spike: end portal + end gateway detection wired through the A1
abstraction, rendered as their own toggleable layer(s) with distinct icons.

**A3. Startup sweep full-height.** v0.3 makes the *manual* `sweep` full-height; for consistency
and to close the known fixed-Y limitation (code-review obs O5), make the startup sweep
full-height too so portals near bedrock/ceiling within radius aren't missed by altitude.

---

## Theme B — Configuration & UX polish

**B1. Portal naming / annotations.** `/bmportals name <here|coords> "Spawn Hub"`, shown as the
marker label. Persists via a new optional field — exactly the boxed-field / null-migration
pattern v0.2 already established, so old `portals.json` keeps loading.

**B2. Per-type icons & detail.** Distinct icons per dimension/type (nether vs end vs gateway);
falls out of A1 + B-marker-tuning.

**B3. Localization (`messages.yml`).** Externalize command responses and marker text for
translation — standard expectation for a published 1.0 plugin.

---

## Theme C — Stability & scale (the real 1.0 gate)

**C1. Performance / scale hardening.** `PortalStore.add()` currently scans **all** portals on
every insert (O(n) per add → O(n²) across a large sweep) to do overlap dedup. Fine at v0.2
scale; questionable at thousands of portals on a big server. Before claiming 1.0, add per-world
spatial bucketing / a coarse grid index so dedup and `removeContaining` are local, and validate
on a large dataset.

**C2. Folia compatibility decision.** Folia's regionized threading **breaks the single-main-thread
assumption** the whole sync model relies on (`PortalStore` synchronization, main-thread Bukkit
access in commands/listeners). 1.0 must make an explicit choice: support Folia (region-aware
scheduling, revisit thread-safety) or declare it unsupported in `plugin.yml`/docs. Don't ship 1.0
with an ambiguous stance.

**C3. Explicit schema version in `portals.json`.** As the schema grows (bounds, names, types),
graduate from shape/null-based migration to an explicit `version` field with a clear upgrade
path. Keeps backward-compat legible as fields accumulate.

---

## Theme D — Quality & distribution

**D1. Integration tests (MockBukkit).** v0.3 covers the pure classes (`Portal`,
`PortalClustering`, `PortalStore`). 1.0 should add MockBukkit-based tests for the event
listener and BlueMap bridge so detection/removal logic is regression-protected, not just the
domain layer. CI runs the full suite.

**D2. Publish to Modrinth + Hangar.** Builds directly on the v0.3 GitHub Actions release
pipeline — add publish steps on tag. A public 1.0 should be installable from a plugin platform,
not just GitHub Releases. (Update checker from v0.3 can point at whichever is canonical.)

**D3. SemVer + CHANGELOG + compatibility commitment.** Adopt semantic versioning, keep a
`CHANGELOG.md`, and state the supported Paper/Minecraft version range. 1.0 implies a stability
promise — say what it covers.

**D4. Docs.** Expand the README / add a wiki: per-feature docs, screenshots/GIFs of each layer,
config cookbook, troubleshooting.

---

## Suggested milestone sequencing

| Milestone | Theme focus |
|---|---|
| **v0.4** | Config-polish release: background sweeping + per-world filtering (B-carryover) + marker presentation tuning |
| **v0.5** | **Detection expansion** (headline): A1 type abstraction → A2 end portals/gateways *(research spike first)* + A3 full-height startup sweep |
| **v0.6** | UX: B1 naming + B2 per-type icons + B3 localization |
| **v0.7** | Hardening: C1 scale/perf + C2 Folia decision + C3 schema version |
| **v1.0** | D1 integration tests + D2 publish (Modrinth/Hangar) + D3 SemVer/changelog/compat + D4 docs |

---

## 1.0 "definition of done" checklist

- [ ] All common portal types detected and layered: nether ✓ (v0.2), **end portals + end
      gateways** (A2)
- [ ] Per-world filtering, marker tuning, and portal naming shipped
- [ ] Localizable user-facing text
- [ ] Validated at scale (N≈thousands of portals) without tick impact; dedup/removal are local-cost
- [ ] Folia stance explicit (supported, or declared unsupported)
- [ ] `portals.json` has a versioned, documented upgrade path
- [ ] Automated tests cover domain **and** event/bridge layers; CI green
- [ ] Published on Modrinth and/or Hangar from the CI pipeline
- [ ] SemVer adopted, CHANGELOG maintained, supported-version range stated
- [ ] No known stale-marker gaps for the supported detection paths

---

## Open questions / research spikes (resolve before the dependent milestone)

1. **End portal / end gateway detection mechanism** — POI? block scan on chunk load? events?
   The biggest unknown; gates A2 (v0.5). *Spike before planning.*
2. **Folia** — support or explicitly not? Gates C2 (v0.7) and influences any threading work.
3. **BlueMap deep-link URL hash format** — shared with v0.3 Item 2 (portal linking); verify once,
   reuse for per-type navigation.
4. **Modrinth/Hangar publishing** — token/secret management in CI; project metadata.
