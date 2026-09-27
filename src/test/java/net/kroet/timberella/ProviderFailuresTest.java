package net.kroet.timberella;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import net.kroet.turtlelib.helper.UpdateChecker.Provider;
import net.kroet.turtlelib.helper.UpdateChecker.ProviderResult;
import org.junit.jupiter.api.Test;

/**
 * A failed update source is shown once, until a start, reload or successful
 * fetch resets it.
 */
class ProviderFailuresTest {
    private final Set<Provider> reported = EnumSet.noneOf(Provider.class);

    private static ProviderResult failed(Provider provider) {
        return new ProviderResult(provider, null, null, "timeout", null, false);
    }

    private static ProviderResult fetched(Provider provider, String version) {
        return new ProviderResult(provider, version, null, null, null, false);
    }

    private List<Provider> firstFailures(ProviderResult... results) {
        return TimberellaPlugin.firstFailures(reported, Arrays.asList(results)).stream()
                .map(ProviderResult::provider)
                .toList();
    }

    @Test
    void aFailureIsReportedOnlyTheFirstTime() {
        assertEquals(List.of(Provider.MODRINTH), firstFailures(failed(Provider.MODRINTH), fetched(Provider.HANGAR,
                "2.0.0")));
        assertEquals(List.of(), firstFailures(failed(Provider.MODRINTH), fetched(Provider.HANGAR, "2.0.0")));
    }

    @Test
    void aSuccessfulFetchResetsItsProvider() {
        firstFailures(failed(Provider.MODRINTH), failed(Provider.HANGAR));

        firstFailures(fetched(Provider.MODRINTH, "2.0.0"), failed(Provider.HANGAR));

        assertEquals(List.of(Provider.MODRINTH), firstFailures(failed(Provider.MODRINTH), failed(Provider.HANGAR)));
    }

    // "No eligible version" is a successful fetch too.
    @Test
    void aFetchWithoutAVersionAlsoResets() {
        firstFailures(failed(Provider.HANGAR));

        firstFailures(fetched(Provider.HANGAR, null));

        assertEquals(List.of(Provider.HANGAR), firstFailures(failed(Provider.HANGAR)));
    }

    @Test
    void clearingTheSetLikeAReloadReportsAgain() {
        firstFailures(failed(Provider.MODRINTH));

        reported.clear();

        assertEquals(List.of(Provider.MODRINTH), firstFailures(failed(Provider.MODRINTH)));
    }

    @Test
    void missingResultsReportNothing() {
        assertTrue(TimberellaPlugin.firstFailures(reported, null).isEmpty());
        assertEquals(List.of(), firstFailures((ProviderResult) null));
    }
}
