package net.kroet.timberella;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import net.kroet.turtlelib.helper.ConfigKeyMigrator;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every language file shipped with 1.x must, after the legacy key rename, only
 * contain keys the current bundled file of the same locale also has.
 */
class LegacyLangMigrationTest {

    // Keys dropped on purpose because nothing uses them anymore.
    private static final Set<String> REMOVED_KEYS = Set.of("update.failed", "log.defaults-none");

    @TempDir
    Path tempDir;

    private static List<String> legacyLocales() throws IOException, URISyntaxException {
        URL dir = LegacyLangMigrationTest.class.getResource("/legacy/1.x/lang");
        assertNotNull(dir, "legacy/1.x/lang not found on the test classpath");
        try (Stream<Path> files = Files.list(Path.of(dir.toURI()))) {
            return files.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".yml"))
                    .map(name -> name.substring(0, name.length() - ".yml".length()))
                    .sorted()
                    .toList();
        }
    }

    private static YamlConfiguration loadResource(String path) throws IOException {
        try (InputStream in = LegacyLangMigrationTest.class.getResourceAsStream(path)) {
            assertNotNull(in, path + " not found on the test classpath");
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    @Test
    void every1xLocaleMigratesCompletely() throws IOException, URISyntaxException {
        List<String> locales = legacyLocales();
        assertTrue(locales.size() >= 14, "expected all bundled 1.x locales, found " + locales);
        List<String> orphaned = new ArrayList<>();
        for (String locale : locales) {
            Path file = tempDir.resolve(locale + ".yml");
            try (InputStream in = LegacyLangMigrationTest.class
                    .getResourceAsStream("/legacy/1.x/lang/" + locale + ".yml")) {
                Files.copy(in, file);
            }
            ConfigKeyMigrator.rewriteLegacyKeysInFile(file.toFile(), null, locale + ".yml",
                    TimberellaPlugin.LEGACY_LANG_KEY_MIGRATIONS);

            YamlConfiguration migrated = YamlConfiguration.loadConfiguration(file.toFile());
            YamlConfiguration current = loadResource("/lang/" + locale + ".yml");
            migrated.getKeys(true).stream()
                    .filter(key -> !migrated.isConfigurationSection(key))
                    .filter(key -> !current.isSet(key))
                    .filter(key -> !REMOVED_KEYS.contains(key))
                    .forEach(key -> orphaned.add(locale + ": " + key));
        }
        assertEquals(List.of(), orphaned, "keys from the 1.x language files without a current counterpart");
    }
}
