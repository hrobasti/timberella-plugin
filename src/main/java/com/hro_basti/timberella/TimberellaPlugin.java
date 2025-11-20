package com.hro_basti.timberella;

import com.hro_basti.timberella.commands.TimberellaCommand;
import com.hro_basti.timberella.listeners.TreeChopListener;
import com.hro_basti.timberella.listeners.UpdateNotifyListener;
import com.hro_basti.timberella.metrics.Metrics;
import com.hro_basti.timberella.update.UpdateChecker;
import com.hro_basti.timberella.util.MessageService;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class TimberellaPlugin extends JavaPlugin {
    private static final int BSTATS_PLUGIN_ID = 28062;
    private MessageService messages;
    private UpdateChecker updateChecker;
    private TreeChopListener treeChopListener;
    private Metrics metrics;
    private volatile long lastConfigModified = 0L;
    private volatile boolean configWatchEnabledFlag = true;
    private BukkitTask configWatcherTask;
    private BukkitTask periodicUpdateTask;
    private volatile UpdateChecker.UpdateInfo pendingUpdateInfo;
    private final java.util.Set<java.util.UUID> disabledPlayers = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    @Override
    public void onEnable() {
        // Ensure default config exists
        saveDefaultConfig();
        ensureResourceExists("leaf_mappings.yml");
        // Messages
        this.messages = new MessageService(this);
        String lang = getConfig().getString("language", "en_US");
        this.messages.load(lang);

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
        mergeMissingConfigKeys();
        updateConfigModifiedTimestamp();
        refreshConfigWatchFlag();

        // Register events (keep reference for refresh on reload)
        PluginManager pm = Bukkit.getPluginManager();
        this.treeChopListener = new TreeChopListener(this);
        pm.registerEvents(this.treeChopListener, this);
        pm.registerEvents(new UpdateNotifyListener(this), this);

        // Update checker (fail-safe)
        configureUpdateChecker();

        // Start periodic watcher for external config edits (every 5s)
        startConfigWatcher();

        // Load player toggles
        loadToggles();

        // bStats metrics (opt-in via config)
        setupMetrics();

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
        reloadConfig();
        // Reload language after potential changes
        String lang = getConfig().getString("language", "en_US");
        messages.load(lang);
        // Refresh listener material sets
        if (treeChopListener != null) treeChopListener.refresh();
        updateConfigModifiedTimestamp();
        refreshConfigWatchFlag();
        setupMetrics();
        configureUpdateChecker();
    }

    private void mergeMissingConfigKeys() {
        InputStream in = getResource("config.yml");
        if (in == null) return; // should not happen
        YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        FileConfiguration current = getConfig();
        mergeSections(current, defaults);
        saveConfig();
    }

    private void startConfigWatcher() {
        stopConfigWatcher();
        configWatcherTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            if (!configWatchEnabledFlag) return;
            try {
                var file = new java.io.File(getDataFolder(), "config.yml");
                if (!file.exists()) return;
                long lm = file.lastModified();
                if (lm > lastConfigModified) {
                    lastConfigModified = lm;
                    Bukkit.getScheduler().runTask(this, this::reloadAndMergeConfig);
                }
            } catch (Exception e) {
                getLogger().fine("Config watcher error: " + e.getMessage());
            }
        }, 100L, 100L); // 5 seconds interval
    }

    private void updateConfigModifiedTimestamp() {
        var file = new java.io.File(getDataFolder(), "config.yml");
        if (file.exists()) {
            lastConfigModified = file.lastModified();
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

    private void mergeSections(ConfigurationSection target, ConfigurationSection defaults) {
        for (String key : defaults.getKeys(false)) {
            Object defVal = defaults.get(key);
            if (!target.contains(key)) {
                target.set(key, defVal);
                continue;
            }
            if (defVal instanceof ConfigurationSection) {
                ConfigurationSection defChild = defaults.getConfigurationSection(key);
                ConfigurationSection tgtChild = target.getConfigurationSection(key);
                if (defChild != null && tgtChild != null) {
                    mergeSections(tgtChild, defChild);
                }
            }
        }
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
            if (getConfig().getBoolean("replant.enabled", true)) values.put("sapling_replant", 1);
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

    private void ensureResourceExists(String relativePath) {
        File out = new File(getDataFolder(), relativePath);
        File parent = out.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        if (!out.exists()) {
            saveResource(relativePath, false);
        }
    }
}
