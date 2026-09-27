package net.kroet.timberella.listeners;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

class TrunkSoilTest {

    @Test
    void grownTrunksStandOnDirtLikeBlocksOrNylium() {
        for (Material soil : List.of(Material.DIRT, Material.COARSE_DIRT, Material.PODZOL, Material.ROOTED_DIRT,
                Material.MOSS_BLOCK, Material.MUD, Material.CRIMSON_NYLIUM, Material.WARPED_NYLIUM)) {
            assertTrue(TreeChopListener.isTrunkSoil(soil), soil.name());
        }
    }

    // A growing tree turns grass and mycelium under its trunk into dirt, so a log
    // resting on them, or on stone or sand, was never a trunk's base.
    @Test
    void grassStoneAndTheLikeCarryNoGrownTrunk() {
        for (Material ground : List.of(Material.GRASS_BLOCK, Material.MYCELIUM, Material.STONE, Material.SAND,
                Material.FARMLAND, Material.NETHERRACK)) {
            assertFalse(TreeChopListener.isTrunkSoil(ground), ground.name());
        }
    }
}
