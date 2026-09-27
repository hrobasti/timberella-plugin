package net.kroet.timberella.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * Every {@code Species} enum constant must have a matching
 * {@code species_limits} section in {@code config.yml}, under its exact
 * {@link TreeChopListener.Species#configKey()}, with matching default values.
 * Every tree wood's fence must be listed in {@code categories.fences}.
 */
class SpeciesConfigConsistencyTest {

    private static YamlConfiguration loadBundledConfig() {
        try (InputStream in = SpeciesConfigConsistencyTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(in, "config.yml not found on the test classpath");
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load bundled config.yml", e);
        }
    }

    @Test
    void everySpeciesHasAMatchingSectionWithConsistentDefaults() {
        YamlConfiguration config = loadBundledConfig();
        ConfigurationSection speciesLimits = config.getConfigurationSection("species_limits");
        assertNotNull(speciesLimits, "config.yml is missing the species_limits section");

        for (TreeChopListener.Species species : TreeChopListener.Species.values()) {
            ConfigurationSection section = speciesLimits.getConfigurationSection(species.configKey());
            assertNotNull(section,
                    "config.yml has no species_limits." + species.configKey() + " section for " + species
                            + " (exact key match required)");

            assertEquals(species.defaultEnabled(), section.getBoolean("enabled"),
                    species + ": enabled mismatch between code default and config.yml");
            assertEquals(species.defaultMaxBlocks(), section.getInt("max_blocks"),
                    species + ": max_blocks mismatch between code default and config.yml");
            assertEquals(species.defaultHorizontalRadius(), section.getInt("max_horizontal_radius"),
                    species + ": max_horizontal_radius mismatch between code default and config.yml");
            assertEquals(species.defaultVerticalRadius(), section.getInt("max_vertical_radius"),
                    species + ": max_vertical_radius mismatch between code default and config.yml");
        }
    }

    @Test
    void everyTreeWoodFenceIsListed() {
        ConfigurationSection fences = loadBundledConfig().getConfigurationSection("categories.fences");
        assertNotNull(fences, "config.yml is missing the categories.fences section");

        for (Material material : Material.values()) {
            String name = material.name();
            if (name.startsWith("LEGACY_") || !name.endsWith("_FENCE")) {
                continue;
            }
            String wood = name.substring(0, name.length() - "_FENCE".length());
            if (Material.getMaterial(wood + "_LOG") != null || Material.getMaterial(wood + "_STEM") != null) {
                assertTrue(fences.contains(name), name + " is missing from categories.fences");
            }
        }
        for (String name : fences.getKeys(false)) {
            assertNotNull(Material.getMaterial(name), "categories.fences lists unknown material " + name);
        }
    }

    // Mangrove roots follow categories.logs like every other material, so the
    // bundled file must switch them on for the trunk's roots to fall with it.
    @Test
    void mangroveRootsAreOnByDefault() {
        assertTrue(loadBundledConfig().getBoolean("categories.logs.MANGROVE_ROOTS"));
    }

    @Test
    void azaleasAreReplantedByDefault() {
        List<String> saplings = loadBundledConfig().getStringList("replant.saplings");

        assertTrue(saplings.containsAll(List.of("AZALEA", "FLOWERING_AZALEA")), saplings.toString());
    }
}
