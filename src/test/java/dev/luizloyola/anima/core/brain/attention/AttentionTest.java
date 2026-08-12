package dev.luizloyola.anima.core.brain.attention;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.luizloyola.anima.core.agent.AgentProfile;
import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.agent.TestSpecies;
import dev.luizloyola.anima.core.brain.knowledge.AgentKnowledge;
import dev.luizloyola.anima.core.brain.sense.DangerTable;
import dev.luizloyola.anima.core.brain.task.FakePercepts;
import java.util.Random;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The idle half of the gaze organ: a look is HELD rather than re-decided every tick, its dwell
 * lands inside the species' declared span, and the point it aims at is a direction off the
 * shoulders at the scan's own reach.
 *
 * <p>The organ eases a head toward whatever this returns; a picker that re-decided each tick would
 * give it nothing to arrive at.
 */
class AttentionTest {

    private static final AgentProfile PROFILE = TestSpecies.PROFILE;
    private static final double EYE_X = 100.5;
    private static final double EYE_Y = 65.6;
    private static final double EYE_Z = -40.5;

    /**
     * An empty scene (no bodies, no drops, no remembered places), so every test is of the scan,
     * not of what stood nearby.
     */
    private static Attention.Focus tick(Attention attention, double bodyYaw, long now,
            RandomGenerator random) {
        return attention.tick(EYE_X, EYE_Y, EYE_Z, bodyYaw, now, new FakePercepts(),
                new AgentKnowledge(), DangerTable.NEUTRAL, PROFILE, random);
    }

    @Test
    @DisplayName("a look is held for its whole dwell, then a new one is rolled")
    void aLookIsHeldForItsDwell() {
        Attention attention = new Attention();
        RandomGenerator random = new Random(1);
        Attention.Focus first = tick(attention, 0.0, 0L, random);
        for (long now = 1; now < first.until(); now++) {
            assertSame(first, tick(attention, 0.0, now, random),
                    "the same look must come back every tick of its dwell — the organ eases toward "
                            + "it, and a picker that re-decided each tick would be a twitch");
        }
        Attention.Focus next = tick(attention, 0.0, first.until(), random);
        assertNotEquals(first, next, "the dwell expired; a fresh look was due");
    }

    @Test
    @DisplayName("the dwell lands inside the species' declared span")
    void dwellIsWithinTheSpeciesSpan() {
        int min = PROFILE.i(ProfileAspect.GAZE_SCAN_MIN_TICKS);
        int max = PROFILE.i(ProfileAspect.GAZE_SCAN_MAX_TICKS);
        Attention attention = new Attention();
        RandomGenerator random = new Random(7);
        long now = 0;
        for (int i = 0; i < 200; i++) {
            Attention.Focus focus = tick(attention, 0.0, now, random);
            long dwell = focus.until() - now;
            assertTrue(dwell >= min && dwell < max,
                    "dwell " + dwell + " outside the declared [" + min + ", " + max + ")");
            now = focus.until();
        }
    }

    @Test
    @DisplayName("the point looked at is a bearing off the shoulders, at the scan's reach")
    void theScannedPointIsADirectionAtReach() {
        Attention attention = new Attention();
        RandomGenerator random = new Random(11);
        long now = 0;
        for (int i = 0; i < 200; i++) {
            Attention.Focus focus = tick(attention, 0.0, now, random);
            double dx = focus.x() - EYE_X;
            double dy = focus.y() - EYE_Y;
            double dz = focus.z() - EYE_Z;
            assertEquals(Attention.SCAN_DISTANCE, Math.sqrt(dx * dx + dy * dy + dz * dz), 1.0e-9,
                    "a scan is a direction made concrete — always its own reach out");
            double bearing = Math.toDegrees(Math.atan2(-dx, dz));
            assertTrue(Math.abs(bearing) <= Attention.arc(PROFILE) + 1.0e-9,
                    "bearing " + bearing + "° is outside the scan arc");
            double pitch = Math.toDegrees(Math.asin(-dy / Attention.SCAN_DISTANCE));
            assertTrue(Math.abs(pitch) <= Attention.SCAN_PITCH_DEGREES + 1.0e-9,
                    "pitch " + pitch + "° is steeper than an idle glance");
            now = focus.until();
        }
    }

    @Test
    @DisplayName("the arc is measured off the shoulders, so a turned body scans a turned arc")
    void theArcFollowsTheBody() {
        Attention facing = new Attention();
        Attention turned = new Attention();
        // Same stream, same tick, only the body turned: the points must differ by that
        // rotation. Off the BODY and not the head is what stops rolls compounding until a standing
        // body slowly revolves.
        Attention.Focus north = tick(facing, 0.0, 0L, new Random(3));
        Attention.Focus east = tick(turned, 90.0, 0L, new Random(3));
        double bearingNorth = Math.toDegrees(Math.atan2(-(north.x() - EYE_X), north.z() - EYE_Z));
        double bearingEast = Math.toDegrees(Math.atan2(-(east.x() - EYE_X), east.z() - EYE_Z));
        assertEquals(90.0, bearingEast - bearingNorth, 1.0e-6);
    }

    @Test
    @DisplayName("an idle scan stays inside the neck, so idling never pivots the body")
    void theScanNeverAsksTheShouldersToMove() {
        int twist = PROFILE.i(ProfileAspect.GAZE_MAX_TWIST_DEGREES);
        assertTrue(Attention.arc(PROFILE) <= twist,
                "a scan wider than the neck turns every idle glance into a pivot");
        Attention attention = new Attention();
        RandomGenerator random = new Random(13);
        long now = 0;
        for (int i = 0; i < 300; i++) {
            Attention.Focus focus = tick(attention, 0.0, now, random);
            Aim aim = Aim.of(focus.x() - EYE_X, focus.y() - EYE_Y, focus.z() - EYE_Z,
                    0.0F, 0.0F, 0.0F, 12.0F, twist, true, false);
            assertFalse(aim.turning(), "an idle scan asked the shoulders to come round");
            assertTrue(aim.reachable(), "an idle scan the body cannot even look at");
            now = focus.until();
        }
    }

    @Test
    @DisplayName("clearing drops the look, so the next tick rolls from where the body is now")
    void clearingForcesAFreshRoll() {
        Attention attention = new Attention();
        RandomGenerator random = new Random(5);
        Attention.Focus first = tick(attention, 0.0, 0L, random);
        assertSame(first, attention.current());
        attention.clear();
        assertNull(attention.current(), "a cleared attention is looking at nothing");
        Attention.Focus afresh = tick(attention, 0.0, 1L, random);
        assertNotEquals(first, afresh, "the roll happened again rather than resuming the old look");
    }
}
