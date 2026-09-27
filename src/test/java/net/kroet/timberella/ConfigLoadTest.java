package net.kroet.timberella;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.function.Supplier;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A missing or broken config.yml still gives the bundled tree categories, which
 * a section that only exists in the defaults would not.
 */
class ConfigLoadTest {
    private static final Supplier<InputStream> BUNDLED = () -> ConfigLoadTest.class.getResourceAsStream("/config.yml");

    @TempDir
    Path dir;

    @Test
    void missingConfigUsesTheBundledCategories() {
        assertBundledCategories(load(dir.resolve("config.yml").toFile()));
    }

    // The broken file itself stays exactly as it is.
    @Test
    void invalidConfigUsesTheBundledCategories() throws IOException {
        String broken = "categories:\n  logs:\n    OAK_LOG: [true\n";
        Path file = Files.writeString(dir.resolve("config.yml"), broken);

        assertBundledCategories(load(file.toFile()));
        assertEquals(broken, Files.readString(file));
    }

    @Test
    void validConfigKeepsItsOwnValuesOverTheDefaults() throws IOException {
        Path file = Files.writeString(dir.resolve("config.yml"), "categories:\n  logs:\n    OAK_LOG: false\n");

        YamlConfiguration config = load(file.toFile());

        assertEquals(Set.of("OAK_LOG"), config.getConfigurationSection("categories.logs").getKeys(false));
        assertEquals(false, config.getBoolean("categories.logs.OAK_LOG"));
        assertTrue(config.getStringList("replant.saplings").contains("OAK_SAPLING"));
    }

    private static YamlConfiguration load(File file) {
        return TimberellaPlugin.loadConfig(file, BUNDLED, Logger.getAnonymousLogger());
    }

    private static void assertBundledCategories(YamlConfiguration config) {
        YamlConfiguration bundled = load(new File("does-not-exist"));
        ConfigurationSection categories = config.getConfigurationSection("categories");
        assertTrue(categories.getConfigurationSection("logs").getBoolean("OAK_LOG"));
        assertEquals(bundledCategories(), categories.getValues(true).toString());
        assertTrue(config.getStringList("replant.saplings").contains("OAK_SAPLING"));
        assertTrue(bundled.getConfigurationSection("categories").getKeys(false).size() > 1);
    }

    private static String bundledCategories() {
        try (InputStream in = BUNDLED.get()) {
            YamlConfiguration bundled = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            return bundled.getConfigurationSection("categories").getValues(true).toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
