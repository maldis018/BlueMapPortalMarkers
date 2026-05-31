package dev.aldis.bluemapportalmarkers;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortalStoreTest {

    private static final UUID W1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID W2 = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final Log LOG = new Log(Logger.getLogger("portal-store-test"), false);

    private static Portal box(UUID world, String name, int minX, int minY, int minZ,
                              int maxX, int maxY, int maxZ) {
        double cx = (minX + maxX) / 2.0;
        double cy = (minY + maxY) / 2.0;
        double cz = (minZ + maxZ) / 2.0;
        return new Portal(world, name, cx, cy, cz, minX, minY, minZ, maxX, maxY, maxZ);
    }

    @Test
    void addNewPortalReturnsTrue() {
        PortalStore store = new PortalStore();
        assertTrue(store.add(box(W1, "world", 0, 60, 0, 1, 62, 0)));
        assertEquals(1, store.size());
    }

    @Test
    void addOverlappingPortalIsDedupedAway() {
        PortalStore store = new PortalStore();
        store.add(box(W1, "world", 0, 60, 0, 1, 62, 0));
        // A second detection of the same frame whose box overlaps the first.
        assertFalse(store.add(box(W1, "world", 1, 60, 0, 2, 62, 0)));
        assertEquals(1, store.size());
    }

    @Test
    void addNearDuplicateWithinCentroidEpsilonIsDeduped() {
        PortalStore store = new PortalStore();
        store.add(new Portal(W1, "world", 0, 60, 0));
        // Centroid 1 block away (<= CENTROID_EPSILON 1.5) — treated as the same portal.
        assertFalse(store.add(new Portal(W1, "world", 1, 60, 0)));
        assertEquals(1, store.size());
    }

    @Test
    void addDistinctPortalsAreBothKept() {
        PortalStore store = new PortalStore();
        store.add(box(W1, "world", 0, 60, 0, 1, 62, 0));
        assertTrue(store.add(box(W1, "world", 50, 60, 50, 51, 62, 50)));
        assertEquals(2, store.size());
    }

    @Test
    void sameBoxDifferentWorldIsNotDeduped() {
        PortalStore store = new PortalStore();
        store.add(box(W1, "world", 0, 60, 0, 1, 62, 0));
        assertTrue(store.add(box(W2, "world_nether", 0, 60, 0, 1, 62, 0)));
        assertEquals(2, store.size());
    }

    @Test
    void removeContainingMatchesBoxAndReturnsPortal() {
        PortalStore store = new PortalStore();
        store.add(box(W1, "world", 10, 60, 5, 12, 63, 5));
        Portal removed = store.removeContaining(W1, 11, 61, 5);
        assertNotNull(removed);
        assertEquals(0, store.size());
    }

    @Test
    void removeContainingMissReturnsNullAndKeepsStore() {
        PortalStore store = new PortalStore();
        store.add(box(W1, "world", 10, 60, 5, 12, 63, 5));
        assertNull(store.removeContaining(W1, 100, 60, 100));
        assertNull(store.removeContaining(W2, 11, 61, 5), "right point, wrong world");
        assertEquals(1, store.size());
    }

    @Test
    void inWorldFiltersByWorld() {
        PortalStore store = new PortalStore();
        store.add(box(W1, "world", 0, 60, 0, 1, 62, 0));
        store.add(box(W1, "world", 50, 60, 50, 51, 62, 50));
        store.add(box(W2, "world_nether", 0, 60, 0, 1, 62, 0));
        assertEquals(2, store.inWorld(W1).size());
        assertEquals(1, store.inWorld(W2).size());
    }

    @Test
    void saveThenLoadRoundTripsAllPortals(@TempDir Path dir) {
        File file = dir.resolve("portals.json").toFile();
        PortalStore source = new PortalStore();
        source.add(box(W1, "world", 0, 60, 0, 1, 62, 0));
        source.add(box(W2, "world_nether", 10, 30, 10, 11, 33, 10));
        source.save(file, LOG);
        assertTrue(file.exists());

        PortalStore loaded = new PortalStore();
        loaded.load(file, LOG);
        assertEquals(2, loaded.size());

        // markerIds are the stable identity; both must survive the round-trip.
        var ids = loaded.all().stream().map(Portal::markerId).sorted().toList();
        var expected = source.all().stream().map(Portal::markerId).sorted().toList();
        assertEquals(expected, ids);

        // Bounding box must survive too, not just the centroid.
        Portal w1 = loaded.inWorld(W1).iterator().next();
        assertEquals(0, w1.minX());
        assertEquals(1, w1.maxX());
        assertEquals(62, w1.maxY());
    }

    @Test
    void loadMissingFileLeavesStoreEmpty(@TempDir Path dir) {
        PortalStore store = new PortalStore();
        store.load(dir.resolve("does-not-exist.json").toFile(), LOG);
        assertEquals(0, store.size());
    }

    @Test
    void loadV01FileWithoutBoundsSynthesizesOneBlockBox(@TempDir Path dir) throws Exception {
        // v0.1 on-disk shape: only the centroid, no min*/max* fields.
        String v01Json = """
                [
                  {
                    "worldId": "11111111-1111-1111-1111-111111111111",
                    "worldName": "world",
                    "x": 12.4,
                    "y": 64.6,
                    "z": -8.4
                  }
                ]
                """;
        File file = dir.resolve("portals.json").toFile();
        Files.writeString(file.toPath(), v01Json, StandardCharsets.UTF_8);

        PortalStore store = new PortalStore();
        store.load(file, LOG);
        assertEquals(1, store.size());

        Portal p = store.all().iterator().next();
        // Migrated to a 1-block box at the rounded centroid.
        assertEquals(12, p.minX());
        assertEquals(12, p.maxX());
        assertEquals(65, p.minY());
        assertEquals(65, p.maxY());
        assertEquals(-8, p.minZ());
        assertEquals(-8, p.maxZ());
        assertEquals("portal_world_12_65_-8", p.markerId());
    }

    @Test
    void v02FileWithBoundsLoadsBoundsVerbatim(@TempDir Path dir) throws Exception {
        String v02Json = """
                [
                  {
                    "worldId": "11111111-1111-1111-1111-111111111111",
                    "worldName": "world",
                    "x": 0.5, "y": 61.0, "z": 0.0,
                    "minX": 0, "minY": 60, "minZ": 0,
                    "maxX": 1, "maxY": 62, "maxZ": 0
                  }
                ]
                """;
        File file = dir.resolve("portals.json").toFile();
        Files.writeString(file.toPath(), v02Json, StandardCharsets.UTF_8);

        PortalStore store = new PortalStore();
        store.load(file, LOG);
        Collection<Portal> all = store.all();
        assertEquals(1, all.size());
        Portal p = all.iterator().next();
        assertEquals(0, p.minX());
        assertEquals(1, p.maxX());
        assertEquals(60, p.minY());
        assertEquals(62, p.maxY());
    }
}
