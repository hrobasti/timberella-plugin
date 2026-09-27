package net.kroet.timberella.listeners;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.NamespacedKey;
import org.junit.jupiter.api.Test;

class SpawnProtectionTest {

    private static final NamespacedKey OVERWORLD = NamespacedKey.minecraft("overworld");
    private static final NamespacedKey NETHER = NamespacedKey.minecraft("the_nether");

    @Test
    void edgeOfRadiusIsStillProtected() {
        assertTrue(TreeChopListener.isWithinSpawnProtection(OVERWORLD, 16, -16, OVERWORLD, 0, 0, 16));
    }

    @Test
    void oneBlockBeyondRadiusOnEitherAxisIsFree() {
        assertFalse(TreeChopListener.isWithinSpawnProtection(OVERWORLD, 17, 0, OVERWORLD, 0, 0, 16));
        assertFalse(TreeChopListener.isWithinSpawnProtection(OVERWORLD, 0, -17, OVERWORLD, 0, 0, 16));
    }

    @Test
    void distanceIsMeasuredFromWorldSpawnNotOrigin() {
        assertTrue(TreeChopListener.isWithinSpawnProtection(OVERWORLD, 110, 205, OVERWORLD, 100, 200, 16));
        assertFalse(TreeChopListener.isWithinSpawnProtection(OVERWORLD, 0, 0, OVERWORLD, 100, 200, 16));
    }

    // The world spawn can lie in any dimension; only that one is protected.
    @Test
    void onlyTheWorldHoldingTheSpawnIsProtected() {
        assertTrue(TreeChopListener.isWithinSpawnProtection(NETHER, 5, 5, NETHER, 0, 0, 16));
        assertFalse(TreeChopListener.isWithinSpawnProtection(OVERWORLD, 5, 5, NETHER, 0, 0, 16));
        assertFalse(TreeChopListener.isWithinSpawnProtection(NETHER, 5, 5, OVERWORLD, 0, 0, 16));
    }

    // Like vanilla, the world border keeps ops out too, while spawn protection
    // leaves them alone.
    @Test
    void blocksOutsideTheWorldBorderAreOffLimitsLikeSpawnProtection() {
        assertTrue(TreeChopListener.mayInteract(true, false));
        assertFalse(TreeChopListener.mayInteract(false, false));
        assertFalse(TreeChopListener.mayInteract(true, true));
        assertFalse(TreeChopListener.mayInteract(false, true));
    }
}
