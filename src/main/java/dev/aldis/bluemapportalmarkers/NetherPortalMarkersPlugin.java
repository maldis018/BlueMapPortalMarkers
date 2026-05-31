package dev.aldis.bluemapportalmarkers;

import de.bluecolored.bluemap.api.BlueMapAPI;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Plugin entry point. Wires the {@link PortalStore}, {@link BlueMapBridge},
 * {@link PoiSweeper}, and {@link PortalListener} together, performs an upfront
 * POI sweep, and handles persistence on save requests and shutdown.
 */
public final class NetherPortalMarkersPlugin extends JavaPlugin {

    /** bStats plugin id (registered at bstats.org). */
    private static final int BSTATS_PLUGIN_ID = 31718;
    /** GitHub repository polled by the update checker. */
    private static final String GITHUB_REPO = "maldis018/BlueMapPortalMarkers";

    private PortalStore store;
    private BlueMapBridge bridge;
    private PoiSweeper sweeper;
    private PortalListener listener;
    private Log log;
    private String storageFileName;
    private int sweepRadius;
    private volatile boolean savePending;
    private final Map<String, PortalLinker.Dimension> dimensions = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        FileConfiguration config = getConfig();

        // --- Config (defaults match config.yml) ---
        String markerLabel = config.getString("markers.label", "Nether Portals");
        boolean defaultHidden = config.getBoolean("markers.default-hidden", false);
        String icon = config.getString("markers.icon", "");
        int anchorX = config.getInt("markers.icon-anchor-x", 25);
        int anchorY = config.getInt("markers.icon-anchor-y", 45);
        this.sweepRadius = config.getInt("discovery.sweep-radius", 256);
        boolean scanOnChunkLoad = config.getBoolean("discovery.scan-on-chunk-load", true);
        this.storageFileName = config.getString("storage.file", "portals.json");
        boolean debug = config.getBoolean("logging.debug", false);
        boolean linkingEnabled = config.getBoolean("linking.enabled", true);
        double linkTolerance = config.getDouble("linking.search-tolerance", 128.0);

        this.log = new Log(getLogger(), debug);

        // --- Core components ---
        this.store = new PortalStore();
        store.load(storageFile(), log);

        // Per-world dimension map for portal linking, populated on the main thread
        // and read off-main by the bridge — hence a thread-safe map.
        refreshDimensions();
        PortalLinker linker = linkingEnabled ? new PortalLinker(linkTolerance) : null;

        this.bridge = new BlueMapBridge(store, log, markerLabel, defaultHidden, icon, anchorX, anchorY,
                linker, dimensions);
        this.sweeper = new PoiSweeper(log, store);

        // --- BlueMap registration (consumer reference kept in 'bridge' field) ---
        BlueMapAPI.onEnable(bridge);

        // --- Events ---
        this.listener = new PortalListener(this, store, bridge, sweeper,
                scanOnChunkLoad, PoiSweeper.CHUNK_QUERY_RADIUS, log);
        getServer().getPluginManager().registerEvents(listener, this);

        // --- Commands ---
        PortalsCommand commands = new PortalsCommand(this);
        if (getCommand("bmportals") != null) {
            getCommand("bmportals").setExecutor(commands);
            getCommand("bmportals").setTabCompleter(commands);
        }

        // --- Metrics + update notice ---
        if (config.getBoolean("metrics.enabled", true)) {
            Metrics metrics = new Metrics(this, BSTATS_PLUGIN_ID);
            metrics.addCustomChart(new SimplePie("tracked_portals", () -> portalCountBucket(store.size())));
        }
        if (config.getBoolean("update-check.enabled", true)) {
            new UpdateChecker(this, log, GITHUB_REPO, getPluginMeta().getVersion()).checkAsync();
        }

        // --- Upfront sweep (2s delay to let worlds/players settle) ---
        getServer().getScheduler().runTaskLater(this, this::initialSweep, 40L);

        log.info("BlueMapPortalMarkers enabled (loaded " + store.size() + " stored portal(s)"
                + (debug ? ", debug logging on" : "") + ").");
    }

    @Override
    public void onDisable() {
        if (bridge != null) {
            BlueMapAPI.unregisterListener(bridge);
        }
        if (store != null && log != null) {
            store.save(storageFile(), log);
        }
        if (log != null) {
            log.info("BlueMapPortalMarkers disabled.");
        }
    }

    /** Upfront sweep on enable: the default sweep at the configured radius. */
    private void initialSweep() {
        try {
            int added = defaultSweep(sweepRadius).size();
            log.info("Upfront POI sweep added " + added + " new portal(s).");
        } catch (RuntimeException ex) {
            log.warn("Upfront POI sweep failed", ex);
        }
    }

    /**
     * Sweep around each world's spawn and around every online player at
     * {@code radius}, live-adding any new portals and persisting. Must run on the
     * main thread. Returns the newly added portals.
     */
    public List<Portal> defaultSweep(int radius) {
        List<Portal> newPortals = new ArrayList<>();
        for (World world : getServer().getWorlds()) {
            newPortals.addAll(sweeper.sweep(world, world.getSpawnLocation(), radius));
        }
        for (Player player : getServer().getOnlinePlayers()) {
            Location loc = player.getLocation();
            World world = loc.getWorld();
            if (world != null) {
                newPortals.addAll(sweeper.sweep(world, loc, radius));
            }
        }
        addAndSave(newPortals);
        return newPortals;
    }

    /** Sphere sweep around a concrete location (used by the {@code me}/{@code <player>} forms). */
    public List<Portal> sweepAt(World world, Location center, int radius) {
        List<Portal> added = sweeper.sweep(world, center, radius);
        addAndSave(added);
        return added;
    }

    /** Full-height column sweep at world coords (used by the coordinate form). */
    public List<Portal> sweepColumn(World world, double x, double z, int radius) {
        List<Portal> added = sweeper.sweepColumn(world, x, z, radius);
        addAndSave(added);
        return added;
    }

    private void addAndSave(List<Portal> portals) {
        for (Portal portal : portals) {
            bridge.addPortal(portal);
        }
        if (!portals.isEmpty()) {
            requestSave();
        }
    }

    /**
     * Re-read {@code config.yml} and apply hot-reloadable settings to the live
     * components. Returns a human-readable report of what was (and wasn't)
     * applied, for the {@code /bmportals reload} command. {@code storage.file}
     * changes require a restart and are reported, not applied.
     */
    public List<String> reloadConfigAndApply() {
        reloadConfig();
        FileConfiguration config = getConfig();
        List<String> report = new ArrayList<>();

        String markerLabel = config.getString("markers.label", "Nether Portals");
        boolean defaultHidden = config.getBoolean("markers.default-hidden", false);
        String icon = config.getString("markers.icon", "");
        int anchorX = config.getInt("markers.icon-anchor-x", 25);
        int anchorY = config.getInt("markers.icon-anchor-y", 45);
        bridge.updateMarkerConfig(markerLabel, defaultHidden, icon, anchorX, anchorY);
        report.add("markers.* applied");

        this.sweepRadius = config.getInt("discovery.sweep-radius", 256);
        report.add("discovery.sweep-radius = " + sweepRadius);

        boolean scanOnChunkLoad = config.getBoolean("discovery.scan-on-chunk-load", true);
        listener.setScanOnChunkLoad(scanOnChunkLoad);
        report.add("discovery.scan-on-chunk-load = " + scanOnChunkLoad
                + " (already-loaded chunks are not retroactively scanned)");

        boolean debug = config.getBoolean("logging.debug", false);
        log.setDebug(debug);
        report.add("logging.debug = " + debug);

        boolean linkingEnabled = config.getBoolean("linking.enabled", true);
        double linkTolerance = config.getDouble("linking.search-tolerance", 128.0);
        refreshDimensions();
        bridge.setLinker(linkingEnabled ? new PortalLinker(linkTolerance) : null);
        report.add("linking.enabled = " + linkingEnabled
                + (linkingEnabled ? " (tolerance " + linkTolerance + ")" : ""));

        String newStorageFile = config.getString("storage.file", "portals.json");
        if (!newStorageFile.equals(storageFileName)) {
            report.add("storage.file change to '" + newStorageFile
                    + "' requires a restart (NOT applied)");
        }

        // Rebuild the live marker set once, after all appearance/linking changes
        // are in place, so the popups reflect the new settings immediately.
        bridge.refresh();

        return report;
    }

    /** Recompute the per-world dimension map from the currently loaded worlds (main thread). */
    public void refreshDimensions() {
        dimensions.clear();
        for (World world : getServer().getWorlds()) {
            registerWorldDimension(world);
        }
    }

    /** Record one world's dimension (called on world load and from {@link #refreshDimensions()}). */
    public void registerWorldDimension(World world) {
        dimensions.put(world.getName(), switch (world.getEnvironment()) {
            case NORMAL -> PortalLinker.Dimension.OVERWORLD;
            case NETHER -> PortalLinker.Dimension.NETHER;
            default -> PortalLinker.Dimension.OTHER;
        });
    }

    // --- Accessors for PortalsCommand ---

    public PortalStore store() {
        return store;
    }

    public BlueMapBridge bridge() {
        return bridge;
    }

    public int sweepRadius() {
        return sweepRadius;
    }

    public Log log() {
        return log;
    }

    /**
     * Request an asynchronous, coalesced persist of the store. Disk I/O runs off
     * the calling (main) thread. If a save is already pending, this is a no-op so
     * a burst of requests collapses into a single write.
     */
    public void requestSave() {
        if (store == null || savePending) {
            return;
        }
        savePending = true;
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            savePending = false;
            store.save(storageFile(), log);
        });
    }

    private File storageFile() {
        return new File(getDataFolder(), storageFileName);
    }

    /** Coarse, anonymous bucket of the tracked-portal count for the bStats pie chart. */
    private static String portalCountBucket(int count) {
        if (count == 0) {
            return "0";
        }
        if (count <= 10) {
            return "1-10";
        }
        if (count <= 50) {
            return "11-50";
        }
        if (count <= 200) {
            return "51-200";
        }
        return "200+";
    }
}
