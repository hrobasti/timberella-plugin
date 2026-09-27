package net.kroet.timberella.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.kroet.timberella.listeners.TreeShape.Pos;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

/**
 * The outer cap of a huge fungus goes with its felled stem, even where nothing
 * connects it to the rest of the cap.
 */
class FungusCapRimTest {
    private static final Set<Pos> BOT_RIM = Set.of(
            new Pos(-2, 0, 2), new Pos(-2, 1, 2), new Pos(-1, 1, -2), new Pos(0, 0, 2),
            new Pos(0, 1, 2), new Pos(2, 0, -2), new Pos(2, 1, -2), new Pos(2, 1, 1));

    private final Map<List<Integer>, Material> world = new HashMap<>();

    // A planted warped fungus with a four-block stem: the cap narrows to one block
    // around the stem from its third row up, and the rim below hangs on its own.
    @Test
    void shortStemTakesItsHangingRim() {
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                set(x, -1, z, Material.WARPED_NYLIUM);
            }
        }
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                set(x, 4, z, Material.WARPED_WART_BLOCK);
                if (x != 0 || z != 0)
                    set(x, 3, z, Material.WARPED_WART_BLOCK);
            }
        }
        set(1, 4, 1, Material.SHROOMLIGHT);
        BOT_RIM.forEach(pos -> set(pos.x(), pos.y(), pos.z(), Material.WARPED_WART_BLOCK));

        for (Pos pos : BOT_RIM) {
            assertFalse(touchesCapAbove(pos), pos + " touches the cap, so the leaf cleanup would reach it anyway");
        }
        assertEquals(BOT_RIM, rimOf(stem(0, 0, 4, Material.WARPED_STEM)));
    }

    // Only the cap block of the felled kind, two blocks out and level with a felled
    // stem block; mushroom blocks and anything farther out or higher up stay.
    @Test
    void onlyTheCapBlockOfTheFelledKindCounts() {
        set(2, 0, 0, Material.WARPED_WART_BLOCK);
        set(-2, 0, 0, Material.NETHER_WART_BLOCK);
        set(0, 1, 2, Material.RED_MUSHROOM_BLOCK);
        set(0, 1, -3, Material.NETHER_WART_BLOCK);
        set(2, 4, 0, Material.NETHER_WART_BLOCK);
        set(-2, -1, 0, Material.NETHER_WART_BLOCK);

        assertEquals(Set.of(new Pos(-2, 0, 0)), rimOf(stem(0, 0, 4, Material.CRIMSON_STEM)));
        assertEquals(Set.of(), rimOf(stem(0, 0, 4, Material.CRIMSON_HYPHAE)));
        assertEquals(Set.of(), rimOf(stem(0, 0, 4, Material.OAK_LOG)));
    }

    // A hat of nine rows on a twelve-block stem reaches three blocks out in rows 3
    // to 6; a wart block three out at the stem's foot is no part of it.
    @Test
    void tallHatTakesItsWideLowestRows() {
        for (int y = 1; y <= 7; y++) {
            set(-3, y, 0, Material.WARPED_WART_BLOCK);
        }
        set(-2, 1, 2, Material.WARPED_WART_BLOCK);
        set(3, 6, 3, Material.SHROOMLIGHT);
        set(3, 5, -3, Material.SHROOMLIGHT);

        assertEquals(Set.of(new Pos(-3, 3, 0), new Pos(-3, 4, 0), new Pos(-3, 5, 0), new Pos(-3, 6, 0),
                new Pos(3, 6, 3)), rimOf(stem(0, 0, 12, Material.WARPED_STEM)));
        assertEquals(Set.of(), rimOf(stem(0, 0, 11, Material.WARPED_STEM)), "an 11-block stem's hat has 8 rows");
    }

    // A stem doubled to 24 blocks has a hat of up to 13 rows, so ring 3 reaches
    // from row 11 to row 18.
    @Test
    void doubledStemTakesRingThreeHigherUp() {
        for (int y = 9; y <= 20; y++) {
            set(0, y, 3, Material.WARPED_WART_BLOCK);
        }

        Set<Pos> expected = new HashSet<>();
        for (int y = 11; y <= 18; y++) {
            expected.add(new Pos(0, y, 3));
        }
        assertEquals(expected, rimOf(stem(0, 0, 24, Material.WARPED_STEM)));
    }

    // Shroomlights never sit in a hat's three lowest rows, so on a four-block stem
    // only the top row's can be one.
    @Test
    void shortStemTakesAShroomlightOnlyAtItsTop() {
        set(2, 3, -2, Material.SHROOMLIGHT);
        set(-2, 2, 0, Material.SHROOMLIGHT);
        set(3, 3, 0, Material.SHROOMLIGHT);

        assertEquals(Set.of(new Pos(2, 3, -2)), rimOf(stem(0, 0, 4, Material.CRIMSON_STEM)));
    }

    // An extra large fungus: a 3x3 stem of 24 whose corner columns grew only here
    // and there; its cap corners lie four out in the four lowest rows (11 to 14),
    // three out above.
    @Test
    void extraLargeFungusTakesTheCapCornersBesideMissingStemCorners() {
        set(4, 12, 4, Material.WARPED_WART_BLOCK);
        set(-4, 14, -4, Material.WARPED_WART_BLOCK);
        set(3, 20, -3, Material.WARPED_WART_BLOCK);
        set(-3, 23, 3, Material.WARPED_WART_BLOCK);
        Set<Pos> corners = Set.of(new Pos(4, 12, 4), new Pos(-4, 14, -4), new Pos(3, 20, -3), new Pos(-3, 23, 3));

        Map<Pos, Material> withoutCorners = hugeStem(24, false);
        Map<Pos, Material> oneCornerBlock = hugeStem(24, false);
        oneCornerBlock.put(new Pos(1, 12, 1), Material.WARPED_STEM);

        assertEquals(corners, rimOf(withoutCorners));
        assertEquals(corners, rimOf(oneCornerBlock));
        assertEquals(corners, rimOf(hugeStem(24, true)));
    }

    // A single stem or a stem wall has no corners to fill in.
    @Test
    void onlyAColumnWithTwoFelledNeighboursHasCorners() {
        set(4, 12, 4, Material.WARPED_WART_BLOCK);
        Map<Pos, Material> wall = new LinkedHashMap<>(stem(0, 0, 24, Material.WARPED_STEM));
        wall.putAll(stem(1, 0, 24, Material.WARPED_STEM));

        assertEquals(Set.of(), rimOf(stem(0, 0, 24, Material.WARPED_STEM)));
        assertEquals(Set.of(), rimOf(wall));
    }

    private static Map<Pos, Material> hugeStem(int height, boolean corners) {
        Map<Pos, Material> stem = new LinkedHashMap<>();
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                if (corners || x == 0 || z == 0) {
                    stem.putAll(stem(x, z, height, Material.WARPED_STEM));
                }
            }
        }
        return stem;
    }

    private Set<Pos> rimOf(Map<Pos, Material> felled) {
        return TreeChopListener.capRim(felled, this::typeAt);
    }

    // Felled stem blocks are air by the time the leaves are cleaned up.
    private static Map<Pos, Material> stem(int x, int z, int height, Material type) {
        Map<Pos, Material> stem = new LinkedHashMap<>();
        for (int y = 0; y < height; y++) {
            stem.put(new Pos(x, y, z), type);
        }
        return stem;
    }

    private boolean touchesCapAbove(Pos pos) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    Pos n = new Pos(pos.x() + dx, pos.y() + dy, pos.z() + dz);
                    if (!BOT_RIM.contains(n) && typeAt(n.x(), n.y(), n.z()) != Material.AIR
                            && typeAt(n.x(), n.y(), n.z()) != Material.WARPED_NYLIUM)
                        return true;
                }
            }
        }
        return false;
    }

    private Material typeAt(int x, int y, int z) {
        return world.getOrDefault(List.of(x, y, z), Material.AIR);
    }

    private void set(int x, int y, int z, Material type) {
        world.put(List.of(x, y, z), type);
    }
}
