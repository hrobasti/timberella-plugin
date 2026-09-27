package net.kroet.timberella.util;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * toggles.yml, the players who switched Timberella off. It is replaced in one
 * step, so a crash while saving never leaves it cut off, and a file that can't
 * be read completely is kept as toggles.yml.broken before it is written anew.
 */
public final class TogglesFile {

    private TogglesFile() {
    }

    /** The players listed as disabled; never throws. */
    public static Set<UUID> read(Path file, Logger logger) {
        Set<UUID> disabled = new LinkedHashSet<>();
        if (!Files.isRegularFile(file)) {
            return disabled;
        }
        String problem;
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(file.toFile());
            if (yaml.contains("disabled") && !yaml.isList("disabled")) {
                problem = "'disabled' isn't a list";
            } else {
                int invalid = 0;
                for (String entry : yaml.getStringList("disabled")) {
                    try {
                        disabled.add(UUID.fromString(entry.trim()));
                    } catch (IllegalArgumentException e) {
                        invalid++;
                    }
                }
                if (invalid == 0) {
                    return disabled;
                }
                problem = invalid + (invalid == 1 ? " entry isn't" : " entries aren't") + " a player UUID";
            }
        } catch (IOException | InvalidConfigurationException e) {
            problem = Objects.requireNonNullElse(e.getMessage(), e.toString()).lines().findFirst().orElse("");
        }
        Path backup = backup(file, logger);
        logger.warning("toggles.yml can't be read completely (" + problem + ")"
                + (backup == null ? "" : "; kept it as " + backup.getFileName())
                + ". Players listed only there have Timberella on again.");
        return disabled;
    }

    /** Replaces the file with this list in one step. */
    public static void write(Path file, List<String> disabled, Logger logger) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("disabled", disabled);
        byte[] bytes = yaml.saveToString().getBytes(StandardCharsets.UTF_8);
        Path target = file.toAbsolutePath();
        Files.createDirectories(target.getParent());
        Path temp = target.resolveSibling("." + target.getFileName() + ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            try {
                moveIntoPlace(temp, target);
            } catch (FileSystemException e) {
                // e.g. Windows refuses the replace while an editor holds the file
                Files.write(target, bytes);
                logger.warning("Could not replace toggles.yml in one step (" + e + "); wrote it in place instead");
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static void moveIntoPlace(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // toggles.yml.broken, or the first free toggles.yml.broken-N; null if the copy
    // fails.
    private static Path backup(Path file, Logger logger) {
        Path backup = file.resolveSibling(file.getFileName() + ".broken");
        for (int n = 2; Files.exists(backup); n++) {
            backup = file.resolveSibling(file.getFileName() + ".broken-" + n);
        }
        try {
            return Files.copy(file, backup);
        } catch (IOException e) {
            logger.warning("Could not keep a copy of toggles.yml: " + e.getMessage());
            return null;
        }
    }
}
