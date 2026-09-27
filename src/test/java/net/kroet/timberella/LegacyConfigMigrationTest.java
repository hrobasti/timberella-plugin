package net.kroet.timberella;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import net.kroet.turtlelib.helper.ConfigKeyMigrator;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The config.yml shipped with 1.x must, after the legacy key rename, only
 * contain keys the current bundled config.yml also has; any leftover key would
 * be silently shadowed by a freshly merged default.
 */
class LegacyConfigMigrationTest {

    @TempDir
    Path tempDir;

    private static InputStream resource(String path) {
        InputStream in = LegacyConfigMigrationTest.class.getResourceAsStream(path);
        assertNotNull(in, path + " not found on the test classpath");
        return in;
    }

    @Test
    void release1xConfigMigratesCompletely() throws IOException {
        Path file = tempDir.resolve("config.yml");
        try (InputStream in = resource("/legacy/1.x/config.yml")) {
            Files.copy(in, file);
        }
        ConfigKeyMigrator.rewriteLegacyKeysInFile(file.toFile(), null, "config.yml",
                TimberellaPlugin.LEGACY_CONFIG_KEY_MIGRATIONS);

        YamlConfiguration migrated = YamlConfiguration.loadConfiguration(file.toFile());
        YamlConfiguration current;
        try (InputStream in = resource("/config.yml")) {
            current = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }

        List<String> orphaned = migrated.getKeys(true).stream()
                .filter(key -> !migrated.isConfigurationSection(key))
                .filter(key -> !current.isSet(key))
                .toList();
        assertEquals(List.of(), orphaned, "keys from the 1.x config.yml without a current counterpart");
    }

    @Test
    void markerKeysOnlyExistIn1xConfig() throws IOException {
        YamlConfiguration legacy;
        YamlConfiguration current;
        try (InputStream in = resource("/legacy/1.x/config.yml")) {
            legacy = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        try (InputStream in = resource("/config.yml")) {
            current = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        for (String marker : TimberellaPlugin.LEGACY_1X_MARKER_KEYS) {
            assertTrue(legacy.contains(marker), marker + " missing from the 1.x config.yml");
            assertFalse(current.contains(marker), marker + " also exists in the current config.yml");
        }
    }

    // Running 1.2.x again adds its old keys back next to the renamed ones, with
    // their defaults; back on the current version they go without a warning.
    @Test
    void keysAddedBackByA12DowngradeGoQuietly() throws IOException, InvalidConfigurationException {
        YamlConfiguration current = new YamlConfiguration();
        try (InputStream in = resource("/config.yml")) {
            current.loadFromString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        YamlConfiguration release12;
        try (InputStream in = resource("/legacy/1.x/config.yml")) {
            release12 = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        YamlConfiguration roundTrip = new YamlConfiguration();
        roundTrip.loadFromString(current.saveToString());
        for (String key : release12.getKeys(true)) {
            if (!release12.isConfigurationSection(key) && !roundTrip.isSet(key)) {
                roundTrip.set(key, release12.get(key));
            }
        }
        Path file = tempDir.resolve("config.yml");
        Files.writeString(file, roundTrip.saveToString());
        List<LogRecord> warnings = new ArrayList<>();
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    warnings.add(record);
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });

        TimberellaPlugin.migrateLegacyConfigKeys(file.toFile(), path -> resource("/" + path), logger);

        YamlConfiguration migrated = YamlConfiguration.loadConfiguration(file.toFile());
        assertEquals(List.of(), warnings.stream().map(LogRecord::getMessage).toList());
        for (String key : migrated.getKeys(true)) {
            if (!migrated.isConfigurationSection(key)) {
                assertEquals(current.get(key), migrated.get(key), key);
            }
        }
    }
}
