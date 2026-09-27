package net.kroet.timberella;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import net.kroet.turtlelib.helper.FileChangeTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The change summary after a reload while leaf_mappings.yml isn't valid YAML:
 * no warning of its own, and once fixed only what differs from the last valid
 * version is listed.
 */
class LeafMappingsChangesTest {
    private static final String VALID = "log_to_leaves:\n  OAK_LOG:\n    - OAK_LEAVES\n  BIRCH_LOG:\n    - BIRCH_LEAVES\n";
    private static final String BROKEN = "log_to_leaves:\n  OAK_LOG: [OAK_LEAVES\n";

    @TempDir
    Path dir;

    private final List<LogRecord> warnings = new ArrayList<>();
    private FileChangeTracker files;
    private FileChangeTracker leafMappings;

    @BeforeEach
    void setUp() throws IOException {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                warnings.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        Files.writeString(dir.resolve("config.yml"), "language: en_US\n");
        files = FileChangeTracker.builder(dir.toFile(), logger).yamlFile("config.yml").build();
        leafMappings = FileChangeTracker.builder(dir.toFile(), logger).yamlFile("leaf_mappings.yml").build();
    }

    @Test
    void brokenFileIsSkippedAndTheLastValidVersionStaysTheBaseline() throws IOException {
        write(VALID);
        compare();
        write(BROKEN);

        assertEquals(List.of(), compare().keyChanges());

        write(VALID.replace("BIRCH_LEAVES", "OAK_LEAVES"));
        FileChangeTracker.Changes fixed = compare();

        assertEquals(1, fixed.keyChanges().size());
        assertEquals(List.of(new FileChangeTracker.KeyChange("log_to_leaves.BIRCH_LOG", "OAK_LEAVES")),
                fixed.keyChanges().get(0).changes());
        assertEquals(List.of(), warnings);
    }

    // Broken from the start: fixing it lists nothing rather than every entry.
    @Test
    void fileBrokenAtStartupIsNotListedWholeOnceFixed() throws IOException {
        write(BROKEN);
        compare();
        write(VALID);

        assertEquals(List.of(), compare().keyChanges());
        assertEquals(List.of(), warnings);
    }

    private FileChangeTracker.Changes compare() {
        return TimberellaPlugin.compareTrackedFiles(files, leafMappings, dir.resolve("leaf_mappings.yml").toFile());
    }

    private void write(String content) throws IOException {
        Files.writeString(dir.resolve("leaf_mappings.yml"), content);
    }
}
