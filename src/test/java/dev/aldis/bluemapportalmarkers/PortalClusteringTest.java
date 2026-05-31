package dev.aldis.bluemapportalmarkers;

import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortalClusteringTest {

    @Test
    void nullAndEmptyYieldNoClusters() {
        assertTrue(PortalClustering.cluster(null).isEmpty());
        assertTrue(PortalClustering.cluster(List.of()).isEmpty());
    }

    @Test
    void singleBlockIsItsOwnCluster() {
        List<PortalClustering.Cluster> clusters =
                PortalClustering.cluster(List.of(new int[] {5, 60, -3}));
        assertEquals(1, clusters.size());
        PortalClustering.Cluster c = clusters.get(0);
        assertArrayEquals(new double[] {5, 60, -3}, c.centroid());
        assertArrayEquals(new int[] {5, 60, -3}, c.min());
        assertArrayEquals(new int[] {5, 60, -3}, c.max());
    }

    @Test
    void orthogonallyAdjacentBlocksJoin() {
        List<PortalClustering.Cluster> clusters = PortalClustering.cluster(List.of(
                new int[] {0, 60, 0},
                new int[] {0, 61, 0}));
        assertEquals(1, clusters.size());
    }

    @Test
    void diagonallyAdjacentBlocksJoin() {
        // 3D diagonal: distance sqrt(3) ~= 1.732, under the 1.8 threshold.
        List<PortalClustering.Cluster> clusters = PortalClustering.cluster(List.of(
                new int[] {0, 60, 0},
                new int[] {1, 61, 1}));
        assertEquals(1, clusters.size());
    }

    @Test
    void blocksBeyondAdjacencyThresholdSplit() {
        // Gap of 2 on X (distance 2 > 1.8) → two separate clusters.
        List<PortalClustering.Cluster> clusters = PortalClustering.cluster(List.of(
                new int[] {0, 60, 0},
                new int[] {2, 60, 0}));
        assertEquals(2, clusters.size());
    }

    @Test
    void twoFramesProduceTwoClustersWithCorrectBoundsAndCentroids() {
        // Frame A: a 2x3 portal at X in [0,1], Y in [60,62], Z=0.
        // Frame B: identical shape far away at X in [100,101].
        List<int[]> positions = List.of(
                new int[] {0, 60, 0}, new int[] {1, 60, 0},
                new int[] {0, 61, 0}, new int[] {1, 61, 0},
                new int[] {0, 62, 0}, new int[] {1, 62, 0},
                new int[] {100, 60, 0}, new int[] {101, 60, 0},
                new int[] {100, 61, 0}, new int[] {101, 61, 0},
                new int[] {100, 62, 0}, new int[] {101, 62, 0});

        List<PortalClustering.Cluster> clusters = PortalClustering.cluster(positions);
        clusters.sort(Comparator.comparingDouble(c -> c.centroid()[0]));
        assertEquals(2, clusters.size());

        PortalClustering.Cluster a = clusters.get(0);
        assertArrayEquals(new int[] {0, 60, 0}, a.min());
        assertArrayEquals(new int[] {1, 62, 0}, a.max());
        assertArrayEquals(new double[] {0.5, 61, 0}, a.centroid());

        PortalClustering.Cluster b = clusters.get(1);
        assertArrayEquals(new int[] {100, 60, 0}, b.min());
        assertArrayEquals(new int[] {101, 62, 0}, b.max());
        assertArrayEquals(new double[] {100.5, 61, 0}, b.centroid());
    }
}
