package dev.luizloyola.anima.core.nav;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

/** The search's reading of a request's recorded pillars must be the set's, cell for cell. */
class RecordedPillarsTest {

    /** Two blocks of one pillar at forest coordinates, and a lone block at the origin. */
    private static final Set<Long> PILLARS = Set.of(LaidBlocks.cell(-172, 42, -607),
            LaidBlocks.cell(-172, 43, -607), LaidBlocks.cell(0, 0, 0));

    @Test
    void holdsExactlyTheRecordedCells() {
        RecordedPillars pillars = RecordedPillars.of(PILLARS);
        assertFalse(pillars.isEmpty());
        for (int x = -175; x <= 3; x++) {
            for (int y = -1; y <= 45; y++) {
                for (int z = -610; z <= 3; z += z < -600 || z > -4 ? 1 : 300) {
                    assertTrue(pillars.contains(x, y, z) == PILLARS.contains(LaidBlocks.cell(x, y, z)),
                            x + " " + y + " " + z);
                }
            }
        }
    }

    @Test
    void besideIsACardinalSideAtTheSameHeight() {
        RecordedPillars pillars = RecordedPillars.of(PILLARS);
        assertTrue(pillars.beside(-171, 42, -607));
        assertTrue(pillars.beside(-173, 43, -607));
        assertTrue(pillars.beside(-172, 42, -606));
        assertTrue(pillars.beside(-172, 43, -608));
        assertTrue(pillars.beside(1, 0, 0), "the origin packs to zero, an ordinary key");
        assertFalse(pillars.beside(-172, 42, -607), "the pillar's own cell");
        assertFalse(pillars.beside(-171, 42, -606), "a diagonal");
        assertFalse(pillars.beside(-171, 44, -607), "level with no block of it");
        assertFalse(pillars.beside(-50, 20, -300), "inside the box, beside nothing");
    }

    @Test
    void noPillarsHoldNothing() {
        RecordedPillars none = RecordedPillars.of(Set.of());
        assertTrue(none.isEmpty());
        assertFalse(none.contains(0, 0, 0));
        assertFalse(none.beside(1, 0, 0));
    }
}
