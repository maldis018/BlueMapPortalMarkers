package email.aldis.bluemapportalmarkers;

import io.papermc.paper.entity.poi.PoiSearchResult;
import io.papermc.paper.entity.poi.PoiType;
import io.papermc.paper.entity.poi.PoiTypes;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Discovers nether portals using Paper's point-of-interest (POI) API and feeds
 * newly found portals into the {@link PortalStore}.
 *
 * <p>All methods access world data and therefore MUST be invoked on the main
 * server thread (event handlers / scheduler main task).</p>
 */
public final class PoiSweeper {

    /** Default radius for the cheap per-chunk query. */
    public static final int CHUNK_QUERY_RADIUS = 16;

    private final Logger logger;
    private final PortalStore store;

    public PoiSweeper(Logger logger, PortalStore store) {
        this.logger = logger;
        this.store = store;
    }

    /**
     * Sweep for nether portals around {@code center} within {@code radius} and
     * add newly discovered ones to the store.
     *
     * @return the list of portals that were newly added (empty if none).
     */
    public List<Portal> sweep(World world, Location center, int radius) {
        // Paper POI API (PR #12117, Paper 26.1)
        List<PoiSearchResult> results = world.locateAllPoiInRange(
                center,
                pt -> pt.equals(PoiTypes.NETHER_PORTAL),
                radius,
                PoiType.Occupancy.ANY);

        return collectAndStore(world, results);
    }

    /**
     * Convenience that queries POIs around the centre of a loaded chunk. POI
     * data for a loaded chunk is already in memory, so this is cheap.
     *
     * @return the list of portals that were newly added (empty if none).
     */
    public List<Portal> queryChunk(Chunk chunk, int radius) {
        World world = chunk.getWorld();
        // Center the query at the world's vertical midpoint and expand the radius so
        // the query sphere spans the full world height (a fixed Y=64 missed portals
        // near bedrock or the build limit). +8 keeps the chunk's full XZ footprint
        // covered even when the world is shallow.
        int midY = (world.getMinHeight() + world.getMaxHeight()) / 2;
        int effectiveRadius = Math.max(radius, (world.getMaxHeight() - world.getMinHeight()) / 2 + 8);
        Location center = new Location(
                world,
                chunk.getX() * 16 + 8,
                midY,
                chunk.getZ() * 16 + 8);
        // Paper POI API (PR #12117, Paper 26.1)
        List<PoiSearchResult> results = world.locateAllPoiInRange(
                center,
                pt -> pt.equals(PoiTypes.NETHER_PORTAL),
                effectiveRadius,
                PoiType.Occupancy.ANY);

        return collectAndStore(world, results);
    }

    /**
     * Turn POI search results into clustered portal centroids and store any new
     * ones.
     */
    private List<Portal> collectAndStore(World world, List<PoiSearchResult> results) {
        List<Portal> added = new ArrayList<>();
        if (results == null || results.isEmpty()) {
            return added;
        }

        List<int[]> blocks = new ArrayList<>(results.size());
        for (PoiSearchResult result : results) {
            Location loc = result.location();
            blocks.add(new int[] { loc.getBlockX(), loc.getBlockY(), loc.getBlockZ() });
        }

        for (double[] centroid : PortalClustering.cluster(blocks)) {
            Portal portal = new Portal(
                    world.getUID(),
                    world.getName(),
                    centroid[0],
                    centroid[1],
                    centroid[2]);
            if (store.add(portal)) {
                added.add(portal);
            }
        }

        if (!added.isEmpty()) {
            logger.fine("POI sweep discovered " + added.size() + " new portal(s) in " + world.getName());
        }
        return added;
    }
}
