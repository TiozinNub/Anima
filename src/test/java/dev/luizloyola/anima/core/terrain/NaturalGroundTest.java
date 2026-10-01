package dev.luizloyola.anima.core.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.terrain.NaturalGround.Cell;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;

class NaturalGroundTest {

    /** A column drawn top-down from {@code top}; anything below the drawing is natural. */
    private static IntFunction<Cell> column(int top, Cell... fromTop) {
        return y -> {
            int i = top - y;
            return i >= 0 && i < fromTop.length ? fromTop[i] : Cell.NATURAL;
        };
    }

    private static NaturalGround walked(int top, int maxDepth, Cell... fromTop) {
        NaturalGround ground = new NaturalGround(0, 0, 1, 1);
        ground.walk(0, 0, top, maxDepth, column(top, fromTop));
        return ground;
    }

    @Test
    void grassUnderFlowersIsTheGround() {
        NaturalGround ground = walked(70, 64, Cell.OPEN);
        assertEquals(69, ground.groundAt(0, 0));
        assertFalse(ground.has(0, 0, NaturalGround.TREE));
        assertFalse(ground.has(0, 0, NaturalGround.BUILT));
    }

    @Test
    void aTreeIsReadThroughAndFlagged() {
        NaturalGround ground = walked(80, 64, Cell.OPEN, Cell.OPEN, Cell.TREE, Cell.TREE, Cell.TREE);
        assertEquals(75, ground.groundAt(0, 0));
        assertTrue(ground.has(0, 0, NaturalGround.TREE));
    }

    /** A cobblestone floor replaced the top block: the ground is under it, and the column built. */
    @Test
    void aFloorIsReadThroughAndFlagged() {
        NaturalGround ground = walked(70, 64, Cell.BUILT);
        assertEquals(69, ground.groundAt(0, 0));
        assertTrue(ground.has(0, 0, NaturalGround.BUILT));
    }

    @Test
    void aPondEndsTheWalkAtItsTop() {
        NaturalGround ground = walked(62, 64, Cell.FLUID, Cell.FLUID);
        assertEquals(62, ground.groundAt(0, 0));
        assertTrue(ground.has(0, 0, NaturalGround.FLUID));
    }

    @Test
    void groundDeeperThanTheWalkStaysUnknown() {
        NaturalGround ground = walked(70, 3, Cell.BUILT, Cell.BUILT, Cell.BUILT);
        assertEquals(NaturalGround.UNKNOWN, ground.groundAt(0, 0));
        assertTrue(ground.has(0, 0, NaturalGround.BUILT));
    }

    @Test
    void anUnreadColumnIsUnknown() {
        assertEquals(NaturalGround.UNKNOWN, new NaturalGround(5, 5, 2, 2).groundAt(6, 6));
    }
}
