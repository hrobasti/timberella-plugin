package net.kroet.timberella.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.kroet.timberella.listeners.TreeChopListener.LeafNeighbor;
import org.junit.jupiter.api.Test;

/**
 * leaves_decay.max_distance is a hard cap on how far leaf cleanup reaches
 * around felled logs; below it, the felled species' radius decides. Leaves only
 * count as held by logs that aren't part of a build.
 */
class LeafDistanceTest {

    @Test
    void defaultCapKeepsEachSpeciesRadius() {
        assertEquals(4, TreeChopListener.effectiveLeafDistance(9, 2)); // birch: at least 4
        assertEquals(6, TreeChopListener.effectiveLeafDistance(9, 6)); // oak
        assertEquals(9, TreeChopListener.effectiveLeafDistance(9, 9)); // cherry, poplar, mangrove
        assertEquals(4, TreeChopListener.effectiveLeafDistance(9, 0)); // no species limit
    }

    @Test
    void lowerMaxDistanceIsAHardCap() {
        assertEquals(2, TreeChopListener.effectiveLeafDistance(2, 9));
        assertEquals(2, TreeChopListener.effectiveLeafDistance(2, 0));
    }

    @Test
    void higherMaxDistanceDoesNotWidenBeyondTheSpecies() {
        assertEquals(6, TreeChopListener.effectiveLeafDistance(12, 6));
    }

    // Leaves in a row from x = 0, then the given block.
    private static boolean isHeld(int leavesInRow, LeafNeighbor end) {
        return TreeChopListener.isHeldWithoutBuilds(0, 0, 0, (x, y, z) -> y != 0 || z != 0 || x < 0
                ? LeafNeighbor.OTHER
                : x < leavesInRow ? LeafNeighbor.LEAF : x == leavesInRow ? end : LeafNeighbor.OTHER);
    }

    @Test
    void leafUpToSixStepsFromALogIsHeldLikeInVanilla() {
        assertTrue(isHeld(1, LeafNeighbor.LOG));
        assertTrue(isHeld(6, LeafNeighbor.LOG));
        assertFalse(isHeld(7, LeafNeighbor.LOG));
    }

    @Test
    void buildLogsHoldNoLeaves() {
        assertFalse(isHeld(1, LeafNeighbor.BUILD_LOG));
        assertFalse(isHeld(3, LeafNeighbor.BUILD_LOG));
    }

    @Test
    void logBehindABuildLogIsOutOfReach() {
        assertFalse(TreeChopListener.isHeldWithoutBuilds(0, 0, 0, (x, y, z) -> y != 0 || z != 0
                ? LeafNeighbor.OTHER
                : x == 1 ? LeafNeighbor.BUILD_LOG : x == 2 ? LeafNeighbor.LOG : LeafNeighbor.OTHER));
    }

    @Test
    void leavesAroundABuildLogStillReachALog() {
        // Build log at x = 1; leaves lead around it over y = 1 to the log at x = 2.
        assertTrue(TreeChopListener.isHeldWithoutBuilds(0, 0, 0, (x, y, z) -> {
            if (z != 0)
                return LeafNeighbor.OTHER;
            if (y == 0)
                return x == 1 ? LeafNeighbor.BUILD_LOG : x == 2 ? LeafNeighbor.LOG : LeafNeighbor.OTHER;
            return y == 1 && x >= 0 && x <= 2 ? LeafNeighbor.LEAF : LeafNeighbor.OTHER;
        }));
    }
}
