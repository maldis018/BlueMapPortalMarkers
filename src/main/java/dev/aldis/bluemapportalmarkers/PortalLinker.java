package dev.aldis.bluemapportalmarkers;

import java.util.Collection;
import java.util.Map;

/**
 * Computes the predicted Overworld&nbsp;&harr;&nbsp;Nether counterpart of a portal
 * using the vanilla 8:1 coordinate rule, and locates the nearest <em>known</em>
 * portal near that predicted target in the opposite dimension.
 *
 * <p>This is intentionally a pure class with no Bukkit or BlueMap imports: it is
 * invoked from {@link BlueMapBridge#accept} which runs <em>off the main server
 * thread</em>, so it must not touch Bukkit. The per-world {@link Dimension}
 * mapping is supplied by the caller (computed on the main thread).</p>
 *
 * <p>Pairing is a <em>prediction</em>, not ground truth — vanilla links to the
 * nearest existing portal within a search volume, or builds a new one. Callers
 * must present the result as a predicted link, never a guarantee.</p>
 */
public final class PortalLinker {

    /** Vanilla horizontal scale factor between the Overworld and the Nether. */
    private static final double SCALE = 8.0;

    public enum Dimension { OVERWORLD, NETHER, OTHER }

    /**
     * The predicted counterpart of a portal.
     *
     * @param hasPrediction whether a prediction applies (false for non-overworld/
     *                      non-nether source worlds, e.g. The End)
     * @param x predicted counterpart X (world coords in the opposite dimension)
     * @param y predicted counterpart Y (unchanged from the source)
     * @param z predicted counterpart Z
     * @param counterpart the nearest known portal within tolerance, or {@code null}
     */
    public record Prediction(boolean hasPrediction, double x, double y, double z, Portal counterpart) {

        static Prediction none() {
            return new Prediction(false, 0, 0, 0, null);
        }
    }

    private final double tolerance;

    /**
     * @param tolerance horizontal search radius (blocks) around the predicted
     *                  target within which a known portal counts as the link
     */
    public PortalLinker(double tolerance) {
        this.tolerance = tolerance;
    }

    /**
     * Predict {@code p}'s counterpart and find the nearest known portal near it.
     *
     * @param p          the portal to link from
     * @param candidates all known portals (the predicted match is searched here)
     * @param dimensions per-world-name dimension mapping (caller-provided)
     */
    public Prediction predict(Portal p, Collection<Portal> candidates, Map<String, Dimension> dimensions) {
        Dimension from = dimensions.getOrDefault(p.worldName(), Dimension.OTHER);
        Dimension target;
        double px;
        double pz;
        if (from == Dimension.NETHER) {
            target = Dimension.OVERWORLD;
            px = p.x() * SCALE;
            pz = p.z() * SCALE;
        } else if (from == Dimension.OVERWORLD) {
            target = Dimension.NETHER;
            px = p.x() / SCALE;
            pz = p.z() / SCALE;
        } else {
            return Prediction.none();
        }
        double py = p.y();

        Portal best = null;
        double bestSq = Double.MAX_VALUE;
        double tolSq = tolerance * tolerance;
        for (Portal cand : candidates) {
            if (cand.equals(p)) {
                continue;
            }
            if (dimensions.getOrDefault(cand.worldName(), Dimension.OTHER) != target) {
                continue;
            }
            double dx = cand.x() - px;
            double dz = cand.z() - pz;
            double sq = dx * dx + dz * dz; // horizontal distance only (Y differs by design)
            if (sq <= tolSq && sq < bestSq) {
                best = cand;
                bestSq = sq;
            }
        }
        return new Prediction(true, px, py, pz, best);
    }
}
