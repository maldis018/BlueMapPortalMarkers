package dev.aldis.bluemapportalmarkers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionCompareTest {

    @Test
    void higherVersionIsNewer() {
        assertTrue(VersionCompare.isNewer("0.3.0", "0.4.0"));
        assertTrue(VersionCompare.isNewer("0.3.0", "1.0.0"));
        assertTrue(VersionCompare.isNewer("0.3.0", "0.3.1"));
    }

    @Test
    void sameOrLowerVersionIsNotNewer() {
        assertFalse(VersionCompare.isNewer("0.3.0", "0.3.0"));
        assertFalse(VersionCompare.isNewer("0.4.0", "0.3.0"));
        assertFalse(VersionCompare.isNewer("1.0.0", "0.9.9"));
    }

    @Test
    void leadingVPrefixIsIgnored() {
        assertTrue(VersionCompare.isNewer("0.3.0", "v0.4.0"));
        assertFalse(VersionCompare.isNewer("v0.3.0", "0.3.0"));
    }

    @Test
    void snapshotQualifierIsStripped() {
        // Running the snapshot of the version that just released → not "newer".
        assertFalse(VersionCompare.isNewer("0.3.0-SNAPSHOT", "v0.3.0"));
        // But a later release than the running snapshot is newer.
        assertTrue(VersionCompare.isNewer("0.3.0-SNAPSHOT", "v0.4.0"));
    }

    @Test
    void differingComponentCountsComparePositionally() {
        assertTrue(VersionCompare.isNewer("0.3", "0.3.1"));
        assertFalse(VersionCompare.isNewer("0.3.0", "0.3"));
        assertTrue(VersionCompare.isNewer("1", "1.0.1"));
    }
}
