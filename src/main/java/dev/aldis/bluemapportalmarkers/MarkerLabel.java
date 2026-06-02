package dev.aldis.bluemapportalmarkers;

/**
 * Formats a configurable label template for portal POI markers.
 * Supported placeholders: {@code {world}}, {@code {x}}, {@code {y}}, {@code {z}}.
 * Pure class — no Bukkit or BlueMap imports.
 */
public final class MarkerLabel {

    private MarkerLabel() {}

    /**
     * Substitutes placeholders in {@code template} with the given portal coords.
     * Coordinates are rounded to the nearest integer for display.
     * A {@code null} or empty template falls back to {@code "Nether Portal"}.
     */
    public static String format(String template, String worldName, double x, double y, double z) {
        if (template == null || template.isEmpty()) {
            return "Nether Portal";
        }
        return template
                .replace("{world}", worldName != null ? worldName : "")
                .replace("{x}", String.valueOf(Math.round(x)))
                .replace("{y}", String.valueOf(Math.round(y)))
                .replace("{z}", String.valueOf(Math.round(z)));
    }
}
