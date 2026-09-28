package dev.luizloyola.anima.mod.nav;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.nav.MoveCapabilities;
import dev.luizloyola.anima.core.nav.MoveType;
import dev.luizloyola.anima.core.nav.Waypoint;
import org.junit.jupiter.api.Test;

/**
 * {@link Navigator#headAboveClimbExit}, on the attic where a body rode a ladder up and down for
 * minutes (flat world, 2026-09-27): a ladder down through the attic floor at y -56 into a room
 * three tall, and a route that steps off the rung at -58 and drops to the floor.
 */
class NavigatorClimbExitTest {

    private static final MoveCapabilities PERSON =
            new MoveCapabilities(1.8, 1, 3, 3, true, 0, true, true);
    private static final Waypoint RUNG = new Waypoint(-31, -58, 88, MoveType.CLIMB);
    private static final Waypoint DROP_OFF = new Waypoint(-31, -59, 87, MoveType.DROP);

    @Test
    void theHeadMustBeUnderTheFloorBeforeTheBodyStepsOff() {
        // -57.6 is where it turned, live: the head still level with the attic floor.
        assertTrue(Navigator.headAboveClimbExit(-57.6, RUNG, DROP_OFF, PERSON));
        assertTrue(Navigator.headAboveClimbExit(-57.1, RUNG, DROP_OFF, PERSON),
                "the standing band alone claims it here");
        assertFalse(Navigator.headAboveClimbExit(-57.8, RUNG, DROP_OFF, PERSON),
                "1.8 tall in a room two cells high: 0.2 of slack");
        assertFalse(Navigator.headAboveClimbExit(-58.3, RUNG, DROP_OFF, PERSON), "below the rung");
    }

    @Test
    void onlyASidewaysLegIsHeldBack() {
        Waypoint nextRung = new Waypoint(-31, -59, 88, MoveType.CLIMB);
        assertFalse(Navigator.headAboveClimbExit(-57.1, RUNG, nextRung, PERSON),
                "further down the same column there is no lip to hit");
    }

    @Test
    void aStepUpOffTheTopMeasuresFromTheLedge() {
        Waypoint top = new Waypoint(0, 70, 0, MoveType.CLIMB);
        Waypoint ledge = new Waypoint(1, 71, 0, MoveType.WALK);
        assertFalse(Navigator.headAboveClimbExit(70.9, top, ledge, PERSON));
    }
}
