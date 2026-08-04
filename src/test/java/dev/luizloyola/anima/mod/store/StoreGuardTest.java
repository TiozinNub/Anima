package dev.luizloyola.anima.mod.store;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The guard's decision table, all of it silent in the wild: a swallowed total failure and a fresh
 * world produce byte-identical stores, and a partial parse looks healthy until its own count is
 * read back.
 */
class StoreGuardTest {

    /** A store that says whatever the test needs it to say. */
    private record Store(int loadedVersion, int declaredRows, int actualRows)
            implements StoreGuard.Checked {
    }

    /** What a factory-built store looks like. */
    private static Store fresh() {
        return new Store(StoreGuard.NEVER_LOADED, StoreGuard.UNCOUNTED, 0);
    }

    @Test
    void aFreshWorldIsNotAFailure() {
        assertNull(StoreGuard.verdict(false, fresh()),
                "no file and an empty store is simply a world nobody has played");
    }

    @Test
    void aFileBesideANeverLoadedStoreIsTheSwallowedFailure() {
        String complaint = StoreGuard.verdict(true, fresh());
        assertNotNull(complaint, "the file was there and nothing came back — vanilla ate the error");
        assertTrue(complaint.contains("empty store"));
    }

    @Test
    void theOnlyDifferenceBetweenThoseTwoIsTheFileOnDisk() {
        // Why the guard has to touch the filesystem at all: the store cannot tell you, and vanilla
        // answers both with the same null.
        Store store = fresh();
        assertNull(StoreGuard.verdict(false, store));
        assertNotNull(StoreGuard.verdict(true, store));
    }

    @Test
    void aHealthyLoadPasses() {
        assertNull(StoreGuard.verdict(true, new Store(1, 40, 40)));
    }

    @Test
    void aPartialParseIsCaughtByTheCount() {
        // The likely one: DFU's list codec keeps what decodes and drops what does not, so the
        // top-level record loads at the right version holding fewer rows than it was saved with.
        String complaint = StoreGuard.verdict(true, new Store(1, 40, 12));
        assertNotNull(complaint);
        assertTrue(complaint.contains("40"), "says how many were expected");
        assertTrue(complaint.contains("12"), "and how many arrived");
    }

    @Test
    void everyRowDroppedStillLooksLikeAGoodVersion() {
        // The case a version check alone sails straight past, and the reason the count exists.
        assertNull(StoreGuard.verdict(true, new Store(1, 40, 40)));
        assertNotNull(StoreGuard.verdict(true, new Store(1, 40, 0)));
    }

    @Test
    void aPreVersioningFileSkipsTheCountItCannotHave() {
        // version 0 = parsed from a file written before any of this; UNCOUNTED = it never wrote a
        // row count, so there is nothing to compare and no grounds to accuse it.
        assertNull(StoreGuard.verdict(true, new Store(0, StoreGuard.UNCOUNTED, 137)),
                "an existing world must not fail to boot the day the guard lands");
    }

    @Test
    void anEmptyStoreThatHonestlySaysSoPasses() {
        assertNull(StoreGuard.verdict(true, new Store(1, 0, 0)),
                "a world where nobody knows anybody yet still saves a file");
    }
}
