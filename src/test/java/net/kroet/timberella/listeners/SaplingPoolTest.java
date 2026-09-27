package net.kroet.timberella.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.EnumSet;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

/**
 * A drops-based replant keeps back only matching saplings, and never more than
 * one per planting spot.
 */
class SaplingPoolTest {

    @Test
    void keepsOnlyTheSaplingTypeOfTheTree() {
        TreeChopListener.SaplingPool pool = new TreeChopListener.SaplingPool(Material.OAK_SAPLING, 1);

        assertEquals(0, pool.reserve(Material.STICK, 2));
        assertEquals(0, pool.reserve(Material.BIRCH_SAPLING, 1));
        assertEquals(0, pool.collected());
    }

    @Test
    void stopsOnceEverySpotHasASapling() {
        TreeChopListener.SaplingPool pool = new TreeChopListener.SaplingPool(Material.DARK_OAK_SAPLING, 4);

        assertEquals(1, pool.reserve(Material.DARK_OAK_SAPLING, 1));
        assertEquals(2, pool.reserve(Material.DARK_OAK_SAPLING, 2));
        assertEquals(1, pool.reserve(Material.DARK_OAK_SAPLING, 3));
        assertEquals(0, pool.reserve(Material.DARK_OAK_SAPLING, 1));
        assertEquals(4, pool.collected());
    }

    // An azalea tree drops either kind of azalea; only the first kind kept is
    // planted, never a mix.
    @Test
    void azaleaTreeKeepsTheFirstKindOfAzaleaItDrops() {
        TreeChopListener.SaplingPool pool = new TreeChopListener.SaplingPool(
                EnumSet.of(Material.AZALEA, Material.FLOWERING_AZALEA), 2);

        assertEquals(1, pool.reserve(Material.FLOWERING_AZALEA, 1));
        assertEquals(0, pool.reserve(Material.AZALEA, 1));
        assertEquals(1, pool.reserve(Material.FLOWERING_AZALEA, 3));
        assertEquals(Material.FLOWERING_AZALEA, pool.kept());
    }

    @Test
    void azaleaLeavesOutnumberingOakLeavesMakeItAnAzaleaTree() {
        assertNull(TreeChopListener.azaleaSapling(10, 3, 2));
        assertNull(TreeChopListener.azaleaSapling(5, 3, 2));
        assertEquals(Material.AZALEA, TreeChopListener.azaleaSapling(2, 20, 5));
        assertEquals(Material.AZALEA, TreeChopListener.azaleaSapling(0, 4, 4));
        assertEquals(Material.FLOWERING_AZALEA, TreeChopListener.azaleaSapling(0, 5, 12));
    }
}
