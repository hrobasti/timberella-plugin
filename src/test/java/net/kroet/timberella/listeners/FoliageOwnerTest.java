package net.kroet.timberella.listeners;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.kroet.timberella.listeners.TreeShape.Pos;
import org.junit.jupiter.api.Test;

/**
 * Wart blocks, shroomlights and vines go with the felled fungus only where no
 * standing stem is as near to them.
 */
class FoliageOwnerTest {

    // Two planted fungi four blocks apart; the one at x = 0 is felled.
    @Test
    void neighborFungusKeepsItsCap() {
        List<Pos> felled = column(0, 4);
        List<Pos> standing = column(4, 7);

        assertTrue(kept(3, 5, 0, felled, standing));
        assertTrue(kept(4, 7, 0, felled, standing));
        assertTrue(kept(3, 7, 1, felled, standing));
        assertTrue(kept(2, 2, 0, felled, standing), "halfway between stays with the standing stem");
        assertTrue(kept(2, 1, -2, felled, standing));
        assertFalse(kept(1, 2, 0, felled, standing));
        assertFalse(kept(0, 4, 0, felled, standing));
        assertFalse(kept(-2, 0, 0, felled, standing));
    }

    // A tall felled fungus beside a short one: the cap high above the short stem
    // is the tall one's, the short one's own cap stays.
    @Test
    void capAboveAShortNeighborGoesWithTheTallStem() {
        List<Pos> felled = column(0, 13);
        List<Pos> standing = column(4, 4);

        assertFalse(kept(3, 10, 0, felled, standing));
        assertFalse(kept(3, 13, 1, felled, standing));
        assertTrue(kept(4, 4, 0, felled, standing));
        assertTrue(kept(3, 3, -1, felled, standing));
    }

    @Test
    void loneFungusLosesItsWholeCap() {
        List<Pos> felled = column(0, 4);

        assertFalse(kept(2, 0, 2, felled, List.of()));
        assertFalse(kept(0, 4, 0, felled, List.of()));
    }

    private static boolean kept(int x, int y, int z, List<Pos> felled, List<Pos> standing) {
        return TreeChopListener.keptByStandingLog(new Pos(x, y, z), felled, standing);
    }

    private static List<Pos> column(int x, int height) {
        List<Pos> stem = new ArrayList<>();
        for (int y = 0; y < height; y++) {
            stem.add(new Pos(x, y, 0));
        }
        return stem;
    }
}
