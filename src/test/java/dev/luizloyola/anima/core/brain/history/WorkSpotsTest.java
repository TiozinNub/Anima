package dev.luizloyola.anima.core.brain.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.brain.sense.Pos;
import org.junit.jupiter.api.Test;

class WorkSpotsTest {

    private static final Pos TRUNK = new Pos(10, 64, 10);

    @Test
    void aDropWithinTheRadiusOfRecentWorkIsNear() {
        WorkSpots spots = new WorkSpots();
        spots.record(TRUNK, 100);
        assertTrue(spots.near(new Pos(14, 64, 13), 200));
        assertFalse(spots.near(new Pos(17, 64, 10), 200));
    }

    @Test
    void workOlderThanADropLastsIsForgotten() {
        WorkSpots spots = new WorkSpots();
        spots.record(TRUNK, 100);
        assertTrue(spots.near(TRUNK, 100 + WorkSpots.MAX_AGE_TICKS));
        assertFalse(spots.near(TRUNK, 101 + WorkSpots.MAX_AGE_TICKS));
    }

    @Test
    void oneTrunkChoppedAgainIsOneSpot() {
        WorkSpots spots = new WorkSpots();
        spots.record(TRUNK, 100);
        spots.record(new Pos(0, 64, 0), 110);
        spots.record(TRUNK, 120);
        assertEquals(2, spots.snapshot().size());
        assertEquals(new WorkSpots.Spot(TRUNK, 120), spots.snapshot().get(0));
    }

    @Test
    void theOldestGoPastCapacity() {
        WorkSpots spots = new WorkSpots();
        for (int i = 0; i < WorkSpots.CAPACITY + 4; i++) {
            spots.record(new Pos(i * 20, 64, 0), i);
        }
        assertEquals(WorkSpots.CAPACITY, spots.snapshot().size());
        assertFalse(spots.near(new Pos(0, 64, 0), 30));
    }
}
