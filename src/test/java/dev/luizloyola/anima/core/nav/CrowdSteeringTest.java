package dev.luizloyola.anima.core.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.nav.CrowdSteering.Neighbour;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The avoidance turn, in the plane. Every case here walks the same way — from the origin toward
 * +Z, which in Minecraft is south — so the body's right hand points at −X (west). A neighbour at a
 * negative x is therefore on our right and should be passed on the left (a negative answer); one at
 * a positive x is on our left and should be passed on the right (positive).
 */
class CrowdSteeringTest {

    /** A person-sized body. */
    private static final double R = 0.3;

    private static double turn(double x, double z, double dirX, double dirZ, Neighbour... crowd) {
        return CrowdSteering.deflection(x, z, dirX, dirZ, R, List.of(crowd));
    }

    /** Walking +Z from the origin, into whoever is passed in. */
    private static double ahead(Neighbour... crowd) {
        return turn(0.0, 0.0, 0.0, 1.0, crowd);
    }

    private static Neighbour body(double x, double z) {
        return new Neighbour(x, z, R);
    }

    @Test
    void anEmptyRoadIsNoTurnAtAll() {
        assertEquals(0.0, ahead());
    }

    @Test
    void aBodyDeadAheadBearsRight() {
        // Identical lateral offset. That is what spawning on whole coordinates produces. Zero
        // here is the bug — the walker shoves them down the corridor.
        assertTrue(ahead(body(0.0, 1.0)) > 0.0);
    }

    @Test
    void twoBodiesDeadAheadOfEachOtherBothBearRightAndSoPass() {
        // Mirror images walking into each other down the same column. Both answers must be
        // positive: each turns to its own right, which is opposite sides of the corridor, so they
        // pass. A rule that agreed on a compass direction would have them mirror into a deadlock.
        double outbound = turn(0.0, 0.0, 0.0, 1.0, body(0.0, 1.2));
        double inbound = turn(0.0, 1.2, 0.0, -1.0, body(0.0, 0.0));
        assertTrue(outbound > 0.0);
        assertTrue(inbound > 0.0);
    }

    @Test
    void aBodyLeaningRightIsPassedOnTheLeft() {
        assertTrue(ahead(body(-0.4, 1.0)) < 0.0);
    }

    @Test
    void aBodyLeaningLeftIsPassedOnTheRight() {
        assertTrue(ahead(body(0.4, 1.0)) > 0.0);
    }

    @Test
    void aBodyBehindIsNotSteeredAround() {
        assertEquals(0.0, ahead(body(0.0, -1.0)));
    }

    @Test
    void aBodyWellToTheSideIsWalkedPastNotAround() {
        // Outside the corridor (two radii plus clearance): we miss them without trying.
        assertEquals(0.0, ahead(body(1.5, 1.0)));
    }

    @Test
    void aBodyBeyondReachIsNotYetAConcern() {
        assertEquals(0.0, ahead(body(0.0, CrowdSteering.REACH + 2 * R + 0.1)));
    }

    @Test
    void theTurnTightensAsTheGapCloses() {
        double far = ahead(body(0.0, 2.0));
        double near = ahead(body(0.0, 1.0));
        double touching = ahead(body(0.0, 0.61));
        assertTrue(far > 0.0);
        assertTrue(near > far);
        assertTrue(touching > near);
    }

    @Test
    void theTurnIsCappedWellShortOfARightAngle() {
        // Three bodies piled directly ahead still cannot spin us sideways: the clamp is what keeps
        // a deflected body making progress toward its waypoint instead of orbiting.
        double packed = ahead(body(0.0, 0.7), body(0.0, 0.9), body(0.0, 1.1));
        assertTrue(packed > 0.0);
        assertTrue(packed < Math.toRadians(45.0), "deflected " + Math.toDegrees(packed) + "°");
    }

    @Test
    void oneEachSideThreadsTheGapBetweenThem() {
        double both = ahead(body(-0.45, 1.0), body(0.45, 1.0));
        assertEquals(0.0, both, 1.0e-9);
    }

    @Test
    void abodyDrawingLevelBesideUsBarelyRegisters() {
        // Ahead by a hair, a full corridor width to the side: the bearing falloff is what stops two
        // agents walking a street side by side from shying away from each other the whole way.
        double alongside = Math.abs(ahead(body(-0.7, 0.05)));
        double infront = Math.abs(ahead(body(0.0, 0.7)));
        assertTrue(alongside < 0.2 * infront,
                "alongside " + Math.toDegrees(alongside) + "° vs in front " + Math.toDegrees(infront) + "°");
    }

    @Test
    void standingInsideEachOtherStillPicksASide() {
        assertTrue(ahead(body(0.0, 0.0)) > 0.0);
    }

    @Test
    void aDegenerateHeadingAsksForNoTurn() {
        assertEquals(0.0, turn(0.0, 0.0, 0.0, 0.0, body(0.0, 1.0)));
    }

    @Test
    void theHeadingNeedNotBeNormalised() {
        assertEquals(ahead(body(0.0, 1.0)), turn(0.0, 0.0, 0.0, 37.0, body(0.0, 1.0)), 1.0e-12);
    }

    @Test
    void aWideBodyIsGivenAWiderBerth() {
        // Same centre distance, bigger disc: less gap left, so a firmer turn.
        double slim = ahead(new Neighbour(0.0, 1.6, 0.3));
        double broad = ahead(new Neighbour(0.0, 1.6, 1.0));
        assertTrue(broad > slim);
    }
}
