package dev.luizloyola.anima.core.brain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import dev.luizloyola.anima.core.brain.sense.Being;
import dev.luizloyola.anima.core.brain.sense.BeingId;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.nav.CellType;
import dev.luizloyola.anima.core.nav.NavGrid;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Walking up to talk ends in front of the other's face (breaking-in spec, decision 3). */
class FacingSpotTest {

    private final FakeContext ctx = new FakeContext();
    private final Being them = person(new Pos(10, 64, 10));

    private static Being person(Pos at) {
        return new Being(BeingId.of(UUID.randomUUID()), Being.Kind.AGENT, "person", "Bia", null, at, 10,
                Being.HUMANOID_EYE_HEIGHT, true, 1, 0, false, List.of(), Being.Activity.IDLE,
                Being.Locomotion.STILL, false, false, false, false, false, false, Being.Gear.NONE,
                Being.Identified.INDIVIDUAL, Being.Awareness.SEEN);
    }

    @Test
    void eachEighthOfTheCompassHasItsFront() {
        int[][] expected = {{10, 12}, {8, 12}, {8, 10}, {8, 8}, {10, 8}, {12, 8}, {12, 10}, {12, 12}};
        for (int octant = 0; octant < 8; octant++) {
            ctx.percepts.facings.put(them.id(), octant * 45.0);
            Pos spot = FacingSpot.before(ctx, them);
            assertEquals(new Pos(expected[octant][0], 64, expected[octant][1]), spot, "yaw " + octant * 45);
        }
    }

    @Test
    void yawWrapsAndRounds() {
        assertEquals(0, FacingSpot.octant(-10));
        assertEquals(0, FacingSpot.octant(350));
        assertEquals(4, FacingSpot.octant(-180));
        assertEquals(6, FacingSpot.octant(-90));
    }

    @Test
    void aBlockedFrontFallsBackToTheDiagonalsThenTheSides() {
        ctx.percepts.facings.put(them.id(), 0.0); // facing +z
        ctx.percepts.terrain = wallAt(z -> z >= 12); // nothing ahead can be stood in
        Pos spot = FacingSpot.before(ctx, them);
        assertEquals(new Pos(8, 64, 10), spot, "the side, after the front and both diagonals");
    }

    @Test
    void unseenFacingWalksToTheirCellAsBefore() {
        assertEquals(new Pos(10, 64, 10), FacingSpot.before(ctx, them));
    }

    @Test
    void theWalkUpAimsInFront() {
        ctx.percepts.beings = List.of(them);
        ctx.percepts.facings.put(them.id(), 90.0); // facing -x
        SeekCompany seek = new SeekCompany(them.id());
        seek.tick(ctx);
        GoTo walk = assertInstanceOf(GoTo.class, seek.walk());
        assertEquals(new Pos(8, 64, 10), new Pos(walk.x(), walk.y(), walk.z()));
    }

    private NavGrid wallAt(java.util.function.IntPredicate solid) {
        return new NavGrid() {
            @Override
            public CellType cell(int x, int y, int z) {
                return y < 64 || solid.test(z) ? CellType.GROUND : CellType.PASSABLE;
            }
        };
    }
}
