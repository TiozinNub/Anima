package dev.luizloyola.anima.core.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link SwimAim}, and the scene it was written for: a wade along a pool's lip that drains sideways
 * over a drop, past a log standing beside the next cell (forest, 2026-10-02; gauntlet E11).
 *
 * <p>The scene is stepped with vanilla's own numbers for a mob in water — the current a fixed
 * {@link #PUSH} after normalising, a {@link #STROKE} of input, {@link #DRAG} a tick, a box
 * {@link #HALF} either side of the centre, collision one axis at a time — on a plan of two cells:
 * the one the body is in, which drains toward +z, and the next one along +x, with the log on its
 * +z side.
 */
class SwimAimTest {
    private static final double PUSH = 0.014;
    private static final double STROKE = 0.02;
    private static final double DRAG = 0.8;
    private static final double HALF = 0.3;
    /** The follower's {@code STUCK_LIMIT}: what a wade gets before it is called stalled. */
    private static final int TICKS = 60;
    /** The follower's waypoint radius. */
    private static final double RADIUS = 0.4;

    @Test
    void stillWaterIsStraightAtTheWaypoint() {
        SwimAim aim = SwimAim.toward(3.0, 4.0, 0.0, 0.0, STROKE);
        assertEquals(0.6, aim.x(), 1e-9);
        assertEquals(0.8, aim.z(), 1e-9);
    }

    @Test
    void aCurrentHeadOnIsSwumStraightInto() {
        SwimAim aim = SwimAim.toward(1.0, 0.0, -PUSH, 0.0, STROKE);
        assertEquals(1.0, aim.x(), 1e-9);
        assertEquals(0.0, aim.z(), 1e-9);
    }

    @Test
    void strokeAndCurrentTogetherPointAtTheWaypoint() {
        double dx = 1.0;
        double dz = -0.6;
        SwimAim aim = SwimAim.toward(dx, dz, -PUSH, 0.0, STROKE);
        assertEquals(1.0, Math.hypot(aim.x(), aim.z()), 1e-9, "a direction, not a speed");
        double vx = STROKE * aim.x() - PUSH;
        double vz = STROKE * aim.z();
        assertEquals(0.0, vx * dz - vz * dx, 1e-12, "no drift off the line");
        assertTrue(vx * dx + vz * dz > 0.0, "and still going toward it");
    }

    @Test
    void theWadeIsPinnedAimedStraight() {
        // The scene bites: aimed at the waypoint, the current puts the body in the log's corner.
        assertFalse(reaches(false));
    }

    @Test
    void theWadeIsCrossedAimedAgainstTheCurrent() {
        assertTrue(reaches(true));
    }

    /**
     * Whether a body wading in at a wade's pace, half a body short of the draining cell, gets within
     * the waypoint radius of the next cell's middle inside {@link #TICKS}.
     */
    private static boolean reaches(boolean ferry) {
        Body body = new Body(-0.15, 0.5, 0.06, 0.0);
        double goalX = 1.5;
        double goalZ = 0.5;
        for (int tick = 0; tick < TICKS; tick++) {
            double dx = goalX - body.x;
            double dz = goalZ - body.z;
            if (dx * dx + dz * dz <= RADIUS * RADIUS) {
                return true;
            }
            // Only the draining cell and the spill beside it flow; the cells either side are still.
            double pushZ = body.x + HALF > 0.0 && body.x - HALF < 1.0 ? PUSH : 0.0;
            SwimAim aim = SwimAim.toward(dx, dz, 0.0, ferry ? pushZ : 0.0, STROKE);
            body.vx += STROKE * aim.x();
            body.vz += pushZ + STROKE * aim.z();
            // One axis at a time, the larger first, as vanilla resolves a move.
            if (Math.abs(body.vx) >= Math.abs(body.vz)) {
                body.moveX();
                body.moveZ();
            } else {
                body.moveZ();
                body.moveX();
            }
            body.vx *= DRAG;
            body.vz *= DRAG;
        }
        return false;
    }

    private static final class Body {
        double x;
        double z;
        double vx;
        double vz;

        Body(double x, double z, double vx, double vz) {
            this.x = x;
            this.z = z;
            this.vx = vx;
            this.vz = vz;
        }

        void moveX() {
            if (hitsLog(this.x + this.vx, this.z)) {
                this.vx = 0.0;
            } else {
                this.x += this.vx;
            }
        }

        void moveZ() {
            if (hitsLog(this.x, this.z + this.vz)) {
                this.vz = 0.0;
            } else {
                this.z += this.vz;
            }
        }
    }

    /** Whether a box centred on {@code (x, z)} overlaps the log: the cell x 1..2, z 1..2. */
    private static boolean hitsLog(double x, double z) {
        return x + HALF > 1.0 && x - HALF < 2.0 && z + HALF > 1.0 && z - HALF < 2.0;
    }
}
