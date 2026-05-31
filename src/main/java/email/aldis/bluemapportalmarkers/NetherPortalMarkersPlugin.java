package email.aldis.bluemapportalmarkers;

import de.bluecolored.bluemap.api.BlueMapAPI;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Plugin entry point. Wires the {@link PortalStore}, {@link BlueMapBridge},
 * {@link PoiSweeper}, and {@link PortalListener} together, performs an upfront
 * POI sweep, and handles persistence on save requests and shutdown.
 */
public final class NetherPortalMarkersPlugin extends JavaPlugin {

    private PortalStore store;
    private BlueMapBridge bridge;
    private PoiSweeper sweeper;
    private String storageFileName;
    private int sweepRadius;
    private volatile boolean savePending;

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

        Logger logger = getLogger();

        // --- Core components ---
        this.store = new PortalStore();
        store.load(storageFile(), logger);

        this.bridge = new BlueMapBridge(store, logger, markerLabel, defaultHidden, icon, anchorX, anchorY);
        this.sweeper = new PoiSweeper(logger, store);

        // --- BlueMap registration (consumer reference kept in 'bridge' field) ---
        BlueMapAPI.onEnable(bridge);

        // --- Events ---
        getServer().getPluginManager().registerEvents(
                new PortalListener(this, store, bridge, sweeper, scanOnChunkLoad, PoiSweeper.CHUNK_QUERY_RADIUS),
                this);

        // --- Upfront sweep (2s delay to let worlds/players settle) ---
        getServer().getScheduler().runTaskLater(this, this::initialSweep, 40L);

        logger.info("BlueMapPortalMarkers enabled (loaded " + store.all().size() + " stored portal(s)).");
    }

    @Override
    public void onDisable() {
        if (bridge != null) {
            BlueMapAPI.unregisterListener(bridge);
        }
        if (store != null) {
            store.save(storageFile(), getLogger());
        }
        getLogger().info("BlueMapPortalMarkers disabled.");
    }

    /** Sweep around each world's spawn and around every online player. */
    private void initialSweep() {
        List<Portal> newPortals = new ArrayList<>();
        try {
            for (World world : getServer().getWorlds()) {
                newPortals.addAll(sweeper.sweep(world, world.getSpawnLocation(), sweepRadius));
            }
            for (Player player : getServer().getOnlinePlayers()) {
                Location loc = player.getLocation();
                World world = loc.getWorld();
                if (world != null) {
                    newPortals.addAll(sweeper.sweep(world, loc, sweepRadius));
                }
            }

            for (Portal portal : newPortals) {
                bridge.addPortal(portal);
            }
            if (!newPortals.isEmpty()) {
                requestSave();
            }
            getLogger().info("Upfront POI sweep added " + newPortals.size() + " new portal(s).");
        } catch (RuntimeException ex) {
            getLogger().log(Level.WARNING, "Upfront POI sweep failed", ex);
        }
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
            store.save(storageFile(), getLogger());
        });
    }

    private File storageFile() {
        return new File(getDataFolder(), storageFileName);
    }
}
