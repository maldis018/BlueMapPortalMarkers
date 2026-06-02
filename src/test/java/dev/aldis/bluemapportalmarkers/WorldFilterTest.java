package dev.aldis.bluemapportalmarkers;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldFilterTest {

    // --- Blacklist ---

    @Test
    void blacklistEmptyAllowsAll() {
        WorldFilter f = new WorldFilter(WorldFilter.Mode.BLACKLIST, List.of());
        assertTrue(f.allows("world"));
        assertTrue(f.allows("world_nether"));
        assertTrue(f.allows("creative"));
    }

    @Test
    void blacklistBlocksListedWorld() {
        WorldFilter f = new WorldFilter(WorldFilter.Mode.BLACKLIST, List.of("creative", "minigames"));
        assertFalse(f.allows("creative"));
        assertFalse(f.allows("minigames"));
    }

    @Test
    void blacklistAllowsUnlistedWorld() {
        WorldFilter f = new WorldFilter(WorldFilter.Mode.BLACKLIST, List.of("creative"));
        assertTrue(f.allows("world"));
        assertTrue(f.allows("world_nether"));
    }

    // --- Whitelist ---

    @Test
    void whitelistEmptyBlocksAll() {
        WorldFilter f = new WorldFilter(WorldFilter.Mode.WHITELIST, List.of());
        assertFalse(f.allows("world"));
        assertFalse(f.allows("world_nether"));
    }

    @Test
    void whitelistAllowsListedWorld() {
        WorldFilter f = new WorldFilter(WorldFilter.Mode.WHITELIST, List.of("world", "world_nether"));
        assertTrue(f.allows("world"));
        assertTrue(f.allows("world_nether"));
    }

    @Test
    void whitelistBlocksUnlistedWorld() {
        WorldFilter f = new WorldFilter(WorldFilter.Mode.WHITELIST, List.of("world"));
        assertFalse(f.allows("creative"));
        assertFalse(f.allows("minigames"));
    }

    // --- Case sensitivity ---

    @Test
    void matchIsCaseSensitive() {
        WorldFilter f = new WorldFilter(WorldFilter.Mode.BLACKLIST, List.of("Creative"));
        assertTrue(f.allows("creative"));   // different case → not blocked
        assertFalse(f.allows("Creative"));
    }
}
