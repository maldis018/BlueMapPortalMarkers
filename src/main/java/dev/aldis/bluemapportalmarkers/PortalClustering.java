package dev.aldis.bluemapportalmarkers;

import java.util.ArrayList;
import java.util.List;

/**
 * Utility to collapse the many per-block coordinates of a portal frame into one
 * marker per physical portal.
 *
 * <p>Block positions are flood-filled into connected groups: two positions join
 * the same cluster when their Euclidean distance is {@code <= ADJACENCY}
 * (covering orthogonal and diagonal adjacency). Each group yields a
 * {@link Cluster} carrying both the centroid and the axis-aligned bounding box
 * of its blocks. Pure Java — operates on primitive coordinates only.</p>
 */
public final class PortalClustering {

    /**
     * Adjacency threshold. {@code sqrt(3) ≈ 1.732} covers full 3D diagonal
     * adjacency; we use 1.8 to be safely inclusive of it.
     */
    public static final double ADJACENCY = 1.8;

    private PortalClustering() {
    }

    /**
     * One clustered portal frame: its centroid ({@code double[]{cx,cy,cz}}) and
     * the integer bounding box of its blocks.
     */
    public record Cluster(double[] centroid, int[] min, int[] max) {
    }

    /**
     * Cluster the given block positions ({@code int[]{x,y,z}}) into connected
     * groups and return one {@link Cluster} per group.
     */
    public static List<Cluster> cluster(List<int[]> positions) {
        List<Cluster> clusters = new ArrayList<>();
        if (positions == null || positions.isEmpty()) {
            return clusters;
        }

        int n = positions.size();
        boolean[] visited = new boolean[n];
        double adjSq = ADJACENCY * ADJACENCY;

        for (int i = 0; i < n; i++) {
            if (visited[i]) {
                continue;
            }
            // Iterative flood fill from i.
            List<Integer> stack = new ArrayList<>();
            stack.add(i);
            visited[i] = true;

            long sumX = 0;
            long sumY = 0;
            long sumZ = 0;
            int count = 0;
            int minX = Integer.MAX_VALUE;
            int minY = Integer.MAX_VALUE;
            int minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int maxY = Integer.MIN_VALUE;
            int maxZ = Integer.MIN_VALUE;

            while (!stack.isEmpty()) {
                int cur = stack.remove(stack.size() - 1);
                int[] cp = positions.get(cur);
                sumX += cp[0];
                sumY += cp[1];
                sumZ += cp[2];
                count++;
                minX = Math.min(minX, cp[0]);
                minY = Math.min(minY, cp[1]);
                minZ = Math.min(minZ, cp[2]);
                maxX = Math.max(maxX, cp[0]);
                maxY = Math.max(maxY, cp[1]);
                maxZ = Math.max(maxZ, cp[2]);

                for (int j = 0; j < n; j++) {
                    if (visited[j]) {
                        continue;
                    }
                    int[] op = positions.get(j);
                    double dx = cp[0] - op[0];
                    double dy = cp[1] - op[1];
                    double dz = cp[2] - op[2];
                    if (dx * dx + dy * dy + dz * dz <= adjSq) {
                        visited[j] = true;
                        stack.add(j);
                    }
                }
            }

            clusters.add(new Cluster(
                    new double[] { (double) sumX / count, (double) sumY / count, (double) sumZ / count },
                    new int[] { minX, minY, minZ },
                    new int[] { maxX, maxY, maxZ }));
        }

        return clusters;
    }
}
