package com.hro_basti.timberella.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VersionComparatorTest {

    @Test
    void numericComparisonOrdersSegments() {
        assertTrue(VersionComparator.compare("1.2.0", "1.1.9") > 0, "1.2.0 should be newer than 1.1.9");
        assertTrue(VersionComparator.compare("1.0", "1") == 0, "Missing segments default to zero");
        assertTrue(VersionComparator.compare("2", "10") < 0, "Shorter numeric values compare numerically");
    }

    @Test
    void letterSuffixAfterPlainVersion() {
        assertTrue(VersionComparator.compare("1.0a", "1.0") > 0, "Letter suffix ranks after plain segment");
        assertTrue(VersionComparator.compare("1.0b", "1.0a") > 0, "Suffixes compare lexicographically");
    }

    @Test
    void prereleaseRanksBeforeFinal() {
        assertTrue(VersionComparator.compare("1.0", "1.0-rc1") > 0, "Release beats prerelease");
        assertTrue(VersionComparator.compare("1.0-rc2", "1.0-rc1") > 0, "Prerelease numbers ascend");
        assertTrue(VersionComparator.compare("1.0-beta", "1.0-alpha") > 0, "beta after alpha");
    }

    @Test
    void unknownPrereleaseTagsHaveStableOrder() {
        assertTrue(VersionComparator.compare("1.0-foo", "1.0") < 0, "Unknown prerelease ranks before release");
        assertTrue(VersionComparator.compare("1.0-bar", "1.0-foo") < 0, "Unknown labels compare lexicographically");
    }

    @Test
    void isGreaterConvenienceMethodDelegates() {
        assertTrue(VersionComparator.isGreater("1.2", "1.1"));
        assertFalse(VersionComparator.isGreater("1.0", "1.0"));
        assertFalse(VersionComparator.isGreater("0.9", "1.0"));
    }
}
