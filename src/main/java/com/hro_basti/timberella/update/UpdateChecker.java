package com.hro_basti.timberella.update;

import com.hro_basti.timberella.TimberellaPlugin;
import com.hro_basti.timberella.util.VersionComparator;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import com.google.gson.*;
import java.util.concurrent.CompletableFuture;

public class UpdateChecker {
    private final TimberellaPlugin plugin;
    private final int providerMode; // 0 both, 1 modrinth, 2 hangar
    private final boolean includePrereleases;

    // Internal slugs (not in config by design)
    private static final String MODRINTH_SLUG = "timberella";
    private static final String HANGAR_NAMESPACE = "hro_basti/timberella"; // owner/project

    public UpdateChecker(TimberellaPlugin plugin, int providerMode, boolean includePrereleases) {
        this.plugin = plugin;
        this.providerMode = providerMode;
        this.includePrereleases = includePrereleases;
    }

    public CompletableFuture<UpdateInfo> checkAsync() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String current = plugin.getPluginMeta().getVersion();
                String latest = null;
                if (providerMode == 0 || providerMode == 1) {
                    latest = latestFromModrinth();
                }
                if ((latest == null || latest.isEmpty()) && (providerMode == 0 || providerMode == 2)) {
                    latest = latestFromHangar();
                }
                if (latest != null && !latest.isEmpty() && VersionComparator.isGreater(latest, current)) {
                    return new UpdateInfo(latest, current);
                }
            } catch (Throwable t) {
                plugin.getLogger().fine("Update check failed: " + t.getMessage());
            }
            return null;
        });
    }

    public record UpdateInfo(String latestVersion, String currentVersion) {}

    private String latestFromModrinth() {
        try {
            URL url = URI.create("https://api.modrinth.com/v2/project/" + MODRINTH_SLUG + "/version").toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Timberella-UpdateChecker");
            if (conn.getResponseCode() != 200) return null;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line; while ((line = reader.readLine()) != null) sb.append(line);
                JsonElement el = JsonParser.parseString(sb.toString());
                if (el.isJsonArray()) {
                    JsonArray arr = el.getAsJsonArray();
                    for (JsonElement entry : arr) {
                        if (!entry.isJsonObject()) continue;
                        JsonObject version = entry.getAsJsonObject();
                        if (!includePrereleases && isModrinthPrerelease(version)) continue;
                        if (version.has("version_number")) {
                            return version.get("version_number").getAsString();
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String latestFromHangar() {
        try {
            URL url = URI.create("https://hangar.papermc.io/api/v1/projects/" + HANGAR_NAMESPACE + "/versions").toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Timberella-UpdateChecker");
            if (conn.getResponseCode() != 200) return null;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line; while ((line = reader.readLine()) != null) sb.append(line);
                JsonElement el = JsonParser.parseString(sb.toString());
                if (el.isJsonArray()) {
                    JsonArray arr = el.getAsJsonArray();
                    for (JsonElement entry : arr) {
                        if (!entry.isJsonObject()) continue;
                        JsonObject version = entry.getAsJsonObject();
                        if (!includePrereleases && isHangarPrerelease(version)) continue;
                        if (version.has("name")) {
                            return version.get("name").getAsString();
                        }
                    }
                } else if (el.isJsonObject()) {
                    JsonObject obj = el.getAsJsonObject();
                    // Some Hangar responses might be wrapped
                    if (obj.has("result")) {
                        JsonElement res = obj.get("result");
                        if (res.isJsonArray()) {
                            JsonArray arr = res.getAsJsonArray();
                            for (JsonElement entry : arr) {
                                if (!entry.isJsonObject()) continue;
                                JsonObject version = entry.getAsJsonObject();
                                if (!includePrereleases && isHangarPrerelease(version)) continue;
                                if (version.has("name")) return version.get("name").getAsString();
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private boolean isModrinthPrerelease(JsonObject version) {
        if (version.has("prerelease") && version.get("prerelease").isJsonPrimitive()) {
            try {
                if (version.get("prerelease").getAsBoolean()) return true;
            } catch (Exception ignored) {}
        }
        if (version.has("version_type") && version.get("version_type").isJsonPrimitive()) {
            String type = version.get("version_type").getAsString();
            return !"release".equalsIgnoreCase(type);
        }
        return false;
    }

    private boolean isHangarPrerelease(JsonObject version) {
        if (version.has("channel")) {
            JsonElement channelEl = version.get("channel");
            String channelName = null;
            if (channelEl.isJsonObject()) {
                JsonObject channelObj = channelEl.getAsJsonObject();
                if (channelObj.has("name")) {
                    channelName = channelObj.get("name").getAsString();
                }
            } else if (channelEl.isJsonPrimitive()) {
                channelName = channelEl.getAsString();
            }
            if (channelName != null) {
                return !"release".equalsIgnoreCase(channelName);
            }
        }
        if (version.has("visibility") && version.get("visibility").isJsonPrimitive()) {
            // Hangar marks snapshots as "Public" as well, so fall back to false here.
            return false;
        }
        return false;
    }
}
