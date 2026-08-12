package dev.luizloyola.anima.core.brain.attention;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The geometry of looking: how far round a head gets this tick, and whether the shoulders come with
 * it. None of it shows inside one tick — a head leading by the wrong sign, an ease taking the long
 * way round the compass, and a walking body holding a look it cannot hold all read as "the Person
 * is looking at something" until you watch for a second or two.
 */
class AimTest {

    private static final float STEP = 12.0F;
    private static final float TWIST = 60.0F;

    /** Straight ahead of a body squared to yaw 0 (Minecraft: +Z), at eye level. */
    private static Aim ahead(float headYaw, float bodyYaw, boolean bodyFree) {
        return Aim.of(0.0, 0.0, 10.0, headYaw, bodyYaw, 0.0F, STEP, TWIST, bodyFree, false);
    }

    /** Something directly behind a body squared to yaw 0 — 180° of twist against a 60° neck. */
    private static Aim behind(float headYaw, float bodyYaw, boolean bodyFree, boolean turning) {
        return Aim.of(0.0, 0.0, -10.0, headYaw, bodyYaw, 0.0F, STEP, TWIST, bodyFree, turning);
    }

    @Test
    @DisplayName("a head eases toward what it looks at, never more than its own turn in a tick")
    void theHeadEasesAtItsOwnPace() {
        Aim aim = ahead(-90.0F, 0.0F, false);
        assertEquals(-78.0F, aim.headYaw(), 1.0e-4, "a step of exactly the body's turn rate");
        assertFalse(aim.turning(), "the target is straight ahead — there is nothing to turn to");
        // ...and arriving, it stops rather than overshooting.
        assertEquals(0.0F, ahead(-6.0F, 0.0F, false).headYaw(), 1.0e-4);
        assertEquals(0.0F, ahead(0.0F, 0.0F, false).headYaw(), 1.0e-4);
    }

    @Test
    @DisplayName("turning takes the short way round the compass, not the long way")
    void turningTakesTheShortWay() {
        // Head at 179°, target at -179°: two degrees apart across the seam, not 358.
        Aim aim = Aim.of(Math.sin(Math.toRadians(1.0)) * 10.0, 0.0, -10.0 * Math.cos(0.0),
                179.0F, 179.0F, 0.0F, STEP, TWIST, false, false);
        assertTrue(Math.abs(Aim.wrap(aim.headYaw() - 179.0F)) <= STEP + 1.0e-4,
                "the head went the long way: " + aim.headYaw());
        assertEquals(-2.0F, Aim.wrap(358.0F), 1.0e-4);
        assertEquals(2.0F, Aim.wrap(-358.0F), 1.0e-4);
        assertEquals(-180.0F, Aim.wrap(180.0F), 1.0e-4, "the seam belongs to the negative side");
    }

    @Test
    @DisplayName("a walking body will not look behind itself, and says so")
    void aWalkingBodyDropsWhatItCannotSee() {
        Aim aim = behind(0.0F, 0.0F, false, false);
        assertFalse(aim.reachable(), "past the neck with the legs driving — this is not a look");
        assertFalse(aim.turning(), "the shoulders are the legs': stealing them steers the body");
        assertEquals(0.0F, aim.bodyYaw(), 1.0e-4);
        // It still turns as far as it goes, and stops exactly at the limit rather than creeping on.
        float head = 0.0F;
        for (int tick = 0; tick < 40; tick++) {
            head = behind(head, 0.0F, false, false).headYaw();
        }
        assertEquals(TWIST, Math.abs(Aim.wrap(head)), 1.0e-3, "parked at the neck's limit");
    }

    @Test
    @DisplayName("a standing body turns after its head, and the head stays in front")
    void aStandingBodyFollowsItsHeadRound() {
        float head = 0.0F;
        float body = 0.0F;
        boolean turning = false;
        boolean led = false;
        for (int tick = 0; tick < 80; tick++) {
            Aim aim = behind(head, body, true, turning);
            head = aim.headYaw();
            body = aim.bodyYaw();
            turning = aim.turning();
            float twist = Math.abs(Aim.wrap(head - body));
            assertTrue(twist <= TWIST + 1.0e-3, "the neck went past its limit: " + twist);
            if (twist > 1.0F) {
                led = true; // the head got there first. That is what makes the turn read as a turn
            }
        }
        assertTrue(led, "the shoulders moved in lockstep with the head — nothing led");
        assertEquals(180.0F, Math.abs(Aim.wrap(head)), 1.0e-3, "the head arrived");
        assertEquals(180.0F, Math.abs(Aim.wrap(body)), 1.0e-3,
                "and the body ended square behind it, rather than parked at the twist limit — "
                        + "which is exactly what vanilla's own clamp would have left it doing");
        assertFalse(turning, "the turn is over; the latch must drop or the next glance pivots too");
    }

    @Test
    @DisplayName("the shoulders follow through once started, instead of stopping at the limit")
    void theShouldersFinishTheTurnTheyStarted() {
        // Only 45° off — inside the neck, so nothing would START a turn here. Mid-turn it must
        // keep going: the latch is what leaves a body facing what it looked at rather than
        // permanently wrenched.
        Aim carrying = Aim.of(-10.0, 0.0, 10.0, 0.0F, 0.0F, 0.0F, STEP, TWIST, true, true);
        assertTrue(carrying.turning(), "a turn under way does not stop because the neck is comfy");
        assertEquals(STEP * Aim.BODY_FOLLOW, Math.abs(Aim.wrap(carrying.bodyYaw())), 1.0e-3);

        Aim starting = Aim.of(-10.0, 0.0, 10.0, 0.0F, 0.0F, 0.0F, STEP, TWIST, true, false);
        assertFalse(starting.turning(), "45° is a glance, and a glance is head-only");
        assertEquals(0.0F, starting.bodyYaw(), 1.0e-4);
    }

    @Test
    @DisplayName("something straight underfoot is looked DOWN at, not spun toward")
    void straightUnderfootKeepsItsBearing() {
        Aim aim = Aim.of(0.001, -1.5, -0.002, 137.0F, 137.0F, 0.0F, STEP, TWIST, true, false);
        assertEquals(137.0F, aim.headYaw(), 1.0e-4, "no bearing worth turning to — the yaw stands");
        assertFalse(aim.turning());
        assertEquals(STEP, aim.pitch(), 1.0e-4, "and the whole look is in the tilt, one step of it");
    }

    @Test
    @DisplayName("the tilt stops short of a snapped neck")
    void pitchIsClampedShortOfVertical() {
        float pitch = 0.0F;
        for (int tick = 0; tick < 40; tick++) {
            pitch = Aim.of(0.0, -100.0, 0.5, 0.0F, 0.0F, pitch, STEP, TWIST, true, false).pitch();
        }
        assertEquals(Aim.MAX_PITCH, pitch, 1.0e-3);
        float up = 0.0F;
        for (int tick = 0; tick < 40; tick++) {
            up = Aim.of(0.0, 100.0, 0.5, 0.0F, 0.0F, up, STEP, TWIST, true, false).pitch();
        }
        assertEquals(-Aim.MAX_PITCH, up, 1.0e-3);
    }
}
