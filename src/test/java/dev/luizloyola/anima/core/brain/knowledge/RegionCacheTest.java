package dev.luizloyola.anima.core.brain.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.config.Config;
import dev.luizloyola.anima.core.config.Knob;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The level's memory of what the ground is shaped like: that it answers, that it forgets when the
 * ground moves, and that it never grows past what it was allowed.
 */
class RegionCacheTest {

    @AfterEach
    void restoreDefaults() {
        Config.reset();
    }

    private static GrownRegion massAt(Pos... cells) {
        return mass(false, cells);
    }

    private static GrownRegion mass(boolean partial, Pos... cells) {
        Map<Pos, BlockKind> blocks = new LinkedHashMap<>();
        for (Pos cell : cells) {
            blocks.put(cell, BlockKind.LOG);
        }
        return new GrownRegion(FakeGrowthRule.THICKET, partial, blocks,
                List.of(new GrownRegion.Part(List.of(cells[0]), Region.of(cells[0]),
                        cells.length, blocks, !partial)));
    }

    private static RegionCache.Key keyAt(Pos seed) {
        return new RegionCache.Key(FakeGrowthRule.THICKET, seed, 24);
    }

    @Test
    @DisplayName("a mass grown once is handed back whole to whoever seeds the same cell")
    void hitsOnTheSameSeed() {
        RegionCache cache = new RegionCache();
        Pos seed = new Pos(10, 64, 10);
        GrownRegion grown = massAt(seed, new Pos(10, 65, 10));

        assertNull(cache.get(keyAt(seed)), "nothing is known before anybody looks");
        cache.put(keyAt(seed), grown);

        assertSame(grown, cache.get(keyAt(seed)));
        assertEquals(1, cache.hits());
        assertEquals(1, cache.misses());
    }

    @Test
    @DisplayName("a different seed, or a different reach, is a different question")
    void missesOnADifferentKey() {
        RegionCache cache = new RegionCache();
        Pos seed = new Pos(10, 64, 10);
        cache.put(keyAt(seed), massAt(seed));

        assertNull(cache.get(keyAt(new Pos(11, 64, 10))), "a neighbouring seed grows its own mass");
        assertNull(cache.get(new RegionCache.Key(FakeGrowthRule.THICKET, seed, 8)),
                "a shorter reach would have stopped somewhere else");
    }

    @Test
    @DisplayName("a body arriving at the same mass from another side gets its cells anyway")
    void coveringServesAnySeedInsideACompleteMass() {
        RegionCache cache = new RegionCache();
        Pos near = new Pos(10, 64, 10);
        Pos far = new Pos(10, 67, 10);
        cache.put(keyAt(near), massAt(near, new Pos(10, 65, 10), new Pos(10, 66, 10), far));

        assertNull(cache.get(keyAt(far)), "not the scan somebody else ran");
        assertNotNull(cache.covering(FakeGrowthRule.THICKET, far, 24), "but the same mass");
        assertNull(cache.covering(FakeGrowthRule.THICKET, new Pos(10, 68, 10), 24),
                "a cell that is not in it is not in it");
    }

    @Test
    @DisplayName("a partial mass is served only to the seed that produced it")
    void coveringRefusesPartialMasses() {
        RegionCache cache = new RegionCache();
        Pos near = new Pos(10, 64, 10);
        Pos far = new Pos(10, 66, 10);
        cache.put(keyAt(near), mass(true, near, new Pos(10, 65, 10), far));

        assertNotNull(cache.get(keyAt(near)), "the body that stopped short still gets its own scan");
        assertNull(cache.covering(FakeGrowthRule.THICKET, far, 24),
                "but where it stopped is a fact about where it stood, not about the wood");
    }

    @Test
    @DisplayName("and only to a seed that could have reached all of it")
    void coveringRefusesWhatIsOutOfReach() {
        RegionCache cache = new RegionCache();
        Pos end = new Pos(0, 64, 0);
        Pos other = new Pos(0, 64, 20);
        cache.put(keyAt(new Pos(0, 64, 10)), massAt(new Pos(0, 64, 10), end, other));

        assertNotNull(cache.covering(FakeGrowthRule.THICKET, end, 24), "20 away, willing to go 24");
        assertNull(cache.covering(FakeGrowthRule.THICKET, end, 8),
                "the far end lies past where this body would have stopped looking");
    }

    @Test
    @DisplayName("another kind's mass is another question")
    void coveringRefusesAnotherKind() {
        RegionCache cache = new RegionCache();
        Pos seed = new Pos(10, 64, 10);
        cache.put(keyAt(seed), massAt(seed));

        assertNull(cache.covering(TestPois.WATER, seed, 24));
    }

    @Test
    @DisplayName("a block changing inside the footprint forgets the shape")
    void invalidatesOnAChangeInside() {
        RegionCache cache = new RegionCache();
        Pos seed = new Pos(10, 64, 10);
        cache.put(keyAt(seed), massAt(seed, new Pos(10, 65, 10), new Pos(11, 65, 10)));

        cache.invalidate(11, 10);
        assertNull(cache.get(keyAt(seed)));
        assertEquals(0, cache.size());
        assertEquals(0, cache.cells());
    }

    @Test
    @DisplayName("height is not part of the question — a change anywhere in the column counts")
    void invalidatesAtAnyHeight() {
        RegionCache cache = new RegionCache();
        Pos seed = new Pos(10, 64, 10);
        cache.put(keyAt(seed), massAt(seed));

        // Two hundred blocks over the mass: a rule may ask what the top of a column is, so this
        // can genuinely change what belongs to it.
        cache.invalidate(10, 10);
        assertNull(cache.get(keyAt(seed)));
    }

    @Test
    @DisplayName("the footprint carries a one-cell skirt, because growth joins diagonally")
    void invalidatesOnATouchingChange() {
        RegionCache cache = new RegionCache();
        Pos seed = new Pos(10, 64, 10);
        cache.put(keyAt(seed), massAt(seed));

        cache.invalidate(11, 11); // corner-adjacent: a log placed here would join the mass
        assertNull(cache.get(keyAt(seed)));
    }

    @Test
    @DisplayName("a change well clear of a mass leaves it alone")
    void keepsWhatDidNotMove() {
        RegionCache cache = new RegionCache();
        Pos seed = new Pos(10, 64, 10);
        cache.put(keyAt(seed), massAt(seed));

        cache.invalidate(40, 40);
        cache.invalidate(12, 10); // two clear of the mass, outside the skirt
        assertNotNull(cache.get(keyAt(seed)));
    }

    @Test
    @DisplayName("a chunk going away takes its shapes with it")
    void invalidatesOnChunkUnload() {
        RegionCache cache = new RegionCache();
        Pos seed = new Pos(10, 64, 10);
        cache.put(keyAt(seed), massAt(seed));

        cache.invalidateChunk(1, 1);
        assertNotNull(cache.get(keyAt(seed)), "a chunk it does not touch");
        cache.invalidateChunk(0, 0);
        assertNull(cache.get(keyAt(seed)));
    }

    @Test
    @DisplayName("the cell allowance is a ceiling, and the least recently walked goes first")
    void evictsLeastRecentlyUsed() {
        Config.install(Config.get().with(Knob.REGION_CACHE_CELLS, 4));
        RegionCache cache = new RegionCache();
        Pos a = new Pos(0, 64, 0);
        Pos b = new Pos(32, 64, 0);
        Pos c = new Pos(64, 64, 0);
        cache.put(keyAt(a), massAt(a, new Pos(0, 65, 0)));
        cache.put(keyAt(b), massAt(b, new Pos(32, 65, 0)));
        assertEquals(4, cache.cells());

        cache.get(keyAt(a)); // a is walked past again; b becomes the stalest
        cache.put(keyAt(c), massAt(c, new Pos(64, 65, 0)));

        assertTrue(cache.cells() <= 4, "never over the allowance");
        assertNotNull(cache.get(keyAt(a)));
        assertNotNull(cache.get(keyAt(c)));
        assertNull(cache.get(keyAt(b)));
    }

    @Test
    @DisplayName("a mass bigger than the whole allowance is not cached, not accommodated")
    void refusesWhatWillNotFit() {
        Config.install(Config.get().with(Knob.REGION_CACHE_CELLS, 2));
        RegionCache cache = new RegionCache();
        Pos seed = new Pos(0, 64, 0);
        cache.put(keyAt(seed), massAt(seed, new Pos(0, 65, 0), new Pos(0, 66, 0)));

        assertNull(cache.get(keyAt(seed)));
        assertEquals(0, cache.size());
    }

    @Test
    @DisplayName("zero cells turns it off entirely")
    void disabledAtZero() {
        Config.install(Config.get().with(Knob.REGION_CACHE_CELLS, 0));
        RegionCache cache = new RegionCache();
        Pos seed = new Pos(0, 64, 0);
        cache.put(keyAt(seed), massAt(seed));

        assertNull(cache.get(keyAt(seed)));
        assertEquals(0, cache.size());
    }
}
