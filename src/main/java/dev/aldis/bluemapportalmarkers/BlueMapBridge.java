package dev.aldis.bluemapportalmarkers;

import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapMap;
import de.bluecolored.bluemap.api.BlueMapWorld;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.markers.POIMarker;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Bridges the {@link PortalStore} to BlueMap by maintaining a toggleable marker
 * set of POI markers, one per portal.
 *
 * <p>Registered via {@link BlueMapAPI#onEnable(Consumer)}; {@link #accept} runs
 * a full idempotent rebuild whenever BlueMap (re)enables. {@link #addPortal} and
 * {@link #removePortal} perform live updates while BlueMap is running and are
 * no-ops otherwise (the next {@link #accept} resyncs from the store).</p>
 */
public final class BlueMapBridge implements Consumer<BlueMapAPI> {

    public static final String MARKER_SET_ID = "nether_portals";

    private final PortalStore store;
    private final Log log;
    private final String markerSetLabel;
    private final boolean defaultHidden;
    private final String iconAddress;
    private final int anchorX;
    private final int anchorY;

    public BlueMapBridge(PortalStore store,
                         Log log,
                         String markerSetLabel,
                         boolean defaultHidden,
                         String iconAddress,
                         int anchorX,
                         int anchorY) {
        this.store = store;
        this.log = log;
        this.markerSetLabel = markerSetLabel;
        this.defaultHidden = defaultHidden;
        this.iconAddress = iconAddress == null ? "" : iconAddress;
        this.anchorX = anchorX;
        this.anchorY = anchorY;
    }

    /**
     * Full, idempotent rebuild of the marker set on every map from the store.
     * Performs a no-gap rebuild: stale markers are dropped via retainAll and
     * live markers are put (idempotent), rather than clearing then repopulating.
     * A full clear() would open a window in which a concurrent live add
     * (addPortal off a different thread) could be wiped before repopulation.
     */
    @Override
    public void accept(BlueMapAPI api) {
        // Single snapshot so the live-id set and the markers we add agree.
        Collection<Portal> snapshot = store.all();

        Set<String> liveIds = new HashSet<>();
        for (Portal portal : snapshot) {
            liveIds.add(portal.markerId());
        }

        // Drop stale markers on every map WITHOUT a full clear (no wipe window).
        for (BlueMapMap map : api.getMaps()) {
            MarkerSet set = getOrCreateMarkerSet(map);
            set.getMarkers().keySet().retainAll(liveIds);
        }

        // Add/replace each portal's marker across every map of its world (put is
        // idempotent, so a portal whose world resolves to multiple maps is fine).
        for (Portal portal : snapshot) {
            // accept() runs off the main thread — use the thread-safe name lookup only.
            BlueMapWorld bmWorld = resolveWorldByName(api, portal);
            if (bmWorld == null) {
                continue;
            }
            POIMarker marker = buildMarker(portal);
            for (BlueMapMap map : bmWorld.getMaps()) {
                getOrCreateMarkerSet(map).getMarkers().put(portal.markerId(), marker);
            }
        }
        log.debug("BlueMap marker rebuild complete (" + snapshot.size() + " portal(s)).");
    }

    /**
     * Live-add a marker for {@code p} to every map of its world. No-op if
     * BlueMap is not currently enabled.
     */
    public void addPortal(Portal p) {
        Optional<BlueMapAPI> instance = BlueMapAPI.getInstance();
        if (instance.isEmpty()) {
            return;
        }
        BlueMapAPI api = instance.get();
        BlueMapWorld bmWorld = resolveWorld(api, p);
        if (bmWorld == null) {
            return;
        }
        POIMarker marker = buildMarker(p);
        for (BlueMapMap map : bmWorld.getMaps()) {
            getOrCreateMarkerSet(map).getMarkers().put(p.markerId(), marker);
        }
    }

    /**
     * Live-remove the marker for {@code p} from every map of its world. No-op
     * if BlueMap is not currently enabled.
     */
    public void removePortal(Portal p) {
        Optional<BlueMapAPI> instance = BlueMapAPI.getInstance();
        if (instance.isEmpty()) {
            return;
        }
        BlueMapAPI api = instance.get();
        BlueMapWorld bmWorld = resolveWorld(api, p);
        if (bmWorld == null) {
            return;
        }
        for (BlueMapMap map : bmWorld.getMaps()) {
            Map<String, MarkerSet> sets = map.getMarkerSets();
            MarkerSet set = sets.get(MARKER_SET_ID);
            if (set != null) {
                set.getMarkers().remove(p.markerId());
            }
        }
    }

    /** Get the existing marker set for a map, or atomically build and install one. */
    private MarkerSet getOrCreateMarkerSet(BlueMapMap map) {
        // computeIfAbsent so two threads can't both build and overwrite each other.
        return map.getMarkerSets().computeIfAbsent(MARKER_SET_ID, id -> MarkerSet.builder()
                .label(markerSetLabel)
                .toggleable(true)
                .defaultHidden(defaultHidden)
                .build());
    }

    /** Build the POI marker for a portal (icon if configured, else default). */
    private POIMarker buildMarker(Portal p) {
        // Escape user-influenced text (world name, coords) before embedding in HTML.
        String coords = htmlEscape(Math.round(p.x()) + ", " + Math.round(p.y()) + ", " + Math.round(p.z()));
        String detail = "<b>Nether Portal</b><br>" + htmlEscape(p.worldName())
                + " @ " + coords;
        POIMarker.Builder b = POIMarker.builder()
                .label("Nether Portal")
                .position(p.x(), p.y() + 1, p.z())
                .detail(detail);
        if (!iconAddress.isEmpty()) {
            b.icon(iconAddress, anchorX, anchorY);
        } else {
            b.defaultIcon();
        }
        return b.build();
    }

    /**
     * Thread-safe resolution of a portal's {@link BlueMapWorld} by world name.
     * Safe to call from any thread (including {@link #accept}, which runs off the
     * main server thread). Returns {@code null} if BlueMap doesn't know the world.
     */
    private BlueMapWorld resolveWorldByName(BlueMapAPI api, Portal p) {
        String name = p.worldName();
        if (name != null && !name.isEmpty()) {
            return api.getWorld(name).orElse(null);
        }
        return null;
    }

    /**
     * Resolve a portal's {@link BlueMapWorld}, preferring the thread-safe name
     * lookup and falling back to a Bukkit UUID lookup. MUST be called on the main
     * server thread (the Bukkit fallback is main-thread-only), so it's used only
     * by {@link #addPortal}/{@link #removePortal}, never by {@link #accept}.
     */
    private BlueMapWorld resolveWorld(BlueMapAPI api, Portal p) {
        BlueMapWorld byName = resolveWorldByName(api, p);
        if (byName != null) {
            return byName;
        }
        if (p.worldId() != null) {
            World bukkitWorld = Bukkit.getWorld(p.worldId());
            if (bukkitWorld != null) {
                Optional<BlueMapWorld> byWorld = api.getWorld(bukkitWorld);
                if (byWorld.isPresent()) {
                    return byWorld.get();
                }
            }
        }
        return null;
    }

    /** Minimal HTML entity escaping for text embedded in marker detail HTML. */
    private static String htmlEscape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
