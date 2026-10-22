package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.sense.Being;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.nav.MoveCapabilities;
import java.util.OptionalDouble;

/**
 * Where to stand to talk to someone: in front of their face, so they see who speaks. A walk up to
 * their own cell ends wherever the route met them, beside or behind, and a player looking ahead
 * missed the greeting (breaking-in spec, decision 3).
 */
final class FacingSpot {

    static final int AHEAD = 2;

    /** Steps for each eighth of the compass, from yaw 0 (+z) turning as Minecraft's yaw does. */
    private static final int[][] STEP = {{0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}, {1, 0}, {1, 1}};

    /** The front first, then the diagonals beside it, then their sides. */
    private static final int[] TURNS = {0, 1, -1, 2, -2};

    private FacingSpot() {
    }

    /** The cell in front of {@code being}, or the floor under them when none in front can be stood on. */
    static Pos before(BrainContext ctx, Being being) {
        Pos under = Standing.floorUnder(ctx, being.pos());
        OptionalDouble yaw = ctx.percepts().facing(being.id());
        if (yaw.isEmpty()) {
            return under;
        }
        MoveCapabilities body = MoveCapabilities.of(ctx.profile());
        int octant = octant(yaw.getAsDouble());
        for (int turn : TURNS) {
            int[] step = STEP[Math.floorMod(octant + turn, 8)];
            Pos at = Standing.floorUnder(ctx, new Pos(under.x() + step[0] * AHEAD, under.y(), under.z() + step[1] * AHEAD));
            if (Standing.standable(ctx.percepts().terrain(), body, at.x(), at.y(), at.z())) {
                return at;
            }
        }
        return under;
    }

    /** Which eighth of the compass a yaw faces, 0 for +z. */
    static int octant(double yaw) {
        return Math.floorMod((int) Math.round(yaw / 45.0), 8);
    }
}
