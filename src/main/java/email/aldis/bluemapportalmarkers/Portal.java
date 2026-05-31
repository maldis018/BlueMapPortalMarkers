package email.aldis.bluemapportalmarkers;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable domain model for a single discovered nether portal.
 *
 * <p>The coordinates ({@code x}, {@code y}, {@code z}) are the centroid of the
 * portal frame's portal blocks. Identity (for {@link #equals(Object)} /
 * {@link #hashCode()} and the stable {@link #markerId()}) is based on the world
 * and the rounded coordinates, so tiny floating-point differences in the
 * centroid don't produce distinct portals.</p>
 *
 * <p>This class is intentionally free of Bukkit and BlueMap imports.</p>
 */
public final class Portal {

    private final UUID worldId;
    private final String worldName;
    private final double x;
    private final double y;
    private final double z;

    public Portal(UUID worldId, String worldName, double x, double y, double z) {
        this.worldId = Objects.requireNonNull(worldId, "worldId");
        this.worldName = Objects.requireNonNull(worldName, "worldName");
        this.x = x;
        this.y = y;
        this.z = z;
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

    /**
     * A stable marker id derived from the world name and rounded coordinates.
     * Used both as the key in {@link PortalStore} and as the BlueMap marker id.
     */
    public String markerId() {
        return "portal_" + worldName + "_" + Math.round(x) + "_" + Math.round(y) + "_" + Math.round(z);
    }

    /**
     * Squared distance from this portal to the given point. Only meaningful when
     * comparing portals within the same world (no world check is performed here).
     */
    public double distanceSq(double ox, double oy, double oz) {
        double dx = x - ox;
        double dy = y - oy;
        double dz = z - oz;
        return dx * dx + dy * dy + dz * dz;
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
