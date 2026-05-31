package dev.aldis.bluemapportalmarkers;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Polls the GitHub Releases API for a newer version and logs a single INFO line
 * if one exists. Entirely best-effort: the network call runs off the main thread
 * and any failure (offline, rate-limited, malformed response) is swallowed at
 * debug level — a metrics/notice feature must never disrupt the server.
 */
public final class UpdateChecker {

    private final JavaPlugin plugin;
    private final Log log;
    private final String repo;
    private final String currentVersion;

    public UpdateChecker(JavaPlugin plugin, Log log, String repo, String currentVersion) {
        this.plugin = plugin;
        this.log = log;
        this.repo = repo;
        this.currentVersion = currentVersion;
    }

    /** Schedule the check on an async thread; returns immediately. */
    public void checkAsync() {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, this::check);
    }

    private void check() {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.github.com/repos/" + repo + "/releases/latest"))
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "BlueMapPortalMarkers")
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.debug("Update check skipped: GitHub returned HTTP " + response.statusCode());
                return;
            }
            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            if (!json.has("tag_name")) {
                return;
            }
            String latest = json.get("tag_name").getAsString();
            if (VersionCompare.isNewer(currentVersion, latest)) {
                log.info("A newer version is available: " + latest + " (running " + currentVersion
                        + "). Get it at https://github.com/" + repo + "/releases/latest");
            } else {
                log.debug("Update check: up to date (latest " + latest + ", running " + currentVersion + ").");
            }
        } catch (Exception ex) {
            // Fail silent (debug only): never let an update check disrupt startup.
            log.debug("Update check failed: " + ex.getMessage());
        }
    }
}
