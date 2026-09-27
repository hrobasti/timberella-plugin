package net.kroet.timberella.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

/**
 * Every tree species must replant its own sapling from each of its log and wood
 * materials; only giant mushrooms have none.
 */
class SpeciesSaplingTest {

    private static final Set<TreeChopListener.Species> WITHOUT_SAPLING = EnumSet
            .of(TreeChopListener.Species.MUSHROOM_BROWN, TreeChopListener.Species.MUSHROOM_RED);

    @Test
    void everyTreeSpeciesHasASapling() {
        for (TreeChopListener.Species species : TreeChopListener.Species.values()) {
            if (WITHOUT_SAPLING.contains(species)) {
                assertNull(species.sapling(), species + " should not replant");
            } else {
                assertNotNull(species.sapling(), species + " has no sapling to replant");
            }
        }
    }

    @Test
    void everyPoplarMaterialReplantsAPoplarSapling() {
        Map<Material, Material> mappings = TreeChopListener.defaultSaplingMappings();
        for (Material log : EnumSet.of(Material.POPLAR_LOG, Material.STRIPPED_POPLAR_LOG, Material.POPLAR_WOOD,
                Material.STRIPPED_POPLAR_WOOD)) {
            assertEquals(Material.POPLAR_SAPLING, mappings.get(log), log.name());
        }
    }

    @Test
    void mangroveRootsReplantAPropagule() {
        Map<Material, Material> mappings = TreeChopListener.defaultSaplingMappings();
        assertEquals(Material.MANGROVE_PROPAGULE, mappings.get(Material.MANGROVE_ROOTS));
        assertEquals(Material.MANGROVE_PROPAGULE, mappings.get(Material.MUDDY_MANGROVE_ROOTS));
    }
}
