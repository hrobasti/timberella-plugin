package com.hro_basti.timberella;

import com.hro_basti.timberella.commands.TimberellaCommand;
import com.hro_basti.timberella.listeners.TreeChopListener;
import com.hro_basti.timberella.listeners.UpdateNotifyListener;
import com.hro_basti.timberella.metrics.Metrics;
import com.hro_basti.timberella.update.UpdateChecker;
import com.hro_basti.timberella.util.MessageService;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.logging.Level;
import java.util.zip.CRC32;

public class TimberellaPlugin extends JavaPlugin {
    private static final int BSTATS_PLUGIN_ID = 28062;
    private static final String REQUIRED_SERVER_BRAND = "Paper";
    private static final Set<String> SUPPORTED_MC_VERSIONS = Collections.unmodifiableSet(new LinkedHashSet<>(List.of(
        "1.21",
        "1.21.1",
        "1.21.2",
        "1.21.3",
        "1.21.4",
        "1.21.5",
        "1.21.6",
        "1.21.7",
        "1.21.8",
        "1.21.9",
        "1.21.10"
    )));
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final String[] STARTUP_BANNER_LINES = {
        "##########################################",
        " _____ _       _                 _ _      ",
        "|_   _(_)_ __ | |__  ___ _ _ ___| | |__ _ ",
        "  | | | | '  \\| '_ \\/ -_) '_/ -_) | / _` |",
        "  |_| |_|_|_|_|_.__/\\___|_| \\___|_|_\\__,_|",
        "                                          ",
        "##########################################"
    };
    private record MergeResult(String fileName, java.util.List<String> addedKeys) {}
    private MessageService messages;
    private UpdateChecker updateChecker;
    private TreeChopListener treeChopListener;
    private Metrics metrics;
    private volatile long lastConfigModified = 0L;
    private volatile long lastConfigSize = -1L;
    private volatile int lastConfigHash = 0;
    private volatile boolean configWatchEnabledFlag = true;
    private BukkitTask configWatcherTask;
    private BukkitTask periodicUpdateTask;
    private volatile UpdateChecker.UpdateInfo pendingUpdateInfo;
    private volatile boolean announceNextUpdateSummary = true;
    private final java.util.Set<java.util.UUID> disabledPlayers = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    private Map<String, String> lastConfigSnapshot = new LinkedHashMap<>();
    private Map<String, String> lastLeafSnapshot = new LinkedHashMap<>();
    private Map<String, Integer> lastLocaleHashes = new LinkedHashMap<>();
    private boolean changeTrackingInitialized = false;

    @Override
    public void onEnable() {
        logStartupBanner();
        // Ensure default config exists
        saveDefaultConfig();
        ensureResourceExists("leaf_mappings.yml");
        // Messages
        this.messages = new MessageService(this);
        String lang = getConfig().getString("language", "en_US");
        this.messages.load(lang);
        syncAllYamlDefaults(lang);

        // Register command + tab completion
        PluginCommand timberellaCommand = getCommand("timberella");
        if (timberellaCommand == null) {
            getLogger().severe("Command 'timberella' is missing from plugin.yml. Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        TimberellaCommand executor = new TimberellaCommand(this);
        timberellaCommand.setExecutor(executor);
        timberellaCommand.setTabCompleter(new com.hro_basti.timberella.commands.TimberellaTabCompleter(this));

        // Merge any new default config keys without overriding user values
        updateConfigModifiedTimestamp();
        refreshConfigWatchFlag();

        // Register events (keep reference for refresh on reload)
        PluginManager pm = Bukkit.getPluginManager();
        this.treeChopListener = new TreeChopListener(this);
        pm.registerEvents(this.treeChopListener, this);
        pm.registerEvents(new UpdateNotifyListener(this), this);

        // Update checker (fail-safe)
        announceNextUpdateSummary = true;
        configureUpdateChecker();

        // Start periodic watcher for external config edits (every 5s)
        startConfigWatcher();

        // Load player toggles
        loadToggles();

        // bStats metrics (opt-in via config)
        setupMetrics();
        warnIfUnsupportedEnvironment();

        logModuleStates();
        updateChangeTrackingSnapshots(
            flattenConfiguration(getConfig()),
            loadLeafMappingsSnapshot(),
            computeLocaleFingerprints()
        );
        getLogger().info("Timberella enabled.");
    }

    @Override
    public void onDisable() {
        shutdownMetrics();
        cancelScheduledUpdateChecks();
        stopConfigWatcher();
        getLogger().info("Timberella disabled.");
    }

    public MessageService messages() {
        return messages;
    }

    public void reloadAndMergeConfig() {
        Map<String, String> previousConfigSnapshot = new LinkedHashMap<>(lastConfigSnapshot);
        Map<String, String> previousLeafSnapshot = new LinkedHashMap<>(lastLeafSnapshot);
        Map<String, Integer> previousLocaleHashes = new LinkedHashMap<>(lastLocaleHashes);
        boolean baselineReady = changeTrackingInitialized;
        reloadConfig();
        // Reload language after potential changes
        String lang = getConfig().getString("language", "en_US");
        messages.load(lang);
        syncAllYamlDefaults(lang);
        // Refresh listener material sets
        if (treeChopListener != null) treeChopListener.refresh();
        updateConfigModifiedTimestamp();
        refreshConfigWatchFlag();
        setupMetrics();
        warnIfUnsupportedEnvironment();
        announceNextUpdateSummary = true;
        configureUpdateChecker();
        logModuleStates();
        Map<String, String> currentConfigSnapshot = flattenConfiguration(getConfig());
        Map<String, String> currentLeafSnapshot = loadLeafMappingsSnapshot();
        Map<String, Integer> currentLocaleFingerprints = computeLocaleFingerprints();
        if (baselineReady) {
            logFileChangeSummary(
                previousConfigSnapshot,
                currentConfigSnapshot,
                previousLeafSnapshot,
                currentLeafSnapshot,
                previousLocaleHashes,
                currentLocaleFingerprints
            );
        }
        updateChangeTrackingSnapshots(currentConfigSnapshot, currentLeafSnapshot, currentLocaleFingerprints);
    }

    private MergeResult mergeMissingConfigKeys() {
        InputStream in = getResource("config.yml");
        if (in == null) return new MergeResult("config.yml", java.util.Collections.emptyList());
        YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        FileConfiguration current = getConfig();
        java.util.List<String> addedKeys = new java.util.ArrayList<>();
        mergeSections(current, defaults, "", addedKeys);
        if (!addedKeys.isEmpty()) {
            saveConfig();
        }
        return new MergeResult("config.yml", addedKeys);
    }

    private MergeResult mergeYamlResource(String relativePath) {
        ensureResourceExists(relativePath);
        InputStream in = getResource(relativePath);
        if (in == null) {
            return new MergeResult(relativePath, java.util.Collections.emptyList());
        }
        YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        File targetFile = new File(getDataFolder(), relativePath);
        YamlConfiguration current = YamlConfiguration.loadConfiguration(targetFile);
        java.util.List<String> addedKeys = new java.util.ArrayList<>();
        mergeSections(current, defaults, "", addedKeys);
        if (!addedKeys.isEmpty()) {
            try {
                current.save(targetFile);
            } catch (java.io.IOException e) {
                getLogger().fine("Could not save " + relativePath + ": " + e.getMessage());
            }
        }
        return new MergeResult(relativePath, addedKeys);
    }

    private void syncAllYamlDefaults(String primaryLocale) {
        logMergeReport(mergeMissingConfigKeys());
        logMergeReport(mergeYamlResource("leaf_mappings.yml"));
        if (messages == null) {
            return;
        }
        Set<String> locales = new LinkedHashSet<>(MessageService.getBundledLocales());
        if (primaryLocale != null && !primaryLocale.isBlank()) {
            locales.add(primaryLocale);
        }
        for (String locale : locales) {
            java.util.List<String> added = messages.syncLocaleFile(locale);
            logMergeReport(new MergeResult("lang/" + locale + ".yml", added));
        }
    }

    private void startConfigWatcher() {
        stopConfigWatcher();
        configWatcherTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            if (!configWatchEnabledFlag) return;
            try {
                var file = new java.io.File(getDataFolder(), "config.yml");
                if (!file.exists()) return;
                long lm = file.lastModified();
                long size = file.length();
                boolean changed = (lm != lastConfigModified) || (size != lastConfigSize);
                int hash = lastConfigHash;
                if (!changed) {
                    hash = computeFileHash(file);
                    changed = (hash != lastConfigHash);
                } else {
                    hash = computeFileHash(file);
                }
                if (changed) {
                    rememberConfigFingerprint(lm, size, hash);
                    Bukkit.getScheduler().runTask(this, this::reloadAndMergeConfig);
                }
            } catch (Exception e) {
                getLogger().fine("Config watcher error: " + e.getMessage());
            }
        }, 100L, 100L); // 5 seconds interval
    }

    private void updateConfigModifiedTimestamp() {
        var file = new java.io.File(getDataFolder(), "config.yml");
        if (!file.exists()) {
            rememberConfigFingerprint(0L, -1L, 0);
            return;
        }
        rememberConfigFingerprint(file.lastModified(), file.length(), computeFileHash(file));
    }

    private void rememberConfigFingerprint(long modified, long size, int hash) {
        lastConfigModified = modified;
        lastConfigSize = size;
        lastConfigHash = hash;
    }

    private int computeFileHash(File file) {
        CRC32 crc = new CRC32();
        try (var in = Files.newInputStream(file.toPath())) {
            byte[] buffer = new byte[1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                crc.update(buffer, 0, read);
            }
            return (int) crc.getValue();
        } catch (IOException e) {
            return -1;
        }
    }

    private void refreshConfigWatchFlag() {
        configWatchEnabledFlag = getConfig().getBoolean("config_watch_enabled", true);
    }

    private void stopConfigWatcher() {
        if (configWatcherTask != null) {
            configWatcherTask.cancel();
            configWatcherTask = null;
        }
    }

    // ===== Player toggle management =====
    public boolean isEnabledFor(java.util.UUID uuid) {
        return !disabledPlayers.contains(uuid);
    }
    public void setEnabledFor(java.util.UUID uuid, boolean enabled) {
        if (enabled) disabledPlayers.remove(uuid); else disabledPlayers.add(uuid);
        saveToggles();
    }
    public boolean toggleEnabled(java.util.UUID uuid) {
        boolean now = !disabledPlayers.contains(uuid);
        setEnabledFor(uuid, !now);
        return !now;
    }

    private java.io.File togglesFile() {
        return new java.io.File(getDataFolder(), "toggles.yml");
    }
    private void loadToggles() {
        try {
            var f = togglesFile();
            if (!f.exists()) return;
            var cfg = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(f);
            var list = cfg.getStringList("disabled");
            disabledPlayers.clear();
            for (String s : list) {
                try { disabledPlayers.add(java.util.UUID.fromString(s)); } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            getLogger().fine("Failed to load toggles.yml: " + e.getMessage());
        }
    }
    private void saveToggles() {
        try {
            var f = togglesFile();
            if (!f.getParentFile().exists()) f.getParentFile().mkdirs();
            var cfg = new org.bukkit.configuration.file.YamlConfiguration();
            java.util.List<String> list = new java.util.ArrayList<>();
            synchronized (disabledPlayers) {
                for (java.util.UUID u : disabledPlayers) list.add(u.toString());
            }
            cfg.set("disabled", list);
            cfg.save(f);
        } catch (Exception e) {
            getLogger().fine("Failed to save toggles.yml: " + e.getMessage());
        }
    }

    private void mergeSections(ConfigurationSection target, ConfigurationSection defaults, String pathPrefix, java.util.List<String> addedKeys) {
        for (String key : defaults.getKeys(false)) {
            Object defVal = defaults.get(key);
            String fullKey = pathPrefix == null || pathPrefix.isEmpty() ? key : pathPrefix + "." + key;
            if (!target.contains(key)) {
                target.set(key, defVal);
                if (addedKeys != null) {
                    addedKeys.add(fullKey);
                }
                continue;
            }
            if (defVal instanceof ConfigurationSection) {
                ConfigurationSection defChild = defaults.getConfigurationSection(key);
                ConfigurationSection tgtChild = target.getConfigurationSection(key);
                if (defChild != null && tgtChild != null) {
                    mergeSections(tgtChild, defChild, fullKey, addedKeys);
                }
            }
        }
    }

    private void logMergeReport(MergeResult result) {
        if (result == null) return;
        if (result.addedKeys().isEmpty()) return;
        java.util.Map<String, String> placeholders = new java.util.HashMap<>();
        placeholders.put("file", result.fileName());
        placeholders.put("count", Integer.toString(result.addedKeys().size()));
        placeholders.put("keys", String.join(", ", result.addedKeys()));
        logLocalized(Level.INFO,
            "Defaults updated ({file}): {count} new keys -> {keys}",
            "log_defaults_updated",
            placeholders);
    }

    private void logModuleStates() {
        boolean timber = getConfig().getBoolean("enable_timber", true);
        boolean leaves = getConfig().getBoolean("enable_leaves_decay", true);
        boolean replantEnabled = getConfig().getBoolean("enable_replant", true);
        java.util.Map<String, String> placeholders = new java.util.HashMap<>();
        placeholders.put("timber", stateLabel(timber));
        placeholders.put("replant", stateLabel(replantEnabled));
        placeholders.put("leaves", stateLabel(leaves));
        logLocalized(Level.INFO,
            "Module status -> Timber: {timber}, Replant: {replant}, Leaves: {leaves}",
            "log_modules_summary",
            placeholders);
    }

    private void logStartupBanner() {
        var console = Bukkit.getConsoleSender();
        if (console == null) {
            return;
        }
        for (String line : STARTUP_BANNER_LINES) {
            console.sendMessage(MINI.deserialize("<light_purple>" + line + "</light_purple>"));
        }
    }

    private String stateLabel(boolean flag) {
        if (messages != null) {
            return flag
                ? messages.plain("log_modules_state_active")
                : messages.plain("log_modules_state_inactive");
        }
        return flag ? "enabled" : "disabled";
    }

    private void setupMetrics() {
        boolean enabled = getConfig().getBoolean("metrics_enabled", true);
        if (!enabled) {
            shutdownMetrics();
            return;
        }
        if (metrics != null) {
            return;
        }
        metrics = new Metrics(this, BSTATS_PLUGIN_ID);
        registerMetricsCharts(metrics);
    }

    private void shutdownMetrics() {
        if (metrics != null) {
            metrics.shutdown();
            metrics = null;
        }
    }

    private void registerMetricsCharts(Metrics metricsInstance) {
        metricsInstance.addCustomChart(new Metrics.SimplePie("language", () -> getConfig().getString("language", "en_US")));
        metricsInstance.addCustomChart(new Metrics.SimplePie("sneak_mode", () -> switch (getConfig().getInt("sneak_mode", 0)) {
            case 1 -> "not_sneaking";
            case 2 -> "always";
            default -> "sneak_only";
        }));
        metricsInstance.addCustomChart(new Metrics.SimplePie("durability_mode", () -> getConfig().getString("tools.durability_mode", "first")));
        metricsInstance.addCustomChart(new Metrics.SimplePie("update_provider", () -> switch (getConfig().getInt("update_provider", 0)) {
            case 1 -> "modrinth_only";
            case 2 -> "hangar_only";
            default -> "modrinth_hangar";
        }));
        metricsInstance.addCustomChart(new Metrics.AdvancedPie("enabled_modules", () -> {
            Map<String, Integer> values = new HashMap<>();
            if (getConfig().getBoolean("enable_timber", true)) values.put("timber", 1);
            if (getConfig().getBoolean("enable_replant", true)) values.put("replant_listener", 1);
            if (getConfig().getBoolean("enable_leaves_decay", true)) values.put("leaves_decay", 1);
            return values;
        }));
        metricsInstance.addCustomChart(new Metrics.SimplePie("config_watch", () -> getConfig().getBoolean("config_watch_enabled", true) ? "enabled" : "disabled"));
        metricsInstance.addCustomChart(new Metrics.SingleLineChart("max_blocks_limit", () -> getConfig().getInt("max_blocks", 1024)));
        metricsInstance.addCustomChart(new Metrics.SimplePie("player_toggle_usage", () -> {
            synchronized (disabledPlayers) {
                return disabledPlayers.isEmpty() ? "all_enabled" : "some_disabled";
            }
        }));
    }

    private void configureUpdateChecker() {
        cancelScheduledUpdateChecks();
        pendingUpdateInfo = null;
        if (!getConfig().getBoolean("check_updates", true)) {
            updateChecker = null;
            return;
        }
        int provider = getConfig().getInt("update_provider", 0);
        boolean includePrereleases = getConfig().getBoolean("update_include_prereleases", false);
        this.updateChecker = new UpdateChecker(this, provider, includePrereleases);
        scheduleUpdateChecks();
    }

    private void scheduleUpdateChecks() {
        if (updateChecker == null) {
            return;
        }
        long hours = Math.max(1L, getConfig().getLong("update_check_interval_hours", 24L));
        long ticks = hours * 60L * 60L * 20L;
        triggerUpdateCheck();
        periodicUpdateTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, this::triggerUpdateCheck, ticks, ticks);
    }

    private void cancelScheduledUpdateChecks() {
        if (periodicUpdateTask != null) {
            periodicUpdateTask.cancel();
            periodicUpdateTask = null;
        }
    }

    private void triggerUpdateCheck() {
        if (updateChecker == null) {
            return;
        }
        updateChecker
            .checkAsync()
            .thenAccept(this::handleUpdateInfo);
    }

    private void handleUpdateInfo(UpdateChecker.UpdateInfo info) {
        if (info == null) {
            return;
        }
        boolean shouldLog = announceNextUpdateSummary || info.hasUpdate();
        if (shouldLog) {
            logUpdateSummary(info);
            announceNextUpdateSummary = false;
        }
        if (!info.hasUpdate()) {
            pendingUpdateInfo = null;
            return;
        }
        boolean alreadyAnnounced = pendingUpdateInfo != null
            && pendingUpdateInfo.latestVersion().equals(info.latestVersion());
        pendingUpdateInfo = info;
        if (alreadyAnnounced) {
            return;
        }
        Bukkit.getScheduler()
            .runTask(
                this,
                () -> {
                    Map<String, String> placeholders = new HashMap<>();
                    placeholders.put("latest_ver", info.latestVersion());
                    placeholders.put("current_ver", info.currentVersion());
                    getServer().getConsoleSender().sendMessage(messages.format("update_available", placeholders));
                });
    }

    public UpdateChecker.UpdateInfo getPendingUpdateInfo() {
        return pendingUpdateInfo;
    }

    private void logUpdateSummary(UpdateChecker.UpdateInfo info) {
        if (info == null) return;
        java.util.List<UpdateChecker.ProviderResult> providers =
            info.providers() == null ? java.util.Collections.emptyList() : info.providers();
        boolean hasProviderDetails = !providers.isEmpty();
        boolean shouldBracketLog = info.hasUpdate() || hasProviderDetails;

        if (shouldBracketLog) {
            logDivider();
        }

        if (info.hasUpdate()) {
            logLocalized(Level.INFO,
                "Update found. Current version: {current}",
                "log_update_found",
                java.util.Map.of("current", info.currentVersion()));
        } else {
            logLocalized(Level.INFO, "No update found.", "log_update_none", java.util.Collections.emptyMap());
        }

        if (hasProviderDetails) {
            for (UpdateChecker.ProviderResult result : providers) {
                logProviderResult(result);
            }
        }

        if (shouldBracketLog) {
            logDivider();
        }
    }

    private void logDivider() {
        getLogger().info("=======================");
    }

    private void logProviderResult(UpdateChecker.ProviderResult result) {
        if (result == null) {
            return;
        }
        java.util.Map<String, String> placeholders = new java.util.HashMap<>();
        placeholders.put("provider", result.provider().displayName());
        if (!result.success()) {
            logLocalized(Level.INFO,
                "- {provider}: Failed to fetch.",
                "log_update_provider_error",
                placeholders);
            return;
        }
        placeholders.put("version", result.latestVersion());
        placeholders.put("url", result.url());
        logLocalized(Level.INFO,
            "- {provider}: Version {version} [{url}]",
            "log_update_provider_ok",
            placeholders);
    }

    private void warnIfUnsupportedEnvironment() {
        if (messages == null) return;
        String serverName = getServer().getName();
        String mcVersion = getServer().getMinecraftVersion();
        boolean serverOk = serverName != null && serverName.toLowerCase(Locale.ROOT).contains(REQUIRED_SERVER_BRAND.toLowerCase(Locale.ROOT));
        boolean versionOk = mcVersion != null && SUPPORTED_MC_VERSIONS.contains(mcVersion);
        if (serverOk && versionOk) {
            return;
        }
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("server_name", serverName != null ? serverName : "unknown");
        placeholders.put("mc_version", mcVersion != null ? mcVersion : "unknown");
        placeholders.put("supported_versions", String.join(", ", SUPPORTED_MC_VERSIONS));
        placeholders.put("required_server", REQUIRED_SERVER_BRAND);
        var console = getServer().getConsoleSender();
        if (console != null) {
            console.sendMessage(messages.format("warn_unsupported_environment", placeholders));
        }
        String warningText = messages != null
            ? messages.plain("warn_unsupported_environment", placeholders)
            : "Warning: Timberella might be running in an unsupported environment.";
        getLogger().warning(warningText);
    }

    private void updateChangeTrackingSnapshots(Map<String, String> configSnapshot,
                                               Map<String, String> leafSnapshot,
                                               Map<String, Integer> localeSnapshot) {
        lastConfigSnapshot = new LinkedHashMap<>(configSnapshot == null ? Collections.emptyMap() : configSnapshot);
        lastLeafSnapshot = new LinkedHashMap<>(leafSnapshot == null ? Collections.emptyMap() : leafSnapshot);
        lastLocaleHashes = new LinkedHashMap<>(localeSnapshot == null ? Collections.emptyMap() : localeSnapshot);
        changeTrackingInitialized = true;
    }

    private Map<String, String> flattenConfiguration(ConfigurationSection section) {
        Map<String, String> result = new LinkedHashMap<>();
        if (section == null) {
            return result;
        }
        flattenSection(section, "", result);
        return result;
    }

    private void flattenSection(ConfigurationSection section, String prefix, Map<String, String> out) {
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            String fullKey = prefix == null || prefix.isEmpty() ? key : prefix + "." + key;
            if (section.isConfigurationSection(key)) {
                flattenSection(section.getConfigurationSection(key), fullKey, out);
                continue;
            }
            Object value = section.get(key);
            out.put(fullKey, formatValue(value));
        }
    }

    private String formatValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof List<?>) {
            return ((List<?>) value).stream()
                .map(String::valueOf)
                .collect(Collectors.joining(", "));
        }
        if (value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            java.util.List<String> parts = new java.util.ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                Object element = java.lang.reflect.Array.get(value, i);
                parts.add(String.valueOf(element));
            }
            return String.join(", ", parts);
        }
        return String.valueOf(value);
    }

    private Map<String, String> loadLeafMappingsSnapshot() {
        File file = new File(getDataFolder(), "leaf_mappings.yml");
        if (!file.exists()) {
            return Collections.emptyMap();
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        return flattenConfiguration(yaml);
    }

    private Map<String, Integer> computeLocaleFingerprints() {
        Map<String, Integer> hashes = new LinkedHashMap<>();
        File langDir = new File(getDataFolder(), "lang");
        if (!langDir.exists()) {
            return hashes;
        }
        File[] files = langDir.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (files == null) {
            return hashes;
        }
        for (File file : files) {
            hashes.put(file.getName(), computeFileHash(file));
        }
        return hashes;
    }

    private void logFileChangeSummary(Map<String, String> previousConfig,
                                      Map<String, String> currentConfig,
                                      Map<String, String> previousLeaf,
                                      Map<String, String> currentLeaf,
                                      Map<String, Integer> previousLocales,
                                      Map<String, Integer> currentLocales) {
        java.util.List<String> configChanges = describeChanges(previousConfig, currentConfig);
        java.util.List<String> leafChanges = describeChanges(previousLeaf, currentLeaf);
        java.util.List<String> localeChanges = detectLocaleChanges(previousLocales, currentLocales);

        if (configChanges.isEmpty() && leafChanges.isEmpty() && localeChanges.isEmpty()) {
            logLocalized(Level.INFO,
                "No configuration changes detected.",
                "log_reload_changes_none",
                Collections.emptyMap());
            return;
        }

        logLocalized(Level.INFO,
            "Detected file changes:",
            "log_reload_changes_header",
            Collections.emptyMap());
        logChangeLine("config.yml", configChanges);
        logChangeLine("leaf_mappings.yml", leafChanges);
        for (String localeFile : localeChanges) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("file", localeFile);
            logLocalized(Level.INFO,
                "{file}",
                "log_reload_changes_locale_line",
                placeholders);
        }
    }

    private void logChangeLine(String fileName, java.util.List<String> changes) {
        if (changes == null || changes.isEmpty()) return;
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("file", fileName);
        placeholders.put("changes", String.join(", ", changes));
        logLocalized(Level.INFO,
            "{file} ({changes})",
            "log_reload_changes_line",
            placeholders);
    }

    private java.util.List<String> describeChanges(Map<String, String> previous, Map<String, String> current) {
        Map<String, String> safePrevious = previous == null ? Collections.emptyMap() : previous;
        Map<String, String> safeCurrent = current == null ? Collections.emptyMap() : current;
        java.util.Set<String> keys = new TreeSet<>();
        keys.addAll(safePrevious.keySet());
        keys.addAll(safeCurrent.keySet());
        java.util.List<String> changes = new java.util.ArrayList<>();
        for (String key : keys) {
            String oldVal = safePrevious.get(key);
            String newVal = safeCurrent.get(key);
            if (Objects.equals(oldVal, newVal)) continue;
            if (newVal == null) {
                changes.add(key + "=<removed>");
            } else {
                changes.add(key + "=" + newVal);
            }
        }
        return changes;
    }

    private java.util.List<String> detectLocaleChanges(Map<String, Integer> previous,
                                                       Map<String, Integer> current) {
        Map<String, Integer> safePrevious = previous == null ? Collections.emptyMap() : previous;
        Map<String, Integer> safeCurrent = current == null ? Collections.emptyMap() : current;
        java.util.Set<String> files = new TreeSet<>();
        files.addAll(safePrevious.keySet());
        files.addAll(safeCurrent.keySet());
        java.util.List<String> changes = new java.util.ArrayList<>();
        for (String name : files) {
            Integer oldHash = safePrevious.get(name);
            Integer newHash = safeCurrent.get(name);
            if (!Objects.equals(oldHash, newHash)) {
                changes.add("lang/" + name);
            }
        }
        return changes;
    }

    private void ensureResourceExists(String relativePath) {
        File out = new File(getDataFolder(), relativePath);
        File parent = out.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        if (!out.exists()) {
            saveResource(relativePath, false);
            getLogger().info("Created default file: " + relativePath);
        }
    }

    private void logLocalized(Level level, String fallback, String messageKey, java.util.Map<String, String> placeholders) {
        java.util.Map<String, String> safePlaceholders = placeholders == null
            ? java.util.Collections.emptyMap()
            : placeholders;
        if (messages != null && messageKey != null) {
            String text = safePlaceholders.isEmpty()
                ? messages.plain(messageKey)
                : messages.plain(messageKey, safePlaceholders);
            getLogger().log(level, text);
            return;
        }
        getLogger().log(level, replacePlaceholders(fallback, safePlaceholders));
    }

    private String replacePlaceholders(String template, java.util.Map<String, String> placeholders) {
        if (template == null) {
            return "";
        }
        String result = template;
        for (java.util.Map.Entry<String, String> entry : placeholders.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }
}
