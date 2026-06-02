package dev.aldis.bluemapportalmarkers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MarkerLabelTest {

    @Test
    void nullTemplateFallsBackToDefault() {
        assertEquals("Nether Portal", MarkerLabel.format(null, "world", 10, 64, -30));
    }

    @Test
    void emptyTemplateFallsBackToDefault() {
        assertEquals("Nether Portal", MarkerLabel.format("", "world", 10, 64, -30));
    }

    @Test
    void worldPlaceholderSubstituted() {
        assertEquals("Portal in world", MarkerLabel.format("Portal in {world}", "world", 0, 0, 0));
    }

    @Test
    void coordPlaceholdersSubstituted() {
        assertEquals("10, 64, -30", MarkerLabel.format("{x}, {y}, {z}", "world", 10, 64, -30));
    }

    @Test
    void allPlaceholdersCombined() {
        assertEquals("world @ 10, 64, -31",
                MarkerLabel.format("{world} @ {x}, {y}, {z}", "world", 10.4, 64.0, -30.6));
    }

    @Test
    void coordsAreRounded() {
        assertEquals("10, 65, -31",
                MarkerLabel.format("{x}, {y}, {z}", "w", 10.4, 64.6, -30.6));
    }

    @Test
    void nullWorldNameBecomesEmpty() {
        assertEquals("@  pos", MarkerLabel.format("@ {world} pos", null, 0, 0, 0));
    }

    @Test
    void templateWithNoPlaceholdersIsReturnedVerbatim() {
        assertEquals("Static Label", MarkerLabel.format("Static Label", "world", 1, 2, 3));
    }
}
