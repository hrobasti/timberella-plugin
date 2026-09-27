package net.kroet.timberella.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.kroet.timberella.listeners.TreeChopListener.Species;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

class SpeciesDetectionTest {
    private final Map<List<Integer>, Material> world = new HashMap<>();

    // Vanilla doubles the stem of one in twelve giant mushrooms, up to twelve high.
    @Test
    void tallMushroomStemFindsTheCapOnItsTop() {
        for (int y = 0; y < 12; y++) {
            set(0, y, 0, Material.MUSHROOM_STEM);
        }
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                set(x, 12, z, Material.BROWN_MUSHROOM_BLOCK);
            }
        }

        assertEquals(Species.MUSHROOM_BROWN, detect(0, 0, 0));
    }

    @Test
    void stemWithoutACapHasNoSpecies() {
        for (int y = 0; y < 5; y++) {
            set(0, y, 0, Material.MUSHROOM_STEM);
        }

        assertNull(detect(0, 0, 0));
    }

    @Test
    void attachmentsTakeTheSpeciesOfTheTreeNextToThem() {
        set(0, 1, 0, Material.BIRCH_LOG);
        set(0, 0, 0, Material.BEE_NEST);
        set(5, 0, 0, Material.PALE_OAK_LOG);
        set(5, 1, 0, Material.CREAKING_HEART);
        set(5, 2, 0, Material.PALE_OAK_LOG);

        assertEquals(Species.BIRCH, detect(0, 0, 0));
        assertEquals(Species.PALE_OAK, detect(5, 1, 0));
    }

    // A face neighbour wins over a diagonal one, and diagonals only count if
    // include_diagonals is on.
    @Test
    void faceNeighboursComeFirst() {
        set(0, 0, 0, Material.OAK_FENCE);
        set(1, 1, 0, Material.DARK_OAK_LOG);

        assertEquals(Species.DARK_OAK, detect(0, 0, 0));
        assertNull(TreeChopListener.detectSpecies(0, 0, 0, 320, false, this::typeAt, this::isTree));

        set(0, 1, 0, Material.SPRUCE_LOG);
        assertEquals(Species.SPRUCE, detect(0, 0, 0));
    }

    @Test
    void attachmentWithoutATreeNextToItHasNoSpecies() {
        set(0, 0, 0, Material.BEE_NEST);
        set(0, 1, 0, Material.OAK_FENCE);

        assertNull(detect(0, 0, 0));
    }

    private Species detect(int x, int y, int z) {
        return TreeChopListener.detectSpecies(x, y, z, 320, true, this::typeAt, this::isTree);
    }

    private boolean isTree(Material type) {
        return type != Material.AIR;
    }

    private Material typeAt(int x, int y, int z) {
        return world.getOrDefault(List.of(x, y, z), Material.AIR);
    }

    private void set(int x, int y, int z, Material type) {
        world.put(List.of(x, y, z), type);
    }
}
