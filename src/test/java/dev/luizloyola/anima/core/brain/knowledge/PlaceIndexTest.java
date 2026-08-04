package dev.luizloyola.anima.core.brain.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.config.Config;
import dev.luizloyola.anima.core.config.Knob;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The index of recognised things — the structure that replaced "remember the mass somebody
 * walked" with "remember the thing, once, under itself".
 */
class PlaceIndexTest {

    private static final PoiKind THICKET = FakeGrowthRule.THICKET;

    /** A second kind, so "is this cell yours?" is never answered by somebody else's thing. */
    private static final PoiKind OTHER_KIND = PoiKind.register("test_place_other", 4, "");

    @AfterEach
    void restoreDefaults() {
        Config.reset();
    }

    private static void allow(int cells) {
        Config.install(Config.get().with(Knob.PLACE_INDEX_CELLS, cells));
    }

    /** One thing of {@code cells} blocks, seen whole, approachable at its first cell. */
    private static GrownRegion region(boolean complete, Pos... cells) {
        return region(THICKET, complete, cells);
    }

    private static GrownRegion region(PoiKind kind, boolean complete, Pos... cells) {
        Map<Pos, BlockKind> blocks = new LinkedHashMap<>();
        Region bounds = null;
        for (Pos cell : cells) {
            blocks.put(cell, BlockKind.LOG);
            bounds = bounds == null ? Region.of(cell) : bounds.including(cell);
        }
        return new GrownRegion(kind, !complete, blocks,
                List.of(new GrownRegion.Part(List.of(cells[0]), bounds, cells.length, blocks,
                        complete)));
    }

    /** Several at once, each one cell, so seams and eviction have something to chew on. */
    private static GrownRegion scatter(boolean complete, Pos... cells) {
        List<GrownRegion.Part> parts = new ArrayList<>();
        Map<Pos, BlockKind> all = new LinkedHashMap<>();
        for (Pos cell : cells) {
            all.put(cell, BlockKind.LOG);
            parts.add(new GrownRegion.Part(List.of(cell), Region.of(cell), 1,
                    Map.of(cell, BlockKind.LOG), complete));
        }
        return new GrownRegion(THICKET, !complete, all, parts);
    }

    @Test
    @DisplayName("every cell of a filed thing answers with that thing")
    void everyCellAnswers() {
        allow(1024);
        PlaceIndex index = new PlaceIndex();
        Pos foot = new Pos(4, 64, 4);
        Pos top = new Pos(4, 65, 4);

        assertNull(index.at(THICKET, foot), "nothing is known before anybody looks");
        assertEquals(1, index.putAll(region(true, foot, top)));

        PlaceIndex.Place found = index.at(THICKET, foot);
        assertNotNull(found);
        assertSame(found, index.at(THICKET, top), "a leaf and its trunk are the same tree");
        assertEquals(2, found.units());
        assertEquals(1, index.size());
        assertEquals(2, index.cells());
    }

    @Test
    @DisplayName("asking about the wrong kind is a miss, not somebody else's answer")
    void kindIsPartOfTheQuestion() {
        allow(1024);
        PlaceIndex index = new PlaceIndex();
        Pos cell = new Pos(0, 64, 0);
        index.putAll(region(true, cell));

        assertNotNull(index.at(THICKET, cell));
        assertNull(index.at(OTHER_KIND, cell));
    }

    /**
     * A scan stopped at its spread cap is "partial", and the old cache lent none of it — three
     * quarters of every re-grown scan in a wood. Here the cut costs only what touches it.
     */
    @Test
    @DisplayName("a cut-short scan still files the things inside it that were seen whole")
    void aCutShortScanStillFilesWhatItSawWhole() {
        allow(1024);
        PlaceIndex index = new PlaceIndex();
        Pos whole = new Pos(0, 64, 0);
        Pos clipped = new Pos(40, 64, 40);

        Map<Pos, BlockKind> all = new LinkedHashMap<>();
        all.put(whole, BlockKind.LOG);
        all.put(clipped, BlockKind.LOG);
        GrownRegion cutShort = new GrownRegion(THICKET, true, all, List.of(
                new GrownRegion.Part(List.of(whole), Region.of(whole), 1,
                        Map.of(whole, BlockKind.LOG), true),
                new GrownRegion.Part(List.of(clipped), Region.of(clipped), 1,
                        Map.of(clipped, BlockKind.LOG), false)));

        assertEquals(1, index.putAll(cutShort), "one of the two was seen whole");
        assertNotNull(index.at(THICKET, whole), "and it is a fact worth lending");
        assertNull(index.at(THICKET, clipped),
                "the one straddling the cut is provisional — looked at again, never lent");
    }

    @Test
    @DisplayName("a block changing forgets the thing there AND the ones sharing its seam")
    void invalidationReachesOneHopPastTheSeam() {
        allow(1024);
        PlaceIndex index = new PlaceIndex();
        // Four in a row, each touching the next: seams a-b, b-c, c-d.
        Pos a = new Pos(0, 64, 0);
        Pos b = new Pos(1, 64, 0);
        Pos c = new Pos(2, 64, 0);
        Pos d = new Pos(3, 64, 0);
        Pos apart = new Pos(40, 64, 40);
        index.putAll(scatter(true, a, b, c, d, apart));
        assertEquals(5, index.size());

        index.invalidate(a.x(), a.z());

        // The one-cell skirt puts both a and b over the changed column, so both are directly
        // hit; c goes because it drew a boundary with b. d is two hops out.
        assertNull(index.at(THICKET, a), "the thing that changed is gone");
        assertNull(index.at(THICKET, b), "and so is the neighbour whose boundary it helped draw");
        assertNull(index.at(THICKET, c), "and that one's neighbour, one hop from a direct hit");
        assertNotNull(index.at(THICKET, d), "one hop, not the whole wood");
        assertNotNull(index.at(THICKET, apart), "and nothing far away is touched");
    }

    @Test
    @DisplayName("a later scan claiming the same cells wins outright")
    void theLaterScanWins() {
        allow(1024);
        PlaceIndex index = new PlaceIndex();
        Pos shared = new Pos(5, 64, 5);
        index.putAll(region(true, shared, new Pos(5, 65, 5)));
        PlaceIndex.Place first = index.at(THICKET, shared);

        index.putAll(region(true, shared, new Pos(6, 64, 5)));
        PlaceIndex.Place second = index.at(THICKET, shared);

        assertNotNull(second);
        assertTrue(first != second, "ownership moved, and two things never both own one cell");
        assertEquals(1, index.size(), "the loser went entirely, not just the contested cell");
        assertNull(index.at(THICKET, new Pos(5, 65, 5)), "including the cells only it held");
    }

    @Test
    @DisplayName("bounded in cells, least recently asked about first")
    void evictsTheLeastRecentlyAskedAbout() {
        allow(3);
        PlaceIndex index = new PlaceIndex();
        Pos first = new Pos(0, 64, 0);
        Pos second = new Pos(20, 64, 20);
        Pos third = new Pos(40, 64, 40);
        index.putAll(scatter(true, first));
        index.putAll(scatter(true, second));
        assertNotNull(index.at(THICKET, first), "asking about it makes it the freshest");

        index.putAll(scatter(true, third));

        assertEquals(3, index.size(), "three single-cell things fit exactly");
        index.putAll(scatter(true, new Pos(60, 64, 60)));
        assertEquals(3, index.size(), "and the fourth costs one of them");
        assertNull(index.at(THICKET, second), "the one nobody had asked about since");
        assertEquals(1, index.evictions());
    }

    @Test
    @DisplayName("a zero allowance turns it off without anybody having to check")
    void zeroDisablesIt() {
        allow(0);
        PlaceIndex index = new PlaceIndex();
        assertEquals(0, index.putAll(region(true, new Pos(0, 64, 0))));
        assertEquals(0, index.size());
        assertNull(index.at(THICKET, new Pos(0, 64, 0)));
    }
}
