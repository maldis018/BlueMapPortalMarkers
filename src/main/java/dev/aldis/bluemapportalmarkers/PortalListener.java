package dev.aldis.bluemapportalmarkers;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.PortalCreateEvent;
import org.bukkit.event.world.WorldLoadEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Bukkit event listener that keeps the {@link PortalStore} and BlueMap in sync
 * with the live world: detecting newly created portals, removing broken ones,
 * and opportunistically sweeping freshly loaded chunks.
 *
 * <p>All handlers run on the main server thread, satisfying the world-access
 * requirement of {@link PoiSweeper} and making {@link #queriedChunks} access
 * single-threaded (no synchronization needed).</p>
 */
public final class PortalListener implements Listener {

    private final NetherPortalMarkersPlugin plugin;
    private final PortalStore store;
    private final BlueMapBridge bridge;
    private final PoiSweeper sweeper;
    private volatile boolean scanOnChunkLoad;
    private volatile WorldFilter worldFilter;
    private final int chunkQueryRadius;
    private final Log log;

    /**
     * Packed chunk keys ({@code (chunkX << 32) ^ (chunkZ & 0xffffffff)}) of
     * chunks already POI-queried, grouped by world. Entries are removed on chunk
     * unload (see {@link #onChunkUnload}), so this stays bounded to currently
     * loaded chunks; an unloaded→reloaded chunk is simply re-queried (cheap).
     */
    private final Map<UUID, Set<Long>> queriedChunks = new HashMap<>();

    public PortalListener(NetherPortalMarkersPlugin plugin,
                          PortalStore store,
                          BlueMapBridge bridge,
                          PoiSweeper sweeper,
                          boolean scanOnChunkLoad,
                          WorldFilter worldFilter,
                          int chunkQueryRadius,
                          Log log) {
        this.plugin = plugin;
        this.store = store;
        this.bridge = bridge;
        this.sweeper = sweeper;
        this.scanOnChunkLoad = scanOnChunkLoad;
        this.worldFilter = worldFilter;
        this.chunkQueryRadius = chunkQueryRadius;
        this.log = log;
    }

    private static long packChunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xffffffffL);
    }

    /**
     * Toggle per-chunk scanning at runtime ({@code /bmportals reload}). Note that
     * turning this on does not retroactively scan chunks already loaded — only
     * chunks loaded after the toggle fire {@link ChunkLoadEvent}.
     */
    public void setScanOnChunkLoad(boolean scanOnChunkLoad) {
        this.scanOnChunkLoad = scanOnChunkLoad;
    }

    /** Replace the world filter at runtime ({@code /bmportals reload}). */
    public void setWorldFilter(WorldFilter worldFilter) {
        this.worldFilter = worldFilter;
    }

    /**
     * Register a newly lit / paired portal. Only acts on {@code FIRE} and
     * {@code NETHER_PAIR} reasons; API-created portals get picked up by the
     * chunk-load sweep instead.
     */
    @EventHandler
    public void onPortalCreate(PortalCreateEvent e) {
        PortalCreateEvent.CreateReason reason = e.getReason();
        if (reason != PortalCreateEvent.CreateReason.FIRE
                && reason != PortalCreateEvent.CreateReason.NETHER_PAIR) {
            return;
        }

        World world = e.getWorld();
        if (!worldFilter.allows(world.getName())) {
            return;
        }
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
        for (PortalClustering.Cluster cluster : PortalClustering.cluster(blocks)) {
            Portal portal = new Portal(
                    world.getUID(),
                    world.getName(),
                    cluster.centroid()[0],
                    cluster.centroid()[1],
                    cluster.centroid()[2],
                    cluster.min()[0], cluster.min()[1], cluster.min()[2],
                    cluster.max()[0], cluster.max()[1], cluster.max()[2]);
            if (store.add(portal)) {
                bridge.addPortal(portal);
                anyAdded = true;
                log.info("Registered new nether portal: " + portal);
            }
        }
        if (anyAdded) {
            plugin.requestSave();
        }
    }

    /**
     * Remove a portal when one of its portal blocks is broken (breaking any
     * block collapses the whole portal in vanilla). The broken block's
     * coordinates are matched against each portal's frame bounding box, so this
     * is precise regardless of frame size.
     */
    @EventHandler
    public void onBlockBreak(BlockBreakEvent e) {
        Block block = e.getBlock();
        if (block.getType() != Material.NETHER_PORTAL) {
            return;
        }
        if (!worldFilter.allows(block.getWorld().getName())) {
            return;
        }
        Portal removed = store.removeContaining(
                block.getWorld().getUID(),
                block.getX(),
                block.getY(),
                block.getZ());
        if (removed != null) {
            bridge.removePortal(removed);
            plugin.requestSave();
            log.info("Removed broken nether portal: " + removed);
        }
    }

    /**
     * Opportunistically sweep a freshly loaded chunk for portals (cheap: POI
     * data is already in memory). Each loaded chunk is queried at most once.
     */
    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        if (!scanOnChunkLoad) {
            return;
        }
        World world = e.getChunk().getWorld();
        if (!worldFilter.allows(world.getName())) {
            return;
        }
        long key = packChunkKey(e.getChunk().getX(), e.getChunk().getZ());
        Set<Long> worldChunks = queriedChunks.computeIfAbsent(world.getUID(), id -> new HashSet<>());
        if (!worldChunks.add(key)) {
            return;
        }

        List<Portal> added = sweeper.queryChunk(e.getChunk(), chunkQueryRadius);
        if (!added.isEmpty()) {
            for (Portal portal : added) {
                bridge.addPortal(portal);
            }
            plugin.requestSave();
            log.debug("Discovered " + added.size() + " portal(s) on chunk load in " + world.getName());
        }
    }

    /**
     * Forget a chunk's queried-marker when it unloads, so the queried set stays
     * bounded to loaded chunks.
     */
    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent e) {
        if (!scanOnChunkLoad) {
            return;
        }
        Set<Long> worldChunks = queriedChunks.get(e.getChunk().getWorld().getUID());
        if (worldChunks == null) {
            return;
        }
        worldChunks.remove(packChunkKey(e.getChunk().getX(), e.getChunk().getZ()));
        if (worldChunks.isEmpty()) {
            queriedChunks.remove(e.getChunk().getWorld().getUID());
        }
    }

    /**
     * Record a newly loaded world's dimension so portal linking can classify its
     * portals (worlds loaded after startup, e.g. via Multiverse, would otherwise
     * be missing from the dimension map until the next reload).
     */
    @EventHandler
    public void onWorldLoad(WorldLoadEvent e) {
        plugin.registerWorldDimension(e.getWorld());
    }
}
