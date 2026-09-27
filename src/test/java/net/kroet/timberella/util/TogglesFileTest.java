package net.kroet.timberella.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * toggles.yml is replaced in one step, and a file that can't be read completely
 * is kept aside before it is written anew.
 */
class TogglesFileTest {
    private static final String A = "069a79f4-44e9-4726-a5be-fca90e38aaf5";
    private static final String B = "853c80ef-3c37-49fd-aa49-938b674adae6";

    @TempDir
    Path dir;

    private final List<String> warnings = new ArrayList<>();

    @Test
    void writtenListReadsBackWithoutATempFileLeft() throws IOException {
        Path file = dir.resolve("toggles.yml");
        Files.writeString(file, "disabled:\n- " + A + "\n");

        TogglesFile.write(file, List.of(A, B), logger());

        assertEquals(Set.of(UUID.fromString(A), UUID.fromString(B)), TogglesFile.read(file, logger()));
        try (var files = Files.list(dir)) {
            assertEquals(List.of(file), files.toList());
        }
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void fileThatIsNoValidYamlIsKeptAsBroken() throws IOException {
        Path file = dir.resolve("toggles.yml");
        String broken = "disabled: [" + A + "\n";
        Files.writeString(file, broken);

        assertTrue(TogglesFile.read(file, logger()).isEmpty());
        assertEquals(broken, Files.readString(dir.resolve("toggles.yml.broken")));
        assertEquals(broken, Files.readString(file));
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("toggles.yml.broken"), warnings.get(0));
    }

    // A cut-off last line: the players before it stay, the file is kept aside.
    @Test
    void entryThatIsNoUuidKeepsTheOthersAndTheFile() throws IOException {
        Path file = dir.resolve("toggles.yml");
        Files.writeString(file, "disabled:\n- " + A + "\n- 853c80ef-3c37\n");
        Files.writeString(dir.resolve("toggles.yml.broken"), "older");

        assertEquals(Set.of(UUID.fromString(A)), TogglesFile.read(file, logger()));
        assertTrue(Files.exists(dir.resolve("toggles.yml.broken-2")));
        assertEquals("older", Files.readString(dir.resolve("toggles.yml.broken")));
        assertTrue(warnings.get(0).contains("1 entry isn't a player UUID"), warnings.get(0));
    }

    @Test
    void missingFileIsNobodyDisabled() {
        assertTrue(TogglesFile.read(dir.resolve("toggles.yml"), logger()).isEmpty());
        assertFalse(Files.exists(dir.resolve("toggles.yml.broken")));
    }

    private Logger logger() {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord logRecord) {
                warnings.add(logRecord.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        return logger;
    }
}
