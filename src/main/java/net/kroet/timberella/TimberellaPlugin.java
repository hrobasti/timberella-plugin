package net.kroet.timberella;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kroet.timberella.commands.TimberellaCommand;
import net.kroet.timberella.compat.CustomBlockGuards;
import net.kroet.timberella.listeners.TreeChopListener;
import net.kroet.timberella.listeners.UpdateNotifyListener;
import net.kroet.timberella.util.LatestWriteGate;
import net.kroet.timberella.util.TogglesFile;
import net.kroet.turtlelib.helper.ConfigDefaultsInserter;
import net.kroet.turtlelib.helper.ConfigKeyMigrator;
import net.kroet.turtlelib.helper.ConfigWatcher;
import net.kroet.turtlelib.helper.FileChangeTracker;
import net.kroet.turtlelib.helper.LegacyDataUpgrade;
import net.kroet.turtlelib.helper.MessageService;
import net.kroet.turtlelib.helper.ServerMatcher;
import net.kroet.turtlelib.helper.StartupBanner;
import net.kroet.turtlelib.helper.UpdateChecker;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.AdvancedPie;
import org.bstats.charts.SimplePie;
import org.bstats.charts.SingleLineChart;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public class TimberellaPlugin extends JavaPlugin {
    private static final int BSTATS_PLUGIN_ID = 28062;
    private static final String STARTUP_BANNER_RESOURCE = "banner.txt";
    private static final String COMMAND_LABEL = "timberella";
    private static final String REQUIRED_SERVER_BRAND = "Paper";
    // Accepts the whole 26.3.x series, not just the literal "26.3" - a Mojang
    // hotfix bumping the patch segment shouldn't need a new Timberella release.
    private static final String SUPPORTED_MINOR_SERIES = "26.3";
    private static final String SUPPORTED_VERSION_LABEL = "26.3.x";
    private static final ServerMatcher.IncompatibleAction INCOMPATIBLE_SERVER_ACTION = ServerMatcher.IncompatibleAction.WARN_AND_CONTINUE;
    // Kebab-case config keys to their snake_case names; ConfigKeyMigrator rewrites
    // them in config.yml before it is read. Package-private for
    // LegacyConfigMigrationTest.
    static final Map<String, String> LEGACY_CONFIG_KEY_MIGRATIONS = Map.ofEntries(
            Map.entry("chat-prefix-label", "chat_prefix_label"),
            Map.entry("startup-banner-enabled", "startup_banner_enabled"),
            Map.entry("config-watch-enabled", "config_watch_enabled"),
            Map.entry("config-watch-interval-seconds", "config_watch_interval_seconds"),
            Map.entry("metrics-enabled", "metrics_enabled"),
            Map.entry("update-check.enabled", "update_check.enabled"),
            Map.entry("update-check.provider", "update_check.provider"),
            Map.entry("update-check.interval-hours", "update_check.interval_hours"),
            Map.entry("update-check.include-prereleases", "update_check.include_prereleases"),
            Map.entry("update-check.filter-by-server-version", "update_check.filter_by_server_version"),
            Map.entry("update-check.notify-console", "update_check.notify_console"),
            Map.entry("update-check.notify-console-always-shown", "update_check.notify_console_always_shown"),
            Map.entry("update-check.notify-op-join", "update_check.notify_op_join"),
            Map.entry("enable-timber", "enable_timber"),
            Map.entry("enable-replant", "enable_replant"),
            Map.entry("enable-leaves-decay", "enable_leaves_decay"),
            Map.entry("sneak-mode", "sneak_mode"),
            Map.entry("include-diagonals", "include_diagonals"),
            Map.entry("break-interval-ticks", "break_interval_ticks"),
            Map.entry("tools.allowed-axes", "tools.allowed_axes"),
            Map.entry("tools.min-remaining-durability", "tools.min_remaining_durability"),
            Map.entry("tools.durability-mode", "tools.durability_mode"),
            Map.entry("tools.durability-multiplier", "tools.durability_multiplier"),
            Map.entry("max-blocks", "max_blocks"),
            Map.entry("leaves-decay.decay-radius", "leaves_decay.decay_radius"),
            Map.entry("leaves-decay.max-distance", "leaves_decay.max_distance"),
            Map.entry("leaves-decay.batch-interval-ticks", "leaves_decay.batch_interval_ticks"),
            Map.entry("leaves-decay.batch-size", "leaves_decay.batch_size"),
            Map.entry("categories.stripped-logs", "categories.stripped_logs"),
            Map.entry("categories.stripped-woods", "categories.stripped_woods"),
            // TreeChopListener.loadSpeciesLimits() has its own fallback for these keys;
            // listed here too so rewriteLegacyKeysInFile() renames them in the file as
            // well.
            Map.entry("species-limits", "species_limits"),
            Map.entry("species-limits.pale-oak", "species_limits.pale_oak"),
            Map.entry("species-limits.dark-oak", "species_limits.dark_oak"),
            Map.entry("species-limits.mushroom-brown", "species_limits.mushroom_brown"),
            Map.entry("species-limits.mushroom-red", "species_limits.mushroom_red"),
            // Segments are renamed file-wide, so one species covers every species section.
            Map.entry("species-limits.oak.max-horizontal-radius", "species_limits.oak.max_horizontal_radius"),
            Map.entry("species-limits.oak.max-vertical-radius", "species_limits.oak.max_vertical_radius"));
    // Top-level keys only a 1.x config.yml has; any of them marks the data folder
    // for the clean upgrade. Package-private for LegacyConfigMigrationTest.
    static final List<String> LEGACY_1X_MARKER_KEYS = List.of("chat-prefix-label", "update-check",
            "species-limits", "enable-timber", "sneak-mode");
    // Update keys only a 1.0.x config.yml has; without a 1.1+ marker they mark a
    // 1.0.x folder, which gets fresh files without carrying anything over.
    static final List<String> LEGACY_1_0_MARKER_KEYS = List.of("check_updates", "update_provider",
            "update_check_interval_hours", "update_include_prereleases");
    // 1.0.x keys that 1.1-1.2.x no longer read. In a folder that ran both, the
    // 1.x upgrade skips them silently, as some share their name with a 2.0 key.
    static final List<String> LEGACY_1_0_LEFTOVER_KEYS = List.of("check_updates", "update_provider",
            "update_check_interval_hours", "update_include_prereleases", "config_watch_enabled", "metrics_enabled",
            "enable_timber", "enable_replant", "enable_leaves_decay", "sneak_mode", "include_diagonals",
            "break_interval_ticks", "max_blocks", "tools.allowed_axes", "tools.min_remaining_durability",
            "tools.durability_mode", "tools.durability_multiplier", "replant.enabled", "species_limits.*",
            "categories.stripped_logs.*", "categories.stripped_woods.*", "leaves_decay.*");
    // Keys only 2.0 has; a config.yml holding one is a 2.0 file with an old key
    // added
    // by hand, whose snake_case values are its own. Package-private for
    // LegacyUpgradeTest.
    static final List<String> CONFIG_2_0_ONLY_KEYS = List.of("require_natural_leaves", "log_stats", "log_detail",
            "replant.sapling_source", "species_limits.poplar");
    // Default files of older releases besides legacy/1.x: 1.0.1's (its leaf lists
    // are also those of 1.1.0-1.2.0) and 1.0's config.yml. A value that some
    // release shipped as that key's default doesn't count as an edit.
    static final List<String> LEGACY_OLDER_DEFAULTS_ROOTS = List.of("legacy/1.0.1/", "legacy/1.0/");
    // Sections whose entries admins add or delete themselves: a default the admin
    // deleted there stays deleted, and each new default is offered only once.
    private static final List<String> CONFIG_OPEN_SECTIONS = List.of("categories.logs", "categories.stripped_logs",
            "categories.woods", "categories.stripped_woods", "categories.fences", "categories.additions");
    private static final List<String> LEAF_MAPPINGS_OPEN_SECTIONS = List.of("log_to_leaves");
    // Package-private for LegacyLangMigrationTest.
    static final Map<String, String> LEGACY_LANG_KEY_MIGRATIONS = Map.ofEntries(
            Map.entry("ui.felling-already-running", "ui.felling_already_running"),
            Map.entry("plugin.language-set", "plugin.language_set"),
            Map.entry("command.usage-admin", "command.usage"),
            Map.entry("command.usage_admin", "command.usage"),
            Map.entry("command.no-permission", "command.no_permission"),
            Map.entry("command.player-only", "command.player_only"),
            Map.entry("command.player-not-found", "command.player_not_found"),
            Map.entry("toggle.self-enabled", "toggle.self_enabled"),
            Map.entry("toggle.self-disabled", "toggle.self_disabled"),
            Map.entry("toggle.other-enabled", "toggle.other_enabled"),
            Map.entry("toggle.other-disabled", "toggle.other_disabled"),
            Map.entry("log.defaults-updated", "log.defaults_updated"),
            Map.entry("log.modules-summary", "log.modules_summary"),
            Map.entry("log.modules-state-active", "log.modules_state_active"),
            Map.entry("log.modules-state-inactive", "log.modules_state_inactive"),
            Map.entry("log.update-found", "log.update_found"),
            Map.entry("log.update-provider-ok", "log.update_provider_ok"),
            Map.entry("log.update-provider-error", "log.update_provider_error"),
            Map.entry("log.update-none", "log.update_none"),
            Map.entry("log.reload-changes-header", "log.reload_changes_header"),
            Map.entry("log.reload-changes-line", "log.reload_changes_line"),
            Map.entry("log.reload-changes-locale-line", "log.reload_changes_locale_line"),
            Map.entry("log.reload-changes-none", "log.reload_changes_none"),
            Map.entry("warn.unsupported-server-version", "warn.unsupported_server_version"));
    private record MergeResult(String fileName, List<String> addedKeys) {
    }
    private MessageService messages;
    private volatile UpdateChecker updateChecker;
    private TreeChopListener treeChopListener;
    private Metrics metrics;
    private ConfigWatcher configWatcher;
    private ServerMatcher serverMatcher;
    private BukkitTask periodicUpdateTask;
    private volatile UpdateChecker.UpdateInfo pendingUpdateInfo;
    private volatile boolean announceNextUpdateSummary = true;
    // Providers whose failure the console has already shown since the last start,
    // reload or successful fetch; main thread only.
    private final Set<UpdateChecker.Provider> reportedProviderFailures = EnumSet.noneOf(UpdateChecker.Provider.class);
    private final Set<UUID> disabledPlayers = Collections.synchronizedSet(new HashSet<>());
    private FileChangeTracker fileChangeTracker;
    // leaf_mappings.yml on its own, compared only while it is valid YAML.
    private FileChangeTracker leafMappingsTracker;
    private FileConfiguration loadedConfig;
    // Set while config.yml wasn't valid YAML at startup: the plugin runs on the
    // bundled defaults, without bStats and update checks, until a reload succeeds.
    private boolean runningOnDefaults;

    @Override
    public void onEnable() {
        LegacyUpgrade upgrade = upgradeLegacyDataFolder();
        saveDefaultConfig();
        String configError = ConfigWatcher.describeYamlError(configFile());
        runningOnDefaults = configError != null;
        if (!runningOnDefaults) {
            // Rename any pre-existing kebab-case keys directly in the file first (keeps
            // comments/formatting, see ConfigKeyMigrator), before anything reads the
            // config - getConfig() below then loads the file fresh, already renamed.
            migrateLegacyConfigKeys(configFile(), this::getResource, getLogger());
        }
        ensureResourceExists("leaf_mappings.yml");
        if (getConfig().getBoolean("startup_banner_enabled", true)) {
            logStartupBanner();
        }
        this.messages = new MessageService(this, "<light_purple>[<prefix_label>]</light_purple>", "Timberella");
        this.messages.setLegacyKeyMigrations(LEGACY_LANG_KEY_MIGRATIONS);
        this.messages.setLegacyDefaultsRoot("legacy/1.x/");
        String lang = getConfig().getString("language", "en_US");
        this.messages.load(lang);
        applyChatPrefixLabel();
        setupServerMatcher();
        if (runningOnDefaults) {
            messages.log(getLogger(), Level.WARNING, "log.config_invalid_startup", Map.of("error", configError));
        }
        syncAllYamlDefaults();
        getLogger().info(messages.plain("plugin.language_set", Map.of("code", messages.getLanguage())));
        logUpgradeReport(upgrade);

        registerCommand(COMMAND_LABEL, "Timberella commands", new TimberellaCommand(this, COMMAND_LABEL));

        initializeConfigWatcher();

        // Register events (keep reference for refresh on reload)
        PluginManager pm = Bukkit.getPluginManager();
        this.treeChopListener = new TreeChopListener(this);
        hookCustomBlockPlugins();
        pm.registerEvents(this.treeChopListener, this);
        pm.registerEvents(new UpdateNotifyListener(this), this);

        // Update checker (fail-safe)
        announceNextUpdateSummary = true;
        configureUpdateChecker();

        if (configWatcher != null) {
            configWatcher.start();
        }

        loadToggles();

        setupMetrics();

        logModuleStates();
        fileChangeTracker = FileChangeTracker.builder(getDataFolder(), getLogger())
                .yamlFile("config.yml")
                .folder("lang", ".yml")
                .build();
        leafMappingsTracker = FileChangeTracker.builder(getDataFolder(), getLogger())
                .yamlFile("leaf_mappings.yml")
                .build();
        // On defaults, the first snapshot waits for the reload that fixes config.yml,
        // so that reload doesn't list every setting as changed.
        if (!runningOnDefaults) {
            compareTrackedFiles();
        }
        getLogger().info(messages.plain("plugin.enabled"));
    }

    private File configFile() {
        return new File(getDataFolder(), "config.yml");
    }

    @Override
    public FileConfiguration getConfig() {
        if (loadedConfig == null) {
            reloadConfig();
        }
        return loadedConfig;
    }

    // Loads like JavaPlugin, but without Bukkit's SEVERE stack trace for a
    // config.yml that isn't valid YAML; the plugin checks the file first and logs
    // its own warning.
    @Override
    public void reloadConfig() {
        loadedConfig = loadConfig(configFile(), () -> getResource("config.yml"), getLogger());
    }

    /**
     * config.yml, or the bundled file itself if it is missing or isn't valid YAML,
     * so sections like categories count as set; the bundled values are the defaults
     * either way. Package-private for ConfigLoadTest.
     */
    static YamlConfiguration loadConfig(File file, Supplier<InputStream> bundled, Logger logger) {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.load(file);
        } catch (IOException | InvalidConfigurationException e) {
            config = loadBundledConfig(bundled, logger);
        }
        config.setDefaults(loadBundledConfig(bundled, logger));
        return config;
    }

    private static YamlConfiguration loadBundledConfig(Supplier<InputStream> bundled, Logger logger) {
        YamlConfiguration config = new YamlConfiguration();
        try (InputStream in = bundled.get()) {
            if (in != null) {
                config.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        } catch (IOException | InvalidConfigurationException e) {
            logger.warning("Could not read bundled config.yml: " + e.getMessage());
        }
        return config;
    }

    private void hookCustomBlockPlugins() {
        CustomBlockGuards guards = CustomBlockGuards.detect(this);
        treeChopListener.setCustomBlockGuard(guards);
        if (!guards.hookedPlugins().isEmpty()) {
            messages.log(getLogger(), Level.INFO, "log.custom_blocks_hooked",
                    Map.of("plugins", String.join(", ", guards.hookedPlugins())));
        }
    }

    // Renames 1.x keys in config.yml in place; an old key left next to its new one,
    // as running 1.2.x again adds them back, goes while it holds the 1.x default.
    // Package-private for LegacyConfigMigrationTest.
    static void migrateLegacyConfigKeys(File file, Function<String, InputStream> resources, Logger logger) {
        try (InputStream legacyDefaults = resources.apply("legacy/1.x/config.yml")) {
            ConfigKeyMigrator.rewriteLegacyKeysInFile(file, logger, "config.yml", LEGACY_CONFIG_KEY_MIGRATIONS,
                    legacyDefaults);
        } catch (IOException e) {
            if (logger != null) {
                logger.warning("Could not read bundled legacy/1.x/config.yml: " + e.getMessage());
            }
        }
    }

    /** Outcome of the one-time upgrade; {@code from10} marks a 1.0.x folder. */
    record LegacyUpgrade(LegacyDataUpgrade.Report report, boolean from10) {
    }

    private LegacyUpgrade upgradeLegacyDataFolder() {
        return upgradeLegacyDataFolder(getDataFolder(), this::getResource, getLogger());
    }

    // A 1.x data folder moves to backup-1.x and is rebuilt from fresh files that
    // keep the admin's changed values; runs before anything reads or creates them.
    // Package-private for LegacyConfigMigrationTest.
    static LegacyUpgrade upgradeLegacyDataFolder(File dataFolder, Function<String, InputStream> resources,
            Logger logger) {
        LegacyDataUpgrade.Builder upgrade1x = LegacyDataUpgrade.builder(dataFolder, resources, logger)
                .markerKeys(LEGACY_1X_MARKER_KEYS.toArray(String[]::new))
                .backupDirName("backup-1.x")
                .legacyDefaultsRoot("legacy/1.x/")
                .freshResources("leaf_mappings.yml")
                .keepFiles("toggles.yml")
                .carryOverValues("config.yml", LEGACY_CONFIG_KEY_MIGRATIONS,
                        CONFIG_OPEN_SECTIONS.toArray(String[]::new));
        if (!LegacyDataUpgrade.hasAnyKey(new File(dataFolder, "config.yml"), CONFIG_2_0_ONLY_KEYS)) {
            upgrade1x.ignoreOldKeys("config.yml", LEGACY_1_0_LEFTOVER_KEYS.toArray(String[]::new));
        }
        LEGACY_OLDER_DEFAULTS_ROOTS.forEach(upgrade1x::olderDefaultsRoot);
        LegacyDataUpgrade.Report report = upgrade1x.run();
        if (!report.notNeeded()) {
            return new LegacyUpgrade(report, false);
        }
        // toggles.yml has kept its format since 1.0.
        LegacyDataUpgrade.Builder upgrade10 = LegacyDataUpgrade.builder(dataFolder, resources, logger)
                .markerKeys(LEGACY_1_0_MARKER_KEYS.toArray(String[]::new))
                .backupDirName("backup-1.x")
                .freshResources("config.yml", "leaf_mappings.yml")
                .keepFiles("toggles.yml")
                // A later 1.x run merges its keys into lang and leaf files, not config.yml.
                .olderDefaultsRoot("legacy/1.x/");
        LEGACY_OLDER_DEFAULTS_ROOTS.forEach(upgrade10::olderDefaultsRoot);
        LegacyDataUpgrade.Report from10 = upgrade10.run();
        return new LegacyUpgrade(from10, from10.performed());
    }

    // Technical errors are already logged by LegacyDataUpgrade itself.
    private void logUpgradeReport(LegacyUpgrade upgrade) {
        LegacyDataUpgrade.Report report = upgrade.report();
        if (!report.performed()) {
            return;
        }
        logUpgradeLine(upgrade.from10() ? "log.upgrade_from_1_0" : "log.upgrade_header", "plugin", getName(),
                "backup", displayPath(report.backupDir()));
        for (LegacyDataUpgrade.CarriedValue value : report.carried()) {
            logUpgradeLine("log.upgrade_kept", "old", value.oldPath(), "new", value.newPath(), "value", value.value());
        }
        for (LegacyDataUpgrade.RemovedEntry entry : report.removed()) {
            logUpgradeLine("log.upgrade_removed", "key", entry.newPath());
        }
        for (LegacyDataUpgrade.DroppedValue value : report.dropped()) {
            boolean settingGone = value.reason() == LegacyDataUpgrade.SkipReason.UNKNOWN_OLD_KEY
                    || value.reason() == LegacyDataUpgrade.SkipReason.NO_NEW_KEY;
            logUpgradeLine(settingGone ? "log.upgrade_dropped" : "log.upgrade_dropped_invalid", "key",
                    value.oldPath(), "value", value.value());
        }
        for (LegacyDataUpgrade.MissingListEntries list : report.missingListEntries()) {
            logUpgradeLine("log.upgrade_new_entries", "list", list.path(), "entries", String.join(", ", list.entries()));
        }
        for (String file : report.customizedFiles()) {
            logUpgradeLine("log.upgrade_replaced_file", "file", file);
        }
        for (String file : report.filesWithoutBaseline()) {
            // Files the jar ships were written fresh, so they aren't only in the backup.
            if (getResource(file) == null) {
                logUpgradeLine("log.upgrade_backup_only", "file", file);
            }
        }
    }

    private void logUpgradeLine(String key, String... placeholderPairs) {
        Map<String, String> placeholders = new LinkedHashMap<>();
        for (int i = 0; i + 1 < placeholderPairs.length; i += 2) {
            placeholders.put(placeholderPairs[i], String.valueOf(placeholderPairs[i + 1]));
        }
        messages.log(getLogger(), Level.INFO, key, placeholders);
    }

    // Relative to the server folder when possible, e.g.
    // plugins/Timberella/backup-1.x.
    private String displayPath(Path path) {
        Path root = getServer().getWorldContainer().toPath().toAbsolutePath().normalize();
        Path absolute = path.toAbsolutePath().normalize();
        Path shown = absolute.startsWith(root) ? root.relativize(absolute) : absolute;
        return shown.toString().replace('\\', '/');
    }

    @Override
    public void onDisable() {
        shutdownMetrics();
        cancelScheduledUpdateChecks();
        // Stops TurtleLib's HTTP client and threads, so none outlive a /reload.
        UpdateChecker.shutdown();
        if (configWatcher != null) {
            configWatcher.stop();
        }
        if (treeChopListener != null) {
            // In-progress fellings won't get to run their own cleanup tick after this.
            treeChopListener.clearActiveFellingTagsOnShutdown();
        }
        // Pending async saves are dropped on disable, so save synchronously.
        if (togglesChanged) {
            writeToggles(snapshotToggles());
        }
        if (messages == null) {
            getLogger().info("Timberella plugin disabled.");
        } else {
            getLogger().info(messages.plain("plugin.disabled"));
        }
    }

    public MessageService messages() {
        return messages;
    }

    /**
     * Reloads config.yml, lang files and leaf mappings. If config.yml isn't valid
     * YAML, nothing is reloaded or rewritten and the error is returned.
     *
     * @return {@code null} once reloaded, otherwise the YAML error in config.yml
     */
    public String reloadAndMergeConfig() {
        return reloadAndMergeConfig(false);
    }

    // external: started by the config watcher, which announces the reload in the
    // console before the list of changes.
    private String reloadAndMergeConfig(boolean external) {
        String configError = ConfigWatcher.describeYamlError(configFile());
        if (configError != null) {
            messages.log(getLogger(), Level.WARNING, "log.config_invalid_reload", Map.of("error", configError));
            if (configWatcher != null) {
                // Otherwise the watcher would report this same content once more.
                configWatcher.refreshBaseline();
            }
            return configError;
        }
        runningOnDefaults = false;
        // Rename first, then reload, so getConfig() below reflects the renamed file.
        migrateLegacyConfigKeys(configFile(), this::getResource, getLogger());
        reloadConfig();
        // Reload language after potential changes
        String lang = getConfig().getString("language", "en_US");
        messages.load(lang);
        applyChatPrefixLabel();
        // Skipped here on purpose: the server version can't change at runtime, so
        // re-checking on every reload would only repeat the warning (or, with
        // ERROR_AND_DISABLE, disable the plugin mid-reload).
        syncAllYamlDefaults();
        getLogger().info(messages.plain("plugin.language_set", Map.of("code", messages.getLanguage())));
        // Refresh listener material sets
        if (treeChopListener != null)
            treeChopListener.refresh();
        setupMetrics();
        announceNextUpdateSummary = true;
        configureUpdateChecker();
        logModuleStates();
        if (external) {
            messages.log(getLogger(), Level.INFO, "log.config_reloaded_external");
        }
        if (fileChangeTracker != null) {
            logFileChangeSummary(compareTrackedFiles());
        }
        if (configWatcher != null) {
            configWatcher.setEnabled(readConfigWatchEnabled());
            configWatcher.setIntervalSeconds(resolveConfigWatchIntervalSeconds());
            configWatcher.refreshBaseline();
        }
        return null;
    }

    // Adds keys the bundled file has but the server's copy lacks, as raw text,
    // so the admin's formatting and comments stay exactly as they were.
    private MergeResult insertMissingDefaults(String relativePath, List<String> openSections) {
        ensureResourceExists(relativePath);
        File targetFile = new File(getDataFolder(), relativePath);
        try (InputStream in = getResource(relativePath)) {
            List<String> added = ConfigDefaultsInserter.insertMissingKeys(targetFile, in, getLogger(), relativePath,
                    openSections);
            return new MergeResult(relativePath, added);
        } catch (IOException e) {
            getLogger().warning("Could not read bundled " + relativePath + ": " + e.getMessage());
            return new MergeResult(relativePath, List.of());
        }
    }

    private void syncAllYamlDefaults() {
        // A config.yml that isn't valid YAML stays byte-identical until it is fixed.
        if (!runningOnDefaults) {
            // A config.yml written afresh (it was deleted) is read back like one that
            // gained keys.
            boolean created = !configFile().exists();
            MergeResult configResult = insertMissingDefaults("config.yml", CONFIG_OPEN_SECTIONS);
            if (created || !configResult.addedKeys().isEmpty()) {
                reloadConfig();
            }
            logMergeReport(configResult);
        }
        // Likewise for leaf_mappings.yml; TreeChopListener reports it when loading.
        if (ConfigWatcher.describeYamlError(new File(getDataFolder(), "leaf_mappings.yml")) == null) {
            logMergeReport(insertMissingDefaults("leaf_mappings.yml", LEAF_MAPPINGS_OPEN_SECTIONS));
        }
        if (messages == null) {
            return;
        }
        // messages.load() already synced the active lang file; only report what it
        // added.
        logMergeReport(new MergeResult("lang/" + messages.getLanguage() + ".yml", messages.getLastAddedKeys()));
    }

    private void initializeConfigWatcher() {
        long intervalSeconds = resolveConfigWatchIntervalSeconds();
        configWatcher = ConfigWatcher.builder(this)
                .file(new File(getDataFolder(), "config.yml"))
                // Initial values only; the background poll never reads getConfig() itself
                // (not thread-safe) - reloadAndMergeConfig() pushes fresh values via
                // setEnabled(...) and setIntervalSeconds(...) instead.
                .enabled(readConfigWatchEnabled())
                .intervalSeconds(intervalSeconds)
                .onChange(() -> {
                    try {
                        reloadAndMergeConfig(true);
                    } catch (Exception ex) {
                        getLogger().log(Level.WARNING, "Config watcher callback failed", ex);
                    }
                })
                // A deleted or emptied config.yml isn't reloaded; the settings in use stay.
                .onSkipped(reason -> messages.log(getLogger(), Level.WARNING,
                        reason == ConfigWatcher.SkipReason.MISSING
                                ? "log.config_missing_external"
                                : "log.config_empty_external"))
                .build();
    }

    private long resolveConfigWatchIntervalSeconds() {
        long configured = getConfig().getLong("config_watch_interval_seconds", 5L);
        return Math.max(1L, configured);
    }

    private boolean readConfigWatchEnabled() {
        return getConfig().getBoolean("config_watch_enabled", true);
    }

    // ===== Player toggle management =====
    public boolean isEnabledFor(UUID uuid) {
        return !disabledPlayers.contains(uuid);
    }
    public void setEnabledFor(UUID uuid, boolean enabled) {
        if (enabled)
            disabledPlayers.remove(uuid);
        else
            disabledPlayers.add(uuid);
        saveToggles();
    }
    public boolean toggleEnabled(UUID uuid) {
        boolean now = !disabledPlayers.contains(uuid);
        setEnabledFor(uuid, !now);
        return !now;
    }

    private File togglesFile() {
        return new File(getDataFolder(), "toggles.yml");
    }
    private void loadToggles() {
        var f = togglesFile();
        if (!f.exists())
            return;
        Set<UUID> loaded = TogglesFile.read(f.toPath(), getLogger());
        disabledPlayers.clear();
        disabledPlayers.addAll(loaded);
    }
    private final LatestWriteGate togglesWriteGate = new LatestWriteGate();
    // Guarded by disabledPlayers, so each snapshot gets the version of its state.
    private long togglesVersion = 0L;
    // Only a toggle since startup makes onDisable write toggles.yml, so a failed
    // start never overwrites it and the file still appears only on first use.
    private volatile boolean togglesChanged = false;

    // Snapshots the UUID list on the calling thread and does the actual disk
    // write in runTaskAsynchronously, so toggling never blocks the main thread
    // on I/O.
    private void saveToggles() {
        togglesChanged = true;
        TogglesSnapshot snapshot = snapshotToggles();
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> writeToggles(snapshot));
    }

    private record TogglesSnapshot(long version, List<String> disabled) {
    }

    private TogglesSnapshot snapshotToggles() {
        List<String> list = new ArrayList<>();
        synchronized (disabledPlayers) {
            for (UUID u : disabledPlayers)
                list.add(u.toString());
            return new TogglesSnapshot(++togglesVersion, list);
        }
    }

    // Async tasks may finish in any order; the gate drops a snapshot older than
    // the one already on disk.
    private void writeToggles(TogglesSnapshot snapshot) {
        togglesWriteGate.writeIfNewer(snapshot.version(), () -> {
            try {
                TogglesFile.write(togglesFile().toPath(), snapshot.disabled(), getLogger());
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Failed to save toggles.yml", e);
            }
        });
    }

    private void logMergeReport(MergeResult result) {
        if (result == null)
            return;
        if (result.addedKeys().isEmpty())
            return;
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("file", result.fileName());
        placeholders.put("count", Integer.toString(result.addedKeys().size()));
        placeholders.put("keys", String.join(", ", result.addedKeys()));
        messages.log(getLogger(), Level.INFO, "log.defaults_updated", placeholders);
    }

    private void logModuleStates() {
        if (getConfig().getBoolean("log_stats", true)) {
            boolean timber = getConfig().getBoolean("enable_timber", true);
            boolean leaves = getConfig().getBoolean("enable_leaves_decay", true);
            boolean replantEnabled = getConfig().getBoolean("enable_replant", true);
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("timber", stateLabel(timber));
            placeholders.put("replant", stateLabel(replantEnabled));
            placeholders.put("leaves", stateLabel(leaves));
            messages.log(getLogger(), Level.INFO, "log.modules_summary", placeholders);
        }
        if (getConfig().getBoolean("log_detail", false) && treeChopListener != null) {
            treeChopListener.activeCategoryMaterials().forEach((category, names) -> logDetailList(
                    "log.detail_category_header", Map.of("category", category), names));
            logDetailList("log.detail_leaves_header", Map.of(), treeChopListener.activeLeafMappings());
            logDetailList("log.detail_axes_header", Map.of(), treeChopListener.activeAxes());
            logDetailList("log.detail_saplings_header", Map.of(), treeChopListener.activeSaplings());
        }
    }

    private void logDetailList(String headerKey, Map<String, String> placeholders, List<String> entries) {
        if (entries.isEmpty()) {
            return;
        }
        messages.log(getLogger(), Level.INFO, headerKey, placeholders);
        for (String entry : entries) {
            getLogger().info(" - " + entry);
        }
    }

    private void logStartupBanner() {
        StartupBanner.builder(this)
                .resource(STARTUP_BANNER_RESOURCE)
                .color("<light_purple>")
                .build()
                .send();
    }

    private String stateLabel(boolean flag) {
        if (messages != null) {
            return flag
                    ? messages.plain("log.modules_state_active")
                    : messages.plain("log.modules_state_inactive");
        }
        return flag ? "enabled" : "disabled";
    }

    private void setupMetrics() {
        boolean enabled = !runningOnDefaults && getConfig().getBoolean("metrics_enabled", true);
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

    private void applyChatPrefixLabel() {
        if (messages == null) {
            return;
        }
        String label = getConfig().getString("chat_prefix_label", "Timberella");
        if (label == null || label.isBlank()) {
            label = "Timberella";
        }
        messages.setPrefixLabel(label);
    }

    private void shutdownMetrics() {
        if (metrics != null) {
            metrics.shutdown();
            metrics = null;
        }
    }

    private void registerMetricsCharts(Metrics metricsInstance) {
        metricsInstance.addCustomChart(new SimplePie("language", () -> messages.getLanguage()));
        metricsInstance.addCustomChart(new SimplePie("sneak_mode", () -> switch (getConfig().getInt("sneak_mode", 0)) {
            case 1 -> "not_sneaking";
            case 2 -> "always";
            default -> "sneak_only";
        }));
        metricsInstance.addCustomChart(new SimplePie("durability_mode", () -> getConfig().getString("tools.durability_mode", "all")));
        metricsInstance.addCustomChart(new SimplePie("update_provider", () -> switch (readUpdateProvider()) {
            case 1 -> "modrinth_only";
            case 2 -> "hangar_only";
            default -> "modrinth_hangar";
        }));
        metricsInstance.addCustomChart(new AdvancedPie("enabled_modules", () -> {
            Map<String, Integer> values = new HashMap<>();
            if (getConfig().getBoolean("enable_timber", true))
                values.put("timber", 1);
            if (getConfig().getBoolean("enable_replant", true))
                values.put("replant_listener", 1);
            if (getConfig().getBoolean("enable_leaves_decay", true))
                values.put("leaves_decay", 1);
            return values;
        }));
        metricsInstance.addCustomChart(new SimplePie("config_watch", () -> getConfig().getBoolean("config_watch_enabled", true) ? "enabled" : "disabled"));
        metricsInstance.addCustomChart(new SingleLineChart("max_blocks_limit", () -> getConfig().getInt("max_blocks", 1024)));
        metricsInstance.addCustomChart(new SimplePie("player_toggle_usage", () -> {
            synchronized (disabledPlayers) {
                return disabledPlayers.isEmpty() ? "all_enabled" : "some_disabled";
            }
        }));
    }

    private void configureUpdateChecker() {
        cancelScheduledUpdateChecks();
        pendingUpdateInfo = null;
        reportedProviderFailures.clear();
        if (runningOnDefaults || !readUpdateBoolean("enabled", true)) {
            updateChecker = null;
            return;
        }
        int provider = readUpdateProvider();
        boolean includePrereleases = readUpdateBoolean("include_prereleases", false);
        boolean filterByServerVersion = readUpdateBoolean("filter_by_server_version", true);
        this.updateChecker = new UpdateChecker(this, provider, includePrereleases, filterByServerVersion,
                "timberella", "hro_basti/timberella");
        // The update summary names a failed provider with its reason, translated.
        this.updateChecker.setLogFailures(false);
        scheduleUpdateChecks();
    }

    private void scheduleUpdateChecks() {
        if (updateChecker == null) {
            return;
        }
        long hours = Math.max(1L, readUpdateLong("interval_hours", 24L));
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
        UpdateChecker checker = updateChecker;
        if (checker == null) {
            return;
        }
        checker.checkAsync()
                .thenAccept(info -> {
                    if (!isEnabled()) {
                        return;
                    }
                    // handleUpdateInfo reads config and messages, so it runs on the main
                    // thread; a result from a checker a reload has since replaced is dropped.
                    Bukkit.getScheduler().runTask(this, () -> {
                        if (checker == updateChecker) {
                            handleUpdateInfo(info);
                        }
                    });
                })
                .exceptionally(ex -> {
                    getLogger().log(Level.WARNING, "Update check failed", ex);
                    return null;
                });
    }

    private void handleUpdateInfo(UpdateChecker.UpdateInfo info) {
        if (info == null) {
            return;
        }
        boolean notifyConsole = readUpdateBoolean("notify_console", true);
        boolean alwaysShow = readUpdateBoolean("notify_console_always_shown", false);
        boolean shouldLog = notifyConsole && (info.hasUpdate() || (alwaysShow && announceNextUpdateSummary));
        if (shouldLog) {
            logUpdateSummary(info);
            announceNextUpdateSummary = false;
        }
        // Without a summary, a provider's first failure still gets one line; the
        // summary's own line counts as shown.
        List<UpdateChecker.ProviderResult> firstFailures = firstFailures(reportedProviderFailures, info.providers());
        if (notifyConsole && !shouldLog) {
            for (UpdateChecker.ProviderResult result : firstFailures) {
                messages.log(getLogger(), Level.WARNING, "log.update_provider_failed", Map.of("provider",
                        result.provider().displayName(), "error", result.errorMessage()));
            }
        }
        if (!info.hasUpdate()) {
            pendingUpdateInfo = null;
            return;
        }
        // Feeds UpdateNotifyListener's op-join chat hint (see
        // isOpJoinUpdateNotifyEnabled()/getPendingUpdateInfo()) - that's the actual
        // admin notification, not console logging above.
        pendingUpdateInfo = info;
    }

    // Marks every failed provider as shown and returns those failing for the first
    // time since the last reset; a successful fetch resets its provider.
    static List<UpdateChecker.ProviderResult> firstFailures(Set<UpdateChecker.Provider> reported,
            List<UpdateChecker.ProviderResult> results) {
        List<UpdateChecker.ProviderResult> first = new ArrayList<>();
        if (results == null) {
            return first;
        }
        for (UpdateChecker.ProviderResult result : results) {
            if (result == null) {
                continue;
            }
            if (result.success()) {
                reported.remove(result.provider());
            } else if (reported.add(result.provider())) {
                first.add(result);
            }
        }
        return first;
    }

    public UpdateChecker.UpdateInfo getPendingUpdateInfo() {
        return pendingUpdateInfo;
    }

    public boolean isOpJoinUpdateNotifyEnabled() {
        return readUpdateBoolean("notify_op_join", true);
    }

    public boolean isConsoleUpdateNotifyEnabled() {
        return readUpdateBoolean("notify_console", true);
    }

    private void logUpdateSummary(UpdateChecker.UpdateInfo info) {
        if (info == null)
            return;
        List<UpdateChecker.ProviderResult> providers = info.providers() == null ? Collections.emptyList() : info.providers();
        boolean hasProviderDetails = !providers.isEmpty();
        boolean shouldBracketLog = info.hasUpdate() || hasProviderDetails;

        if (shouldBracketLog) {
            logDivider();
        }

        if (info.hasUpdate()) {
            messages.log(getLogger(), Level.INFO, "log.update_found", Map.of("current", info.currentVersion()));
        } else {
            messages.log(getLogger(), Level.INFO, "log.update_none");
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
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("provider", result.provider().displayName());
        if (!result.success()) {
            placeholders.put("error", result.errorMessage());
            messages.log(getLogger(), Level.INFO, "log.update_provider_error", placeholders);
            return;
        }
        if (result.latestVersion() == null) {
            messages.log(getLogger(), Level.INFO, "log.update_provider_none", placeholders);
            return;
        }
        placeholders.put("version", result.latestVersion());
        placeholders.put("url", result.url());
        messages.log(getLogger(), Level.INFO, "log.update_provider_ok", placeholders);
    }

    private void setupServerMatcher() {
        if (messages == null) {
            serverMatcher = null;
            return;
        }
        serverMatcher = ServerMatcher.builder(this)
                .allowMinorSeries(SUPPORTED_MINOR_SERIES)
                .incompatibleAction(INCOMPATIBLE_SERVER_ACTION)
                .onMismatch(this::handleServerMismatch)
                .build();
        serverMatcher.enforce();
    }

    private void handleServerMismatch(ServerMatcher.MatchResult result) {
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("required_server", REQUIRED_SERVER_BRAND);
        placeholders.put("supported_versions", SUPPORTED_VERSION_LABEL);
        placeholders.put("server_name", result.serverName() != null ? result.serverName() : "unknown");
        placeholders.put("mc_version", result.minecraftVersion() != null ? result.minecraftVersion() : "unknown");

        if (messages != null) {
            // The logger already prefixes the plugin name, so the message has no <prefix>.
            messages.log(getLogger(), Level.WARNING, "warn.unsupported_server_version", placeholders);
        } else {
            getLogger().warning("Timberella officially supports " + REQUIRED_SERVER_BRAND
                    + " " + SUPPORTED_VERSION_LABEL + ". Detected "
                    + result.serverName() + " " + result.minecraftVersion() + '.');
        }
    }

    private ConfigurationSection updateSettings() {
        return getConfig().getConfigurationSection("update_check");
    }

    private boolean readUpdateBoolean(String childKey, boolean defaultValue) {
        ConfigurationSection section = updateSettings();
        if (section != null && section.contains(childKey)) {
            return section.getBoolean(childKey, defaultValue);
        }
        return defaultValue;
    }

    // UpdateChecker queries no provider outside 0..2, so clamp to that range.
    private int readUpdateProvider() {
        return Math.max(0, Math.min(2, readUpdateInt("provider", 0)));
    }

    private int readUpdateInt(String childKey, int defaultValue) {
        ConfigurationSection section = updateSettings();
        if (section != null && section.contains(childKey)) {
            return section.getInt(childKey, defaultValue);
        }
        return defaultValue;
    }

    private long readUpdateLong(String childKey, long defaultValue) {
        ConfigurationSection section = updateSettings();
        if (section != null && section.contains(childKey)) {
            return section.getLong(childKey, defaultValue);
        }
        return defaultValue;
    }

    private FileChangeTracker.Changes compareTrackedFiles() {
        return compareTrackedFiles(fileChangeTracker, leafMappingsTracker,
                new File(getDataFolder(), "leaf_mappings.yml"));
    }

    // While leaf_mappings.yml isn't valid YAML it is left out, so its last readable
    // version stays the baseline and the loader's warning isn't repeated here.
    // Package-private for LeafMappingsChangesTest.
    static FileChangeTracker.Changes compareTrackedFiles(FileChangeTracker files, FileChangeTracker leafMappings,
            File leafMappingsFile) {
        FileChangeTracker.Changes changes = files.compareAndUpdate();
        if (ConfigWatcher.describeYamlError(leafMappingsFile) != null) {
            return changes;
        }
        List<FileChangeTracker.FileKeyChanges> keyChanges = new ArrayList<>(changes.keyChanges());
        keyChanges.addAll(leafMappings.compareAndUpdate().keyChanges());
        return new FileChangeTracker.Changes(changes.firstSnapshot(), keyChanges, changes.changedFiles(),
                changes.addedFiles(), changes.removedFiles(), changes.unreadableFiles());
    }

    private void logFileChangeSummary(FileChangeTracker.Changes changes) {
        if (changes.firstSnapshot()) {
            return;
        }
        if (changes.isEmpty()) {
            messages.log(getLogger(), Level.INFO, "log.reload_changes_none");
            return;
        }
        // Only unreadable files: the tracker's own warning says so.
        if (!changes.hasListedChanges()) {
            return;
        }
        messages.log(getLogger(), Level.INFO, "log.reload_changes_header");
        for (FileChangeTracker.FileKeyChanges fileChanges : changes.keyChanges()) {
            if (fileChanges.changes().isEmpty())
                continue;
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("file", fileChanges.file());
            placeholders.put("changes", fileChanges.describe());
            messages.log(getLogger(), Level.INFO, "log.reload_changes_line", placeholders);
        }
        for (String file : changes.allChangedFiles()) {
            messages.log(getLogger(), Level.INFO, "log.reload_changes_locale_line", Map.of("file", file));
        }
    }

    private void ensureResourceExists(String relativePath) {
        File out = new File(getDataFolder(), relativePath);
        File parent = out.getParentFile();
        if (parent != null && !parent.exists()) {
            // noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        if (!out.exists()) {
            saveResource(relativePath, false);
            getLogger().info("Created default file: " + relativePath);
        }
    }

}
