package dev.aldis.bluemapportalmarkers;

/**
 * Pure version-string comparison used by {@link UpdateChecker}. Kept free of
 * Bukkit/BlueMap imports so it can be unit-tested without those runtimes on the
 * classpath.
 */
public final class VersionCompare {

    private VersionCompare() {
    }

    /**
     * Whether {@code latest} is a strictly newer version than {@code current}.
     * Tolerant of a leading {@code v} and a trailing qualifier
     * (e.g. {@code -SNAPSHOT}); compares the dot-separated numeric components.
     */
    public static boolean isNewer(String current, String latest) {
        int[] a = parseVersion(current);
        int[] b = parseVersion(latest);
        int len = Math.max(a.length, b.length);
        for (int i = 0; i < len; i++) {
            int ai = i < a.length ? a[i] : 0;
            int bi = i < b.length ? b[i] : 0;
            if (bi != ai) {
                return bi > ai;
            }
        }
        return false;
    }

    /** Parse "v0.3.0-SNAPSHOT" → {0,3,0}; non-numeric/qualifier parts are dropped. */
    private static int[] parseVersion(String version) {
        if (version == null) {
            return new int[0];
        }
        String v = version.trim();
        if (v.startsWith("v") || v.startsWith("V")) {
            v = v.substring(1);
        }
        int dash = v.indexOf('-');
        if (dash >= 0) {
            v = v.substring(0, dash); // drop -SNAPSHOT / -rc1 / etc.
        }
        String[] parts = v.split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException ex) {
                out[i] = 0;
            }
        }
        return out;
    }
}
