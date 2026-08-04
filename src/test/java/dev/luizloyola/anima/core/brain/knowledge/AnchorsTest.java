package dev.luizloyola.anima.core.brain.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The half of recognising a place that belongs to the observer — everything else about a thing is
 * worked out once and shared.
 */
class AnchorsTest {

    @Test
    @DisplayName("two bodies at opposite ends walk to opposite ends")
    void theNearEndIsWhoeverIsAsking() {
        List<Pos> shore = List.of(new Pos(0, 64, 0), new Pos(20, 64, 0));

        assertEquals(new Pos(0, 64, 0), Anchors.choose(shore, new Pos(-5, 64, 0)));
        assertEquals(new Pos(20, 64, 0), Anchors.choose(shore, new Pos(25, 64, 0)));
    }

    @Test
    @DisplayName("down beats near — you fell a tree at its stump, not at the nearest branch")
    void lowestWinsOverNearest() {
        Pos stump = new Pos(10, 64, 10);
        Pos branchOverhead = new Pos(0, 72, 0);
        // The branch is the nearer of the two to a body standing under it, and still loses.
        assertEquals(stump, Anchors.choose(List.of(branchOverhead, stump), new Pos(0, 64, 0)));
    }

    @Test
    @DisplayName("height has its say once, not twice")
    void distanceIsHorizontal() {
        List<Pos> feet = List.of(new Pos(4, 64, 0), new Pos(-4, 64, 0));
        // Standing high above the west foot: in three dimensions the east one is no nearer, but
        // horizontally the west one plainly is, and that is the one you would walk to.
        assertEquals(new Pos(-4, 64, 0), Anchors.choose(feet, new Pos(-3, 90, 0)));
    }

    @Test
    @DisplayName("a tie resolves the same way every time — an anchor is a memory's identity")
    void tiesAreDeterministic() {
        Pos west = new Pos(-3, 64, 0);
        Pos east = new Pos(3, 64, 0);
        Pos middle = new Pos(0, 64, 0);

        assertEquals(west, Anchors.choose(List.of(west, east), middle));
        assertEquals(west, Anchors.choose(List.of(east, west), middle),
                "and it does not depend on which order the candidates arrived in");
    }
}
