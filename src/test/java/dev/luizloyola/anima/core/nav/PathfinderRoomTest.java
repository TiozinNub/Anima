package dev.luizloyola.anima.core.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.sense.Confinement;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Room to stand: {@link Pathfinder#room} and the rest cells a route search counts, over the shape
 * that needed them — a settler on a ledge over a flooded channel (the forest, 2026-09-28).
 */
class PathfinderRoomTest {

    private static final int WIDTH = 60;
    private static final int CHANNEL_WEST = 20;
    private static final int CHANNEL_EAST = 24;
    /** Where the channel reaches a beach, far enough down it that the swim is a long one. */
    private static final int BEACH = 40;
    /** The notch the body stands in: two under the ground behind it, five over the water. */
    private static final int LEDGE_X = 19;
    private static final Set<Integer> LEDGE_Z = Set.of(10, 11, 12);

    /**
     * Ground at 7 on both sides of a five-wide channel, the ledge notched into its west wall at 5,
     * and at the far end a beach at 1 ramping back up to the ground. Deep enough in z that a survey
     * spends its budget before it runs out of world, so a way out reads as one.
     */
    private static AsciiWorld ledge(boolean beach) {
        String[] rows = new String[90];
        for (int z = 0; z < rows.length; z++) {
            StringBuilder row = new StringBuilder();
            for (int x = 0; x < WIDTH; x++) {
                boolean channel = x >= CHANNEL_WEST && x <= CHANNEL_EAST;
                if (x == LEDGE_X && LEDGE_Z.contains(z)) {
                    row.append('5');
                } else if (channel && z < BEACH) {
                    row.append('W');
                } else if (channel && beach && z < BEACH + 7) {
                    row.append((char) ('1' + z - BEACH));
                } else if (channel && !beach) {
                    row.append('#');
                } else {
                    row.append('7');
                }
            }
            rows[z] = row.toString();
        }
        return AsciiWorld.of(rows);
    }

    private static PathRequest from(int x, int y, int z) {
        return PathRequest.of(x, y, z, x, y, z, TestBodies.BIPED);
    }

    @Test
    void aLedgeOverAChannelHasAWayOutButNoRoom() {
        AsciiWorld world = ledge(true);

        Confinement survey = Pathfinder.survey(world, from(LEDGE_X, 5, 11));
        assertFalse(survey.sealed(), "the channel leads to a beach, so a way out exists");

        Confinement room = Pathfinder.room(world, from(LEDGE_X, 5, 11), 32.0, 64);
        assertTrue(room.sealed(), "three cells of ground and a long swim is no room to stand");
        assertEquals(3, room.cells());
        assertEquals(Set.of(new Pos(LEDGE_X, 5, 10), new Pos(LEDGE_X, 5, 11), new Pos(LEDGE_X, 5, 12)),
                Set.copyOf(room.region()), "the region is where the body can stand, not the water");
    }

    @Test
    void theWalkIsWhatMakesTheSwimTooLong() {
        Confinement room = Pathfinder.room(ledge(true), from(LEDGE_X, 5, 11), 400.0, 64);
        assertFalse(room.sealed(), "given walk enough, the beach and the ground past it count");
    }

    @Test
    void openGroundHasRoom() {
        Confinement room = Pathfinder.room(ledge(true), from(10, 7, 11), 32.0, 64);
        assertFalse(room.sealed());
        assertTrue(room.region().isEmpty(), "a body with room has no region to report");
    }

    @Test
    void aRouteSearchCountsOnlyTheCellsTheBodyCouldStopIn() {
        Path path = Pathfinder.find(ledge(false), PathRequest.of(LEDGE_X, 5, 11, 10, 7, 11,
                TestBodies.BIPED));
        assertFalse(path.reachedGoal());
        assertEquals(3, path.restCells(), "only the ledge is ground: " + path);
        assertTrue(path.reachableCells() > 100,
                "the channel is still searched, it just is not ground: " + path.reachableCells());
    }
}
