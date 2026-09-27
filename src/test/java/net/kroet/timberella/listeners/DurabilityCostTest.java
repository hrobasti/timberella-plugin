package net.kroet.timberella.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Proxy;
import org.bukkit.inventory.meta.Damageable;
import org.junit.jupiter.api.Test;

/**
 * Charging the axe log by log must add up to exactly the cost of charging the
 * whole felling at once, and never take the axe below 1 durability.
 */
class DurabilityCostTest {

    private static final double[] MULTIPLIERS = {0.0, 0.3, 0.5, 1.0, 1.5, 2.0};

    // Replays a felling of logCount logs: one charge before the first further
    // log and one after every broken log, each only for the difference.
    private static int chargeIncrementally(int logCount, double multiplier, int maxDurability, int startDamage) {
        int damage = startDamage;
        int charged = 0;
        for (int broken = 1; broken <= logCount; broken++) {
            int due = TreeChopListener.extraDurabilityCost(broken, multiplier) - charged;
            if (due > 0) {
                int applied = TreeChopListener.cappedDurabilityCharge(due, maxDurability, damage);
                damage += applied;
                charged += applied;
            }
        }
        return charged;
    }

    @Test
    void incrementalChargesMatchTheWholeFellingCost() {
        for (double multiplier : MULTIPLIERS) {
            for (int logs = 1; logs <= 200; logs++) {
                int expected = TreeChopListener.extraDurabilityCost(logs, multiplier);
                assertEquals(expected, chargeIncrementally(logs, multiplier, 10_000, 1),
                        "multiplier " + multiplier + ", " + logs + " logs");
            }
        }
    }

    @Test
    void halfMultiplierExamples() {
        // round(n * 0.5) minus the vanilla point for the first block
        assertEquals(0, TreeChopListener.extraDurabilityCost(1, 0.5));
        assertEquals(0, TreeChopListener.extraDurabilityCost(2, 0.5));
        assertEquals(1, TreeChopListener.extraDurabilityCost(3, 0.5));
        assertEquals(4, TreeChopListener.extraDurabilityCost(10, 0.5));
    }

    @Test
    void axeKeepsAtLeastOneDurability() {
        // Iron axe (250) with 240 damage: 10 left, so at most 9 more.
        for (double multiplier : MULTIPLIERS) {
            for (int logs = 1; logs <= 200; logs++) {
                int expected = Math.min(TreeChopListener.extraDurabilityCost(logs, multiplier), 9);
                assertEquals(expected, chargeIncrementally(logs, multiplier, 250, 240),
                        "multiplier " + multiplier + ", " + logs + " logs");
            }
        }
        assertEquals(0, TreeChopListener.cappedDurabilityCharge(5, 250, 249));
    }

    // An axe's own max_damage component counts, not its type's durability.
    @Test
    void itemOwnMaxDamageWinsOverTheTypeDurability() {
        assertEquals(100, TreeChopListener.maxDurability(1561, damageable(100)));
        assertEquals(2000, TreeChopListener.maxDurability(250, damageable(2000)));
        assertEquals(250, TreeChopListener.maxDurability(250, damageable(null)));
    }

    private static Damageable damageable(Integer maxDamage) {
        return (Damageable) Proxy.newProxyInstance(Damageable.class.getClassLoader(), new Class<?>[]{Damageable.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "hasMaxDamage" -> maxDamage != null;
                    case "getMaxDamage" -> maxDamage;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
