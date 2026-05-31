package dev.aldis.bluemapportalmarkers;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Canonical, thread-safe set of discovered portals with bounding-box dedup and
 * Gson-backed persistence.
 *
 * <p>Persistence serializes a {@link List} of plain {@link PortalDto} records
 * rather than the live map type, decoupling the on-disk format from runtime
 * structure. The store has no Bukkit or BlueMap dependencies.</p>
 */
public final class PortalStore {

    /**
     * Centroid jitter tolerance (blocks): two same-world portals whose centroids
     * are within this distance are treated as the same even if their boxes don't
     * quite touch (guards against re-detection rounding). Primary dedup is by
     * bounding-box overlap; this is a small safety net.
     */
    public static final double CENTROID_EPSILON = 1.5;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type DTO_LIST_TYPE = new TypeToken<List<PortalDto>>() {
    }.getType();

    private final Map<String, Portal> portals = new ConcurrentHashMap<>();

    /**
     * Add a portal unless an existing same-world portal already covers it (their
     * bounding boxes overlap, or their centroids are within
     * {@link #CENTROID_EPSILON}).
     *
     * @return {@code true} if the portal was newly stored, {@code false} if it
     *         was treated as a duplicate of an existing one.
     */
    public synchronized boolean add(Portal p) {
        double epsSq = CENTROID_EPSILON * CENTROID_EPSILON;
        for (Portal existing : portals.values()) {
            if (!existing.worldId().equals(p.worldId())) {
                continue;
            }
            if (existing.overlaps(p) || existing.distanceSq(p.x(), p.y(), p.z()) <= epsSq) {
                return false;
            }
        }
        // No same-world overlap/near-duplicate found above, so this is a new portal.
        portals.put(p.markerId(), p);
        return true;
    }

    /**
     * Find and remove the stored portal in the given world whose frame bounding
     * box contains the point (e.g. a broken portal block). If several match, the
     * one whose centroid is nearest the point wins.
     *
     * @return the removed portal, or {@code null} if none matched.
     */
    public synchronized Portal removeContaining(UUID worldId, double x, double y, double z) {
        Portal best = null;
        double bestSq = Double.MAX_VALUE;
        for (Portal existing : portals.values()) {
            if (!existing.worldId().equals(worldId) || !existing.contains(x, y, z)) {
                continue;
            }
            double sq = existing.distanceSq(x, y, z);
            if (sq < bestSq) {
                best = existing;
                bestSq = sq;
            }
        }
        if (best != null) {
            portals.remove(best.markerId());
        }
        return best;
    }

    public Collection<Portal> all() {
        return new ArrayList<>(portals.values());
    }

    /**
     * Remove and return every stored portal in the given world (used by the
     * {@code /bmportals purge <world>} admin command).
     */
    public synchronized List<Portal> removeWorld(UUID worldId) {
        List<Portal> removed = new ArrayList<>();
        portals.values().removeIf(p -> {
            if (p.worldId().equals(worldId)) {
                removed.add(p);
                return true;
            }
            return false;
        });
        return removed;
    }

    /**
     * Remove and return every stored portal (used by {@code /bmportals purge}
     * with no world argument). Caller is responsible for dropping the markers.
     */
    public synchronized List<Portal> clear() {
        List<Portal> removed = new ArrayList<>(portals.values());
        portals.clear();
        return removed;
    }

    public Collection<Portal> inWorld(UUID worldId) {
        List<Portal> out = new ArrayList<>();
        for (Portal p : portals.values()) {
            if (p.worldId().equals(worldId)) {
                out.add(p);
            }
        }
        return out;
    }

    /** Number of portals currently tracked. */
    public int size() {
        return portals.size();
    }

    /**
     * Load portals from {@code file}. A missing file leaves the store empty.
     * IOExceptions are logged (not thrown).
     */
    public void load(File file, Log log) {
        if (file == null || !file.exists()) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            List<PortalDto> dtos = GSON.fromJson(reader, DTO_LIST_TYPE);
            if (dtos == null) {
                return;
            }
            for (PortalDto dto : dtos) {
                Portal p = dto.toPortal();
                if (p != null) {
                    portals.put(p.markerId(), p);
                }
            }
        } catch (IOException | RuntimeException ex) {
            log.warn("Failed to load portals from " + file, ex);
        }
    }

    /**
     * Persist all portals to {@code file} as pretty-printed JSON. The parent
     * directory is created if needed. IOExceptions are logged (not thrown).
     */
    public void save(File file, Log log) {
        if (file == null) {
            return;
        }
        // Snapshot under the lock so a concurrent add/removeContaining can't make
        // the weakly-consistent iterator drop or duplicate an entry. The actual
        // file write happens outside the lock.
        List<PortalDto> dtos;
        synchronized (this) {
            dtos = new ArrayList<>(portals.size());
            for (Portal p : portals.values()) {
                dtos.add(PortalDto.from(p));
            }
        }
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            // Write to a sibling temp file then atomically move it into place so a
            // crash mid-write can never leave a truncated/corrupt portals file.
            Path target = file.toPath();
            Path tmp = target.resolveSibling(file.getName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(dtos, DTO_LIST_TYPE, writer);
            }
            try {
                Files.move(tmp, target,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException ex) {
            log.warn("Failed to save portals to " + file, ex);
        }
    }

    /**
     * Plain serializable view of a {@link Portal} (the on-disk representation).
     *
     * <p>The bound fields are boxed {@link Integer} so that a v0.1 file (which
     * had only the centroid) deserializes them as {@code null}, letting
     * {@link #toPortal()} migrate by synthesizing a 1-block box at the centroid.</p>
     */
    private record PortalDto(String worldId, String worldName, double x, double y, double z,
                             Integer minX, Integer minY, Integer minZ,
                             Integer maxX, Integer maxY, Integer maxZ) {

        static PortalDto from(Portal p) {
            return new PortalDto(p.worldId().toString(), p.worldName(), p.x(), p.y(), p.z(),
                    p.minX(), p.minY(), p.minZ(), p.maxX(), p.maxY(), p.maxZ());
        }

        Portal toPortal() {
            if (worldId == null || worldName == null) {
                return null;
            }
            try {
                UUID uid = UUID.fromString(worldId);
                if (minX == null || minY == null || minZ == null
                        || maxX == null || maxY == null || maxZ == null) {
                    // v0.1 data: no bounds — synthesize a 1-block box at the centroid.
                    // Note: this 1-block box will miss peripheral block-break events
                    // until the next full chunk sweep replaces it with real bounds.
                    return new Portal(uid, worldName, x, y, z);
                }
                return new Portal(uid, worldName, x, y, z, minX, minY, minZ, maxX, maxY, maxZ);
            } catch (IllegalArgumentException ex) {
                return null;
            }
        }
    }
}
