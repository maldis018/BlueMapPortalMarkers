package email.aldis.bluemapportalmarkers;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.PortalCreateEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Bukkit event listener that keeps the {@link PortalStore} and BlueMap in sync
 * with the live world: detecting newly created portals, removing broken ones,
 * and opportunistically sweeping freshly loaded chunks.
 *
 * <p>All handlers run on the main server thread, satisfying the world-access
 * requirement of {@link PoiSweeper}.</p>
 */
public final class PortalListener implements Listener {

    private final NetherPortalMarkersPlugin plugin;
    private final PortalStore store;
    private final BlueMapBridge bridge;
    private final PoiSweeper sweeper;
    private final boolean scanOnChunkLoad;
    private final int chunkQueryRadius;
    private final Logger logger;

    /**
     * Keys ("worldId:chunkX:chunkZ") of chunks already POI-queried this session.
     * This set grows for the lifetime of the server session (no ChunkUnload
     * cleanup) - acceptable for v0.1, candidate for a packed-long set later.
     */
    private final Set<String> queriedChunks = new HashSet<>();

    public PortalListener(NetherPortalMarkersPlugin plugin,
                          PortalStore store,
                          BlueMapBridge bridge,
                          PoiSweeper sweeper,
                          boolean scanOnChunkLoad,
                          int chunkQueryRadius) {
        this.plugin = plugin;
        this.store = store;
        this.bridge = bridge;
        this.sweeper = sweeper;
        this.scanOnChunkLoad = scanOnChunkLoad;
        this.chunkQueryRadius = chunkQueryRadius;
        this.logger = plugin.getLogger();
    }

    /**
     * Register a newly lit / paired portal. Only acts on {@code FIRE} and
     * {@code NETHER_PAIR} reasons.
     */
    @EventHandler
    public void onPortalCreate(PortalCreateEvent e) {
        // Only FIRE / NETHER_PAIR reasons are handled here; API-created portals
        // get picked up by the chunk-load sweep instead.
        PortalCreateEvent.CreateReason reason = e.getReason();
        if (reason != PortalCreateEvent.CreateReason.FIRE
                && reason != PortalCreateEvent.CreateReason.NETHER_PAIR) {
            return;
        }

        World world = e.getWorld();
        List<int[]> blocks = new ArrayList<>();
        for (BlockState bs : e.getBlocks()) {
            if (bs.getType() == Material.NETHER_PORTAL) {
                blocks.add(new int[] { bs.getX(), bs.getY(), bs.getZ() });
            }
        }
        if (blocks.isEmpty()) {
            return;
        }

        boolean anyAdded = false;
        for (double[] centroid : PortalClustering.cluster(blocks)) {
            Portal portal = new Portal(
                    world.getUID(),
                    world.getName(),
                    centroid[0],
                    centroid[1],
                    centroid[2]);
            if (store.add(portal)) {
                bridge.addPortal(portal);
                anyAdded = true;
                logger.info("Registered new nether portal: " + portal);
            }
        }
        if (anyAdded) {
            plugin.requestSave();
        }
    }

    /**
     * Remove a portal when one of its portal blocks is broken (breaking any
     * block collapses the whole portal in vanilla).
     */
    @EventHandler
    public void onBlockBreak(BlockBreakEvent e) {
        Block block = e.getBlock();
        if (block.getType() != Material.NETHER_PORTAL) {
            return;
        }
        // The 3.0 removal radius is intentionally smaller than
        // PortalStore.MERGE_DISTANCE; it may not reach the centroid of very large
        // (>~6 block half-extent) portal frames - acceptable for v0.1.
        Portal removed = store.removeNear(
                block.getWorld().getUID(),
                block.getX(),
                block.getY(),
                block.getZ(),
                3.0);
        if (removed != null) {
            bridge.removePortal(removed);
            plugin.requestSave();
            logger.info("Removed broken nether portal: " + removed);
        }
    }

    /**
     * Opportunistically sweep a freshly loaded chunk for portals (cheap: POI
     * data is already in memory). Each chunk is queried at most once per session.
     */
    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        if (!scanOnChunkLoad) {
            return;
        }
        World world = e.getChunk().getWorld();
        String key = world.getUID() + ":" + e.getChunk().getX() + ":" + e.getChunk().getZ();
        if (!queriedChunks.add(key)) {
            return;
        }

        List<Portal> added = sweeper.queryChunk(e.getChunk(), chunkQueryRadius);
        if (!added.isEmpty()) {
            for (Portal portal : added) {
                bridge.addPortal(portal);
            }
            plugin.requestSave();
            logger.info("Discovered " + added.size() + " portal(s) on chunk load in " + world.getName());
        }
    }
}
