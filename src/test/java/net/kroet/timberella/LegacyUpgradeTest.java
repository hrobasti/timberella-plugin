package net.kroet.timberella;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.kroet.turtlelib.helper.LegacyDataUpgrade;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The one-time upgrade against real release files: a 1.0.x folder gets fresh
 * files without carried values, and a folder that ran 1.0.x and then 1.1+ is
 * upgraded from its 1.1+ keys while the 1.0.x leftovers are skipped silently.
 * Only files holding a value no release shipped are reported as customized.
 */
class LegacyUpgradeTest {
    private static final String TOGGLES = "disabled:\n- 069a79f4-44e9-4726-a5be-fca90e38aaf5\n";

    @TempDir
    Path dataDir;

    @ParameterizedTest
    @ValueSource(strings = {"1.0", "1.0.1"})
    void release10FolderGetsFreshFilesAndNothingCarriedOver(String release) throws IOException {
        byte[] oldConfig = read("/release-1.0.x/config-" + release + ".yml");
        String changed = new String(oldConfig, StandardCharsets.UTF_8).replace("check_updates: true",
                "check_updates: false");
        write("config.yml", changed.getBytes(StandardCharsets.UTF_8));
        write("toggles.yml", TOGGLES.getBytes(StandardCharsets.UTF_8));
        write("lang/de_DE.yml", "command:\n  reloaded: Eigener Text\n".getBytes(StandardCharsets.UTF_8));

        TimberellaPlugin.LegacyUpgrade upgrade = upgrade();

        LegacyDataUpgrade.Report report = upgrade.report();
        assertTrue(report.performed());
        assertTrue(upgrade.from10());
        assertEquals(List.of(), report.carried());
        assertEquals(List.of(), report.dropped());
        assertEquals(changed, Files.readString(dataDir.resolve("backup-1.x/config.yml")));
        assertArrayEquals(read("/config.yml"), Files.readAllBytes(dataDir.resolve("config.yml")));
        assertArrayEquals(read("/leaf_mappings.yml"), Files.readAllBytes(dataDir.resolve("leaf_mappings.yml")));
        assertEquals(TOGGLES, Files.readString(dataDir.resolve("toggles.yml")));
        assertTrue(Files.exists(dataDir.resolve("backup-1.x/lang/de_DE.yml")));
        assertFalse(Files.exists(dataDir.resolve("lang")));
        assertEquals(Set.of("config.yml", "lang/de_DE.yml"), Set.copyOf(report.customizedFiles()));
    }

    // legacy/1.0.1 holds that release's files verbatim.
    @ParameterizedTest
    @ValueSource(strings = {"1.0", "1.0.1"})
    void untouched10FolderReportsNoCustomizedFiles(String release) throws IOException {
        String files = release.equals("1.0") ? "/release-1.0.x/" : "/legacy/1.0.1/";
        write("config.yml", read("/release-1.0.x/config-" + release + ".yml"));
        write("lang/en_US.yml", read(files + (release.equals("1.0") ? "lang-en_US-1.0.yml" : "lang/en_US.yml")));
        write("leaf_mappings.yml",
                read(files + (release.equals("1.0") ? "leaf_mappings-1.0.yml" : "leaf_mappings.yml")));

        TimberellaPlugin.LegacyUpgrade upgrade = upgrade();

        assertTrue(upgrade.from10());
        assertEquals(List.of(), upgrade.report().customizedFiles());
        assertEquals(List.of(), upgrade.report().filesWithoutBaseline());
    }

    // 1.1.0 and 1.1.1 lang files lack a key 1.2 added, and 1.1.0 to 1.2.0 shipped
    // shorter lists for the nether stems in leaf_mappings.yml.
    @Test
    void untouchedFilesOfOlder1xReleasesAreNotReported() throws IOException {
        write("config.yml", read("/legacy/1.x/config.yml"));
        write("lang/en_US.yml", read("/release-1.1.x/lang-en_US-1.1.0.yml"));
        write("leaf_mappings.yml", read("/release-1.1.x/leaf_mappings-1.2.0.yml"));

        LegacyDataUpgrade.Report report = upgrade().report();

        assertTrue(report.performed());
        assertEquals(List.of(), report.customizedFiles());
        assertEquals(List.of(), report.filesWithoutBaseline());
    }

    @Test
    void editedFilesOfOlder1xReleasesAreStillReported() throws IOException {
        write("config.yml", read("/legacy/1.x/config.yml"));
        write("lang/en_US.yml",
                edited("/release-1.1.x/lang-en_US-1.1.0.yml", "Timberella plugin enabled.", "Timberella is on."));
        write("leaf_mappings.yml", withoutLine("/release-1.1.x/leaf_mappings-1.2.0.yml", "    - RED_MUSHROOM_BLOCK"));

        LegacyDataUpgrade.Report report = upgrade().report();

        assertEquals(Set.of("lang/en_US.yml", "leaf_mappings.yml"), Set.copyOf(report.customizedFiles()));
    }

    // A 1.0 folder that ran 1.2.2 once: 1.2.2 merged its keys into the lang and
    // leaf files, but added no 1.1+ key to config.yml, so it stays a 1.0 folder.
    @Test
    void untouched10FolderThatRan12IsNotReported() throws IOException, InvalidConfigurationException {
        write("config.yml", read("/release-1.0.x/config-1.0.yml"));
        write("lang/en_US.yml", merged("/release-1.0.x/lang-en_US-1.0.yml", "/legacy/1.x/lang/en_US.yml"));
        write("leaf_mappings.yml", merged("/release-1.0.x/leaf_mappings-1.0.yml", "/legacy/1.x/leaf_mappings.yml"));

        TimberellaPlugin.LegacyUpgrade upgrade = upgrade();

        assertTrue(upgrade.from10());
        assertEquals(List.of(), upgrade.report().customizedFiles());
        assertEquals(List.of(), upgrade.report().filesWithoutBaseline());
    }

    // A folder that ran 1.0 and then 1.1+ keeps the 1.0 values, with the keys
    // that 1.1+ added merged in next to them.
    @Test
    void untouchedFilesFromAChainOfReleasesAreNotReported() throws IOException, InvalidConfigurationException {
        write("config.yml", read("/legacy/1.x/config.yml"));
        write("lang/en_US.yml", merged("/release-1.0.x/lang-en_US-1.0.yml", "/legacy/1.x/lang/en_US.yml"));
        write("leaf_mappings.yml", merged("/release-1.0.x/leaf_mappings-1.0.yml", "/legacy/1.x/leaf_mappings.yml"));

        assertEquals(List.of(), upgrade().report().customizedFiles());
    }

    // A 1.0.1 file to which the 1.1+ keys were added by hand while 1.1-1.2.x ran
    // (those releases never write missing keys into config.yml), so the 1.0 keys
    // stayed next to them.
    @Test
    void mixedFolderCarries1xValuesAndSkips10Leftovers() throws IOException, InvalidConfigurationException {
        YamlConfiguration mixed = yaml("/release-1.0.x/config-1.0.1.yml");
        mixed.set("enable_timber", false);
        mixed.set("sneak_mode", 1);
        mixed.set("check_updates", false);
        mixed.set("tools.allowed_axes", List.of("WOODEN_AXE"));
        mixed.set("species_limits.oak.max_horizontal_radius", 99);
        mixed.set("categories.stripped_logs.STRIPPED_OAK_LOG", true);
        mixed.set("leaves_decay.decay_radius", 9);
        YamlConfiguration release122 = yaml("/legacy/1.x/config.yml");
        for (String key : release122.getKeys(true)) {
            if (!release122.isConfigurationSection(key) && !mixed.isSet(key)) {
                mixed.set(key, release122.get(key));
            }
        }
        mixed.set("sneak-mode", 2);
        mixed.set("update-check.enabled", false);
        write("config.yml", mixed.saveToString().getBytes(StandardCharsets.UTF_8));

        TimberellaPlugin.LegacyUpgrade upgrade = upgrade();

        LegacyDataUpgrade.Report report = upgrade.report();
        assertTrue(report.performed());
        assertFalse(upgrade.from10());
        YamlConfiguration result = YamlConfiguration.loadConfiguration(dataDir.resolve("config.yml").toFile());
        YamlConfiguration fresh = yaml("/config.yml");
        assertEquals(2, result.getInt("sneak_mode"));
        assertFalse(result.getBoolean("update_check.enabled"));
        assertTrue(result.getBoolean("enable_timber"));
        for (String key : List.of("tools.allowed_axes", "species_limits.oak.max_horizontal_radius",
                "categories.stripped_logs.STRIPPED_OAK_LOG", "leaves_decay.decay_radius")) {
            assertEquals(fresh.get(key), result.get(key), key);
        }
        Stream.concat(report.carried().stream().map(LegacyDataUpgrade.CarriedValue::oldPath),
                report.dropped().stream().map(LegacyDataUpgrade.DroppedValue::oldPath))
                .forEach(path -> assertFalse(isLeftover(path), path + " was carried over or reported"));
        assertTrue(report.carried().stream().anyMatch(value -> value.oldPath().equals("sneak-mode")));
    }

    // An old 1.x name pasted into a 2.0 config.yml: every changed 2.0 value stays,
    // and the old name gives way to the 2.0 key next to it.
    @Test
    void newFileWithAnOldKeyAddedKeepsItsOwnValues() throws IOException, InvalidConfigurationException {
        YamlConfiguration current = yaml("/config.yml");
        current.set("max_blocks", 500);
        current.set("tools.durability_mode", "first");
        current.set("enable_replant", false);
        current.set("chat_prefix_label", "Holz");
        current.set("leaves_decay.decay_radius", 3);
        write("config.yml", (current.saveToString() + "sneak-mode: 2\n").getBytes(StandardCharsets.UTF_8));

        TimberellaPlugin.LegacyUpgrade upgrade = upgrade();

        assertTrue(upgrade.report().performed());
        YamlConfiguration result = YamlConfiguration.loadConfiguration(dataDir.resolve("config.yml").toFile());
        assertEquals(500, result.getInt("max_blocks"));
        assertEquals("first", result.getString("tools.durability_mode"));
        assertFalse(result.getBoolean("enable_replant"));
        assertEquals("Holz", result.getString("chat_prefix_label"));
        assertEquals(3, result.getInt("leaves_decay.decay_radius"));
        assertEquals(0, result.getInt("sneak_mode"));
        List<String> carried = report(upgrade);
        assertTrue(carried.containsAll(List.of("max_blocks", "tools.durability_mode", "enable_replant",
                "chat_prefix_label", "leaves_decay.decay_radius")), carried.toString());
        assertTrue(upgrade.report().dropped().isEmpty(), upgrade.report().dropped().toString());
    }

    @Test
    void newOnlyKeysAreNoneOfTheOlderReleases() throws IOException, InvalidConfigurationException {
        YamlConfiguration current = yaml("/config.yml");
        for (String key : TimberellaPlugin.CONFIG_2_0_ONLY_KEYS) {
            assertTrue(current.contains(key), key);
            for (String release : List.of("/legacy/1.0/config.yml", "/legacy/1.0.1/config.yml",
                    "/legacy/1.x/config.yml")) {
                assertFalse(yaml(release).contains(key) || yaml(release).contains(key.replace('_', '-')),
                        key + " in " + release);
            }
        }
    }

    private static List<String> report(TimberellaPlugin.LegacyUpgrade upgrade) {
        return upgrade.report().carried().stream().map(LegacyDataUpgrade.CarriedValue::newPath).toList();
    }

    @Test
    void currentFolderIsLeftAlone() throws IOException {
        write("config.yml", read("/config.yml"));

        assertTrue(upgrade().report().notNeeded());
        assertFalse(Files.exists(dataDir.resolve("backup-1.x")));
    }

    // The leftover list must cover every key only 1.0.x had, and no key 1.1+ read.
    @Test
    void leftoverKeysMatchTheReleaseFiles() throws IOException, InvalidConfigurationException {
        YamlConfiguration release122 = yaml("/legacy/1.x/config.yml");
        for (String release : List.of("1.0", "1.0.1")) {
            YamlConfiguration release10 = yaml("/release-1.0.x/config-" + release + ".yml");
            for (String key : release10.getKeys(true)) {
                if (!release10.isConfigurationSection(key)) {
                    assertEquals(!release122.isSet(key), isLeftover(key), release + ": " + key);
                }
            }
            for (String marker : TimberellaPlugin.LEGACY_1_0_MARKER_KEYS) {
                assertTrue(release10.isSet(marker), release + " lacks " + marker);
            }
        }
        YamlConfiguration current = yaml("/config.yml");
        for (String key : release122.getKeys(true)) {
            assertFalse(isLeftover(key), "1.2.2 key " + key + " is on the leftover list");
        }
        for (String marker : TimberellaPlugin.LEGACY_1_0_MARKER_KEYS) {
            assertFalse(release122.contains(marker) || current.contains(marker), marker);
        }
    }

    private static boolean isLeftover(String path) {
        return TimberellaPlugin.LEGACY_1_0_LEFTOVER_KEYS.stream().anyMatch(entry -> entry.endsWith(".*")
                ? path.startsWith(entry.substring(0, entry.length() - 1))
                : path.equals(entry));
    }

    private TimberellaPlugin.LegacyUpgrade upgrade() {
        return TimberellaPlugin.upgradeLegacyDataFolder(dataDir.toFile(),
                path -> LegacyUpgradeTest.class.getResourceAsStream("/" + path), null);
    }

    private static byte[] read(String resource) throws IOException {
        try (InputStream in = LegacyUpgradeTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, resource + " not found on the test classpath");
            return in.readAllBytes();
        }
    }

    private static YamlConfiguration yaml(String resource) throws IOException, InvalidConfigurationException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(new String(read(resource), StandardCharsets.UTF_8));
        return yaml;
    }

    private static byte[] edited(String resource, String from, String to) throws IOException {
        String text = new String(read(resource), StandardCharsets.UTF_8);
        assertTrue(text.contains(from), resource + " lacks " + from);
        return text.replace(from, to).getBytes(StandardCharsets.UTF_8);
    }

    // Removes the first such line, whatever line ending the file uses.
    private static byte[] withoutLine(String resource, String line) throws IOException {
        String text = new String(read(resource), StandardCharsets.UTF_8);
        String edited = text.replaceFirst("(?m)^" + Pattern.quote(line) + "\\R", "");
        assertNotEquals(text, edited, resource + " lacks " + line);
        return edited.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] merged(String older, String newer) throws IOException, InvalidConfigurationException {
        YamlConfiguration file = yaml(older);
        YamlConfiguration added = yaml(newer);
        for (String key : added.getKeys(true)) {
            if (!added.isConfigurationSection(key) && !file.isSet(key)) {
                file.set(key, added.get(key));
            }
        }
        return file.saveToString().getBytes(StandardCharsets.UTF_8);
    }

    private void write(String path, byte[] content) throws IOException {
        Path target = dataDir.resolve(path);
        Files.createDirectories(target.getParent());
        Files.write(target, content);
    }
}
