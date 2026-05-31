package email.aldis.bluemapportalmarkers;

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
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Canonical, thread-safe set of discovered portals with proximity-based dedup
 * and Gson-backed persistence.
 *
 * <p>Persistence serializes a {@link List} of plain {@link PortalDto} records
 * rather than the live map type, decoupling the on-disk format from runtime
 * structure. The store has no Bukkit or BlueMap dependencies.</p>
 */
public final class PortalStore {

    /** Two portals within this many blocks (same world) are considered the same portal. */
    public static final double MERGE_DISTANCE = 5.0;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type DTO_LIST_TYPE = new TypeToken<List<PortalDto>>() {
    }.getType();

    private final Map<String, Portal> portals = new ConcurrentHashMap<>();

    /**
     * Add a portal unless an existing portal in the same world lies within
     * {@link #MERGE_DISTANCE} of it.
     *
     * @return {@code true} if the portal was newly stored, {@code false} if it
     *         was treated as a duplicate of an existing one.
     */
    public synchronized boolean add(Portal p) {
        double mergeSq = MERGE_DISTANCE * MERGE_DISTANCE;
        for (Portal existing : portals.values()) {
            if (existing.worldId().equals(p.worldId())
                    && existing.distanceSq(p.x(), p.y(), p.z()) <= mergeSq) {
                return false;
            }
        }
        // putIfAbsent guards against a race producing the exact same markerId.
        return portals.putIfAbsent(p.markerId(), p) == null;
    }

    /**
     * Find and remove a stored portal in the given world within {@code radius}
     * blocks of the point.
     *
     * @return the removed portal, or {@code null} if none matched.
     */
    public synchronized Portal removeNear(UUID worldId, double x, double y, double z, double radius) {
        double radiusSq = radius * radius;
        Portal best = null;
        double bestSq = Double.MAX_VALUE;
        for (Portal existing : portals.values()) {
            if (!existing.worldId().equals(worldId)) {
                continue;
            }
            double sq = existing.distanceSq(x, y, z);
            if (sq <= radiusSq && sq < bestSq) {
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

    public Collection<Portal> inWorld(UUID worldId) {
        List<Portal> out = new ArrayList<>();
        for (Portal p : portals.values()) {
            if (p.worldId().equals(worldId)) {
                out.add(p);
            }
        }
        return out;
    }

    /**
     * Load portals from {@code file}. A missing file leaves the store empty.
     * IOExceptions are logged (not thrown).
     */
    public void load(File file, Logger logger) {
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
            logger.log(Level.WARNING, "Failed to load portals from " + file, ex);
        }
    }

    /**
     * Persist all portals to {@code file} as pretty-printed JSON. The parent
     * directory is created if needed. IOExceptions are logged (not thrown).
     */
    public void save(File file, Logger logger) {
        if (file == null) {
            return;
        }
        List<PortalDto> dtos = new ArrayList<>();
        for (Portal p : portals.values()) {
            dtos.add(PortalDto.from(p));
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
            logger.log(Level.WARNING, "Failed to save portals to " + file, ex);
        }
    }

    /**
     * Plain serializable view of a {@link Portal} (the on-disk representation).
     */
    private record PortalDto(String worldId, String worldName, double x, double y, double z) {

        static PortalDto from(Portal p) {
            return new PortalDto(p.worldId().toString(), p.worldName(), p.x(), p.y(), p.z());
        }

        Portal toPortal() {
            if (worldId == null || worldName == null) {
                return null;
            }
            try {
                return new Portal(UUID.fromString(worldId), worldName, x, y, z);
            } catch (IllegalArgumentException ex) {
                return null;
            }
        }
    }
}
