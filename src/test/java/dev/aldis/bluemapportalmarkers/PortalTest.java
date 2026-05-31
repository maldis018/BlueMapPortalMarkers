package dev.aldis.bluemapportalmarkers;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortalTest {

    private static final UUID W1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID W2 = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static Portal box(UUID world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        double cx = (minX + maxX) / 2.0;
        double cy = (minY + maxY) / 2.0;
        double cz = (minZ + maxZ) / 2.0;
        return new Portal(world, "world", cx, cy, cz, minX, minY, minZ, maxX, maxY, maxZ);
    }

    @Test
    void constructorNormalizesSwappedBounds() {
        Portal p = new Portal(W1, "world", 5, 5, 5, 10, 10, 10, 0, 0, 0);
        assertEquals(0, p.minX());
        assertEquals(10, p.maxX());
        assertEquals(0, p.minZ());
        assertEquals(10, p.maxZ());
    }

    @Test
    void centroidOnlyConstructorSynthesizesOneBlockBox() {
        Portal p = new Portal(W1, "world", 12.4, 64.6, -8.5);
        assertEquals(12, p.minX());
        assertEquals(12, p.maxX());
        assertEquals(65, p.minY());
        assertEquals(65, p.maxY());
        // -8.5 rounds half-up to -8 via Math.round.
        assertEquals(-8, p.minZ());
        assertEquals(-8, p.maxZ());
    }

    @Test
    void containsIsInclusiveWithHalfBlockTolerance() {
        Portal p = box(W1, 0, 60, 0, 2, 63, 0); // 1-block-thick on Z (min==max==0)
        assertTrue(p.contains(1, 61, 0), "interior point");
        assertTrue(p.contains(0, 60, 0), "corner is inclusive");
        assertTrue(p.contains(2.4, 63.4, 0.4), "within the 0.5 tolerance band");
        assertTrue(p.contains(0, 60, -0.5), "tolerance covers the thin Z axis");
        assertFalse(p.contains(3, 61, 0), "beyond tolerance on X");
        assertFalse(p.contains(1, 61, 1), "beyond tolerance on the thin Z axis");
    }

    @Test
    void overlapsTouchingBoxesIsTrue() {
        Portal a = box(W1, 0, 60, 0, 2, 63, 0);
        Portal touching = box(W1, 2, 60, 0, 4, 63, 0); // shares the X=2 plane
        assertTrue(a.overlaps(touching), "inclusive bounds: touching counts as overlap");
    }

    @Test
    void overlapsSeparateBoxesIsFalse() {
        Portal a = box(W1, 0, 60, 0, 2, 63, 0);
        Portal apart = box(W1, 10, 60, 0, 12, 63, 0);
        assertFalse(a.overlaps(apart));
    }

    @Test
    void overlapsDifferentWorldIsFalse() {
        Portal a = box(W1, 0, 60, 0, 2, 63, 0);
        Portal b = box(W2, 0, 60, 0, 2, 63, 0); // identical box, different world
        assertFalse(a.overlaps(b));
    }

    @Test
    void markerIdUsesRoundedCentroid() {
        Portal p = new Portal(W1, "world_nether", 12.4, 64.6, -8.4);
        assertEquals("portal_world_nether_12_65_-8", p.markerId());
    }

    @Test
    void equalsAndHashCodeIgnoreSubBlockJitter() {
        Portal a = new Portal(W1, "world", 12.1, 64.0, -8.2);
        Portal b = new Portal(W1, "world", 12.4, 63.8, -8.1); // same rounded centroid
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());

        Portal differentWorld = new Portal(W2, "world", 12.1, 64.0, -8.2);
        assertNotEquals(a, differentWorld);

        Portal differentCell = new Portal(W1, "world", 13.6, 64.0, -8.2);
        assertNotEquals(a, differentCell);
    }

    @Test
    void distanceSqIsSquaredEuclidean() {
        Portal p = new Portal(W1, "world", 0, 0, 0);
        assertEquals(0.0, p.distanceSq(0, 0, 0));
        assertEquals(14.0, p.distanceSq(1, 2, 3)); // 1+4+9
    }
}
