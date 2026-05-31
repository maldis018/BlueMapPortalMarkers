package dev.aldis.bluemapportalmarkers;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable domain model for a single discovered nether portal.
 *
 * <p>The coordinates ({@code x}, {@code y}, {@code z}) are the centroid of the
 * portal frame's portal blocks. The integer {@code min*}/{@code max*} fields are
 * the axis-aligned bounding box of those blocks, used for precise break-removal
 * and overlap-based deduplication (so even large frames map to a single marker
 * and breaking any block of the frame removes the right portal).</p>
 *
 * <p>Identity (for {@link #equals(Object)} / {@link #hashCode()} and the stable
 * {@link #markerId()}) is based on the world and the rounded centroid, so tiny
 * floating-point differences don't produce distinct portals.</p>
 *
 * <p>This class is intentionally free of Bukkit and BlueMap imports.</p>
 */
public final class Portal {

    /** Tolerance (blocks) when testing whether a point lies within the frame box. */
    private static final double CONTAINS_EPSILON = 0.5;

    private final UUID worldId;
    private final String worldName;
    private final double x;
    private final double y;
    private final double z;
    private final int minX;
    private final int minY;
    private final int minZ;
    private final int maxX;
    private final int maxY;
    private final int maxZ;

    public Portal(UUID worldId, String worldName,
                  double x, double y, double z,
                  int minX, int minY, int minZ,
                  int maxX, int maxY, int maxZ) {
        this.worldId = Objects.requireNonNull(worldId, "worldId");
        this.worldName = Objects.requireNonNull(worldName, "worldName");
        this.x = x;
        this.y = y;
        this.z = z;
        this.minX = Math.min(minX, maxX);
        this.minY = Math.min(minY, maxY);
        this.minZ = Math.min(minZ, maxZ);
        this.maxX = Math.max(minX, maxX);
        this.maxY = Math.max(minY, maxY);
        this.maxZ = Math.max(minZ, maxZ);
    }

    /**
     * Convenience for callers/migration that only have a centroid: synthesizes a
     * 1-block bounding box at the rounded centroid.
     */
    public Portal(UUID worldId, String worldName, double x, double y, double z) {
        this(worldId, worldName, x, y, z,
                (int) Math.round(x), (int) Math.round(y), (int) Math.round(z),
                (int) Math.round(x), (int) Math.round(y), (int) Math.round(z));
    }

    public UUID worldId() {
        return worldId;
    }

    public String worldName() {
        return worldName;
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public double z() {
        return z;
    }

    public int minX() {
        return minX;
    }

    public int minY() {
        return minY;
    }

    public int minZ() {
        return minZ;
    }

    public int maxX() {
        return maxX;
    }

    public int maxY() {
        return maxY;
    }

    public int maxZ() {
        return maxZ;
    }

    /**
     * A stable marker id derived from the world name and rounded centroid.
     * Used both as the key in {@link PortalStore} and as the BlueMap marker id.
     */
    public String markerId() {
        return "portal_" + worldName + "_" + Math.round(x) + "_" + Math.round(y) + "_" + Math.round(z);
    }

    /**
     * Squared distance from this portal's centroid to the given point. Only
     * meaningful within the same world (no world check is performed here).
     */
    public double distanceSq(double ox, double oy, double oz) {
        double dx = x - ox;
        double dy = y - oy;
        double dz = z - oz;
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * Whether the given point lies within this portal's frame bounding box
     * (inclusive, with a small tolerance). Does not check the world.
     */
    public boolean contains(double px, double py, double pz) {
        return px >= minX - CONTAINS_EPSILON && px <= maxX + CONTAINS_EPSILON
                && py >= minY - CONTAINS_EPSILON && py <= maxY + CONTAINS_EPSILON
                && pz >= minZ - CONTAINS_EPSILON && pz <= maxZ + CONTAINS_EPSILON;
    }

    /**
     * Whether this portal's bounding box intersects or touches another's. Used
     * for dedup; only meaningful for portals in the same world (the world is
     * checked here for safety).
     */
    public boolean overlaps(Portal other) {
        if (!worldId.equals(other.worldId)) {
            return false;
        }
        return minX <= other.maxX && maxX >= other.minX
                && minY <= other.maxY && maxY >= other.minY
                && minZ <= other.maxZ && maxZ >= other.minZ;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Portal other)) {
            return false;
        }
        return worldId.equals(other.worldId)
                && Math.round(x) == Math.round(other.x)
                && Math.round(y) == Math.round(other.y)
                && Math.round(z) == Math.round(other.z);
    }

    @Override
    public int hashCode() {
        return Objects.hash(worldId, Math.round(x), Math.round(y), Math.round(z));
    }

    @Override
    public String toString() {
        return "Portal{" + worldName + " @ " + Math.round(x) + "," + Math.round(y) + "," + Math.round(z) + '}';
    }
}
