package net.kroet.timberella.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.kroet.timberella.listeners.BeeNests.Outcome;
import org.junit.jupiter.api.Test;

/**
 * A nest felled with a tree treats its bees like vanilla does when a player
 * breaks it.
 */
class BeeNestsTest {

    @Test
    void withoutSilkTouchTheBeesComeOut() {
        assertEquals(Outcome.RELEASE, BeeNests.outcome(false, false, true, true));
        // Nearby bees turn angry even if this nest is empty or blocks don't drop.
        assertEquals(Outcome.RELEASE, BeeNests.outcome(false, false, false, false));
    }

    @Test
    void silkTouchKeepsThemInTheDroppedNest() {
        assertEquals(Outcome.KEEP, BeeNests.outcome(false, true, true, true));
    }

    @Test
    void creativeModeDropsANestWithBeesOrHoney() {
        assertEquals(Outcome.DROP_NEST, BeeNests.outcome(true, false, true, true));
        assertEquals(Outcome.DROP_NEST, BeeNests.outcome(true, true, true, true));
        assertEquals(Outcome.KEEP, BeeNests.outcome(true, false, false, true));
        assertEquals(Outcome.KEEP, BeeNests.outcome(true, false, true, false));
    }
}
