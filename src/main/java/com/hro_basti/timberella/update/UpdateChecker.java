package com.hro_basti.timberella.update;

import com.google.gson.*;
import com.hro_basti.timberella.TimberellaPlugin;
import com.hro_basti.timberella.util.VersionComparator;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
                List<ProviderResult> providers = new ArrayList<>();
                if (providerMode == 0 || providerMode == 1) {
                    providers.add(fetchModrinthStatus());
                }
                if (providerMode == 0 || providerMode == 2) {
                    providers.add(fetchHangarStatus());
                }
                String latest = findNewestAvailableVersion(current, providers);
                return new UpdateInfo(current, latest, providers);
            } catch (Throwable t) {
                plugin.getLogger().fine("Update check failed: " + t.getMessage());
                return new UpdateInfo(plugin.getPluginMeta().getVersion(), null, Collections.emptyList());
            }
        });
    }

    public record UpdateInfo(String currentVersion, String latestVersion, List<ProviderResult> providers) {
        public boolean hasUpdate() {
            return latestVersion != null && !latestVersion.isBlank();
        }
    }

    public record ProviderResult(Provider provider, String latestVersion, String url, String errorMessage) {
        public boolean success() {
            return errorMessage == null;
        }
    }

    public enum Provider {
        MODRINTH("Modrinth", "https://modrinth.com/plugin/" + MODRINTH_SLUG),
        HANGAR("Hangar", "https://hangar.papermc.io/" + HANGAR_NAMESPACE);

        private final String displayName;
        private final String url;

        Provider(String displayName, String url) {
            this.displayName = displayName;
            this.url = url;
        }

        public String displayName() {
            return displayName;
        }

        public String url() {
            return url;
        }
    }

    private ProviderResult fetchModrinthStatus() {
        try {
            String version = latestFromModrinth();
            if (version == null || version.isBlank()) {
                return errorResult(Provider.MODRINTH, "Fehler beim Abruf");
            }
            return new ProviderResult(Provider.MODRINTH, version, Provider.MODRINTH.url(), null);
        } catch (Exception ex) {
            plugin.getLogger().fine("Modrinth update check failed: " + ex.getMessage());
            return errorResult(Provider.MODRINTH, "Fehler beim Abruf");
        }
    }

    private ProviderResult fetchHangarStatus() {
        try {
            String version = latestFromHangar();
            if (version == null || version.isBlank()) {
                return errorResult(Provider.HANGAR, "Fehler beim Abruf");
            }
            return new ProviderResult(Provider.HANGAR, version, Provider.HANGAR.url(), null);
        } catch (Exception ex) {
            plugin.getLogger().fine("Hangar update check failed: " + ex.getMessage());
            return errorResult(Provider.HANGAR, "Fehler beim Abruf");
        }
    }

    private ProviderResult errorResult(Provider provider, String message) {
        return new ProviderResult(provider, null, provider.url(), message);
    }

    private String findNewestAvailableVersion(String current, List<ProviderResult> providers) {
        String latest = null;
        for (ProviderResult result : providers) {
            String candidate = result.latestVersion();
            if (candidate == null) continue;
            if (!VersionComparator.isGreater(candidate, current)) continue;
            if (latest == null || VersionComparator.isGreater(candidate, latest)) {
                latest = candidate;
            }
        }
        return latest;
    }

    private String latestFromModrinth() throws Exception {
        URL url = URI.create("https://api.modrinth.com/v2/project/" + MODRINTH_SLUG + "/version").toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(4000);
        conn.setReadTimeout(4000);
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", "Timberella-UpdateChecker");
        if (conn.getResponseCode() != 200) {
            throw new IllegalStateException("HTTP " + conn.getResponseCode());
        }
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
        throw new IllegalStateException("No versions found");
    }

    private String latestFromHangar() throws Exception {
        URL url = URI.create("https://hangar.papermc.io/api/v1/projects/" + HANGAR_NAMESPACE + "/versions").toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(4000);
        conn.setReadTimeout(4000);
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", "Timberella-UpdateChecker");
        if (conn.getResponseCode() != 200) {
            throw new IllegalStateException("HTTP " + conn.getResponseCode());
        }
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
        throw new IllegalStateException("No versions found");
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
