package net.kroet.timberella.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.junit.jupiter.api.Test;

/**
 * Felled logs add to the mined-block objectives like hand-broken ones, even
 * though the server writes the lifetime total into them.
 */
class MinedStatisticTest {
    private int lifetime = 253;

    // The objective was created after the player had already mined 253 logs.
    @Test
    void freshObjectiveCountsOnlyTheFelledLogs() {
        FakeScore fresh = new FakeScore(null);
        FakeScore running = new FakeScore(7);
        List<FakeScore> tracked = List.of(fresh, running);

        for (int log = 0; log < 5; log++) {
            MinedStatistic.addKeepingScores(objectives(fresh, running), "Steve", () -> award(tracked), 1);
        }

        assertEquals(5, fresh.value);
        assertEquals(12, running.value);
        assertEquals(258, lifetime);
    }

    @Test
    void objectiveTheServerDoesNotUpdateStaysAsItIs() {
        FakeScore untrackedSet = new FakeScore(3);
        FakeScore untrackedUnset = new FakeScore(null);

        MinedStatistic.addKeepingScores(objectives(untrackedSet, untrackedUnset), "Steve", () -> award(List.of()),
                1);

        assertEquals(3, untrackedSet.value);
        assertFalse(untrackedUnset.isSet());
    }

    // What Paper's incrementStatistic does to the objectives it updates.
    private void award(List<FakeScore> tracked) {
        lifetime++;
        tracked.forEach(score -> score.value = lifetime);
    }

    private static List<Objective> objectives(FakeScore... scores) {
        return Arrays.stream(scores).map(MinedStatisticTest::objective).toList();
    }

    private static Objective objective(FakeScore score) {
        Score proxy = (Score) Proxy.newProxyInstance(Score.class.getClassLoader(), new Class<?>[]{Score.class},
                (self, method, args) -> switch (method.getName()) {
                    case "isScoreSet" -> score.isSet();
                    case "getScore" -> score.value == null ? 0 : score.value;
                    case "setScore" -> {
                        score.value = (Integer) args[0];
                        yield null;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        return (Objective) Proxy.newProxyInstance(Objective.class.getClassLoader(), new Class<?>[]{Objective.class},
                (self, method, args) -> {
                    if (method.getName().equals("getScore") && args[0] instanceof String)
                        return proxy;
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static final class FakeScore {
        Integer value;

        FakeScore(Integer value) {
            this.value = value;
        }

        boolean isSet() {
            return value != null;
        }
    }
}
