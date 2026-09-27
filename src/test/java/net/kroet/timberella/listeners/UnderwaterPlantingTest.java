package net.kroet.timberella.listeners;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.bukkit.Material;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class UnderwaterPlantingTest {

    // Like UnderwaterTrees' bundled config.yml, its loaded config's defaults.
    private static final String BUNDLED = """
            require_water_above: false
            soils:
                DIRT: true
                MUD: true
                SAND: false
            saplings:
                OAK_SAPLING: true
                CHERRY_SAPLING: true
                MANGROVE_PROPAGULE: false
            """;

    private static UnderwaterPlanting rules(String file) {
        try {
            YamlConfiguration config = new YamlConfiguration();
            config.loadFromString(file);
            YamlConfiguration defaults = new YamlConfiguration();
            defaults.loadFromString(BUNDLED);
            config.setDefaults(defaults);
            return UnderwaterPlanting.fromConfig(config);
        } catch (InvalidConfigurationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void plantsAnEnabledSaplingOnAnEnabledSoilIntoWater() {
        UnderwaterPlanting rules = rules(BUNDLED);

        assertTrue(rules.accepts(Material.OAK_SAPLING, Material.DIRT, Material.WATER));
        assertTrue(rules.accepts(Material.CHERRY_SAPLING, Material.MUD, Material.WATER));
    }

    @Test
    void disabledOrUnlistedSaplingsAndSoilsStayOut() {
        UnderwaterPlanting rules = rules(BUNDLED);

        assertFalse(rules.accepts(Material.MANGROVE_PROPAGULE, Material.DIRT, Material.WATER));
        assertFalse(rules.accepts(Material.BIRCH_SAPLING, Material.DIRT, Material.WATER));
        assertFalse(rules.accepts(Material.OAK_SAPLING, Material.SAND, Material.WATER));
        assertFalse(rules.accepts(Material.OAK_SAPLING, Material.GRASS_BLOCK, Material.WATER));
    }

    @Test
    void onlyWaterIsAPlaceForIt() {
        UnderwaterPlanting rules = rules(BUNDLED);

        assertFalse(rules.accepts(Material.OAK_SAPLING, Material.DIRT, Material.AIR));
        assertFalse(rules.accepts(Material.OAK_SAPLING, Material.DIRT, Material.LAVA));
    }

    @Test
    void bubbleColumnCountsUnlessWaterAboveIsRequired() {
        assertTrue(rules(BUNDLED).accepts(Material.OAK_SAPLING, Material.DIRT, Material.BUBBLE_COLUMN));

        UnderwaterPlanting strict = rules(BUNDLED.replace("require_water_above: false", "require_water_above: true"));
        assertFalse(strict.accepts(Material.OAK_SAPLING, Material.DIRT, Material.BUBBLE_COLUMN));
        assertTrue(strict.accepts(Material.OAK_SAPLING, Material.DIRT, Material.WATER));
    }

    // UnderwaterTrees uses its bundled defaults for a section that is missing or
    // enables nothing, even the entries switched off there.
    @Test
    void aSectionEnablingNothingFallsBackToTheBundledDefaults() {
        UnderwaterPlanting rules = rules("""
                saplings:
                    OAK_SAPLING: false
                """);

        assertTrue(rules.accepts(Material.OAK_SAPLING, Material.MUD, Material.WATER));
        assertTrue(rules.accepts(Material.CHERRY_SAPLING, Material.DIRT, Material.WATER));
        assertFalse(rules.accepts(Material.OAK_SAPLING, Material.SAND, Material.WATER));
    }

    @Test
    void entriesAreMatchedByMaterialNameLikeUnderwaterTreesDoes() {
        UnderwaterPlanting rules = rules("""
                soils:
                    dirt: true
                    NO_SUCH_SOIL: true
                saplings:
                    minecraft:birch_sapling: true
                """);

        assertTrue(rules.accepts(Material.BIRCH_SAPLING, Material.DIRT, Material.WATER));
        assertFalse(rules.accepts(Material.OAK_SAPLING, Material.DIRT, Material.WATER));
        assertFalse(rules.accepts(Material.BIRCH_SAPLING, Material.MUD, Material.WATER));
    }

    // A trunk's foot fills with water before the leaves around the block above it
    // are gone; planted then, the sapling would wash out.
    @Test
    void saplingInWaterWaitsForWaterAbove() {
        UnderwaterPlanting rules = rules(BUNDLED);
        List<Material> sides = List.of(Material.WATER, Material.WATER, Material.OAK_LEAVES, Material.WATER);

        assertFalse(rules.isSettled(Material.OAK_SAPLING, Material.DIRT, Material.WATER, Material.AIR, sides));
        assertTrue(rules.isSettled(Material.OAK_SAPLING, Material.DIRT, Material.WATER, Material.WATER, sides));
        assertTrue(rules.isSettled(Material.OAK_SAPLING, Material.DIRT, Material.WATER, Material.BUBBLE_COLUMN,
                sides));

        UnderwaterPlanting strict = rules(BUNDLED.replace("require_water_above: false", "require_water_above: true"));
        assertFalse(strict.isSettled(Material.OAK_SAPLING, Material.DIRT, Material.WATER, Material.BUBBLE_COLUMN,
                sides));
    }

    @Test
    void dryFootWaitsWhileWaterFlowsIn() {
        UnderwaterPlanting rules = rules(BUNDLED);
        List<Material> dry = List.of(Material.STONE, Material.AIR, Material.OAK_LEAVES, Material.AIR);

        assertTrue(rules.isSettled(Material.OAK_SAPLING, Material.DIRT, Material.AIR, Material.AIR, dry));
        assertFalse(rules.isSettled(Material.OAK_SAPLING, Material.DIRT, Material.AIR, Material.WATER, dry));
        assertFalse(rules.isSettled(Material.OAK_SAPLING, Material.DIRT, Material.AIR, Material.AIR,
                List.of(Material.STONE, Material.BUBBLE_COLUMN, Material.AIR, Material.AIR)));
    }

    // Nothing is worth waiting for where UnderwaterTrees wouldn't plant anyway.
    @Test
    void saplingItWouldNotPlantUnderwaterNeverWaits() {
        UnderwaterPlanting rules = rules(BUNDLED);
        List<Material> wet = List.of(Material.WATER, Material.WATER, Material.WATER, Material.WATER);

        assertTrue(rules.isSettled(Material.BIRCH_SAPLING, Material.DIRT, Material.WATER, Material.AIR, wet));
        assertTrue(rules.isSettled(Material.OAK_SAPLING, Material.SAND, Material.WATER, Material.AIR, wet));
        assertTrue(rules.isSettled(Material.MANGROVE_PROPAGULE, Material.MUD, Material.WATER, Material.AIR, wet));

        UnderwaterPlanting strict = rules(BUNDLED.replace("require_water_above: false", "require_water_above: true"));
        assertTrue(strict.isSettled(Material.OAK_SAPLING, Material.DIRT, Material.BUBBLE_COLUMN, Material.AIR, wet));
    }
}
