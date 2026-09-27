package net.kroet.timberella.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;
import net.kroet.turtlelib.helper.ConfigWatcher;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LeafMappingsFileTest {
    private static final Supplier<InputStream> BUNDLED = () -> LeafMappingsFileTest.class
            .getResourceAsStream("/leaf_mappings.yml");

    @TempDir
    Path dir;

    @Test
    void validFileIsLoaded() throws IOException {
        File file = write("log_to_leaves:\n  BIRCH_LOG:\n    - BIRCH_LEAVES\n");

        YamlConfiguration yaml = TreeChopListener.leafMappingsSource(file, ConfigWatcher.describeYamlError(file), true,
                BUNDLED);

        assertEquals(List.of("BIRCH_LEAVES"), yaml.getStringList("log_to_leaves.BIRCH_LOG"));
    }

    // A reload keeps the mappings already loaded, a start uses the bundled ones,
    // and the broken file stays exactly as it is.
    @Test
    void invalidFileKeepsTheLoadedMappingsOrUsesTheBundledOnes() throws IOException {
        String broken = "log_to_leaves:\n  OAK_LOG: [OAK_LEAVES\n  BIRCH_LOG:\n    - BIRCH_LEAVES\n";
        File file = write(broken);
        String error = ConfigWatcher.describeYamlError(file);

        assertNotNull(error);
        assertTrue(error.contains("line"), error);
        assertNull(TreeChopListener.leafMappingsSource(file, error, true, BUNDLED));
        YamlConfiguration atStartup = TreeChopListener.leafMappingsSource(file, error, false, BUNDLED);
        assertTrue(atStartup.getStringList("log_to_leaves.OAK_LOG").contains("OAK_LEAVES"));
        assertTrue(atStartup.getStringList("log_to_leaves.CRIMSON_STEM").contains("NETHER_WART_BLOCK"));
        assertEquals(broken, Files.readString(file.toPath()));
    }

    private File write(String content) throws IOException {
        Path file = dir.resolve("leaf_mappings.yml");
        Files.writeString(file, content);
        return file.toFile();
    }

    // Azalea trees grow oak logs, so their leaves are cleared with oak.
    @Test
    void bundledMappingsClearAzaleaLeavesWithOak() throws IOException {
        YamlConfiguration bundled;
        try (InputStream in = BUNDLED.get()) {
            bundled = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }

        for (String log : List.of("OAK_LOG", "STRIPPED_OAK_LOG", "OAK_WOOD", "STRIPPED_OAK_WOOD")) {
            assertTrue(bundled.getStringList("log_to_leaves." + log)
                    .containsAll(List.of("OAK_LEAVES", "AZALEA_LEAVES", "FLOWERING_AZALEA_LEAVES")), log);
        }
    }

    // A jungle bush grows oak leaves around a single jungle log.
    @Test
    void bundledMappingsClearOakLeavesWithJungle() throws IOException {
        YamlConfiguration bundled;
        try (InputStream in = BUNDLED.get()) {
            bundled = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }

        for (String log : List.of("JUNGLE_LOG", "STRIPPED_JUNGLE_LOG", "JUNGLE_WOOD", "STRIPPED_JUNGLE_WOOD")) {
            assertTrue(bundled.getStringList("log_to_leaves." + log).containsAll(List.of("JUNGLE_LEAVES", "OAK_LEAVES")),
                    log);
        }
    }
}
