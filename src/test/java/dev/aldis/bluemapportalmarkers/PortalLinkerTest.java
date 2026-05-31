package dev.aldis.bluemapportalmarkers;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortalLinkerTest {

    private static final UUID OW = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID NE = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    private static final Map<String, PortalLinker.Dimension> DIMS = Map.of(
            "world", PortalLinker.Dimension.OVERWORLD,
            "world_nether", PortalLinker.Dimension.NETHER,
            "world_the_end", PortalLinker.Dimension.OTHER);

    private static Portal overworld(double x, double y, double z) {
        return new Portal(OW, "world", x, y, z);
    }

    private static Portal nether(double x, double y, double z) {
        return new Portal(NE, "world_nether", x, y, z);
    }

    @Test
    void overworldPredictsNetherAtOneEighth() {
        Portal p = overworld(800, 64, -400);
        PortalLinker.Prediction pred = new PortalLinker(128).predict(p, List.of(p), DIMS);
        assertTrue(pred.hasPrediction());
        assertEquals(100.0, pred.x());
        assertEquals(-50.0, pred.z());
        assertEquals(64.0, pred.y(), "Y is carried over unchanged");
        assertNull(pred.counterpart(), "no nether portal known yet");
    }

    @Test
    void netherPredictsOverworldAtEightTimes() {
        Portal p = nether(100, 32, -50);
        PortalLinker.Prediction pred = new PortalLinker(128).predict(p, List.of(p), DIMS);
        assertTrue(pred.hasPrediction());
        assertEquals(800.0, pred.x());
        assertEquals(-400.0, pred.z());
    }

    @Test
    void otherDimensionHasNoPrediction() {
        Portal end = new Portal(UUID.randomUUID(), "world_the_end", 0, 64, 0);
        PortalLinker.Prediction pred = new PortalLinker(128).predict(end, List.of(end), DIMS);
        assertFalse(pred.hasPrediction());
        assertNull(pred.counterpart());
    }

    @Test
    void findsKnownCounterpartWithinTolerance() {
        Portal ow = overworld(800, 64, 0);          // predicts nether (100, 0)
        Portal neNear = nether(110, 30, 5);          // ~11 blocks from prediction
        PortalLinker.Prediction pred = new PortalLinker(128).predict(ow, List.of(ow, neNear), DIMS);
        assertSame(neNear, pred.counterpart());
    }

    @Test
    void ignoresCounterpartBeyondTolerance() {
        Portal ow = overworld(800, 64, 0);          // predicts nether (100, 0)
        Portal neFar = nether(400, 30, 0);           // 300 blocks away, tolerance 128
        PortalLinker.Prediction pred = new PortalLinker(128).predict(ow, List.of(ow, neFar), DIMS);
        assertNull(pred.counterpart());
    }

    @Test
    void picksNearestOfSeveralCandidates() {
        Portal ow = overworld(800, 64, 0);          // predicts nether (100, 0)
        Portal near = nether(105, 30, 0);            // 5 away
        Portal nearer = nether(101, 30, 0);          // 1 away
        PortalLinker.Prediction pred =
                new PortalLinker(128).predict(ow, List.of(ow, near, nearer), DIMS);
        assertSame(nearer, pred.counterpart());
    }

    @Test
    void doesNotMatchAcrossSameDimension() {
        Portal ow = overworld(800, 64, 0);
        Portal otherOverworld = overworld(100, 64, 0); // sits at the predicted nether coords, but is OVERWORLD
        PortalLinker.Prediction pred =
                new PortalLinker(128).predict(ow, List.of(ow, otherOverworld), DIMS);
        assertNull(pred.counterpart(), "counterpart must be in the opposite dimension");
    }
}
