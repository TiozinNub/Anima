package dev.luizloyola.anima.core.nav;

import dev.luizloyola.anima.core.agent.AgentProfile;
import dev.luizloyola.anima.core.agent.ProfileAspect;

/**
 * What the body a path is computed for can physically do, as plain data: the neighbour model is
 * parameterized by this rather than hard-coding any particular creature.
 *
 * <p><b>A snapshot.</b> These five are {@link ProfileAspect}s and can shift under a
 * modifier, but the A* runs OFF the server thread, so {@link #of} reads them once on the calling
 * thread.
 *
 * @param height     body height in blocks — the real hitbox (1.8 for a Person), not a cell count;
 *                   {@link #topCell} works out what it costs in cells
 * @param jumpHeight cells it can jump straight up (only 0 and 1 are modelled)
 * @param maxDrop    cells it will willingly fall; anything deeper is a hole to route around
 * @param maxLeap    widest gap (cells) at the same level; 2+ needs a sprint run-up, so the engine
 *                   also demands an aligned approach cell
 * @param canSwim    whether it may cross water; false keeps {@link CellType#WATER} impassable.
 *                   Surface crossing only — a dive capability would add depth/breath fields
 */
public record MoveCapabilities(double height, int jumpHeight, int maxDrop, int maxLeap,
                               boolean canSwim) {

    /**
     * How far up a body walks without jumping — vanilla's step height, which a Person keeps at the
     * living default of 0.6 (see {@code Person.createAttributes}). Slabs, snow layers, dirt paths
     * and carpets are all under it, so crossing them is a walk, not a hop.
     *
     * <p>Not a record field because no species varies it. It lives here because the search (step
     * versus jump) and the classifier (whether a floor sunk inside a block can be walked into — a
     * cauldron rim stands a full block over its bottom, a hopper rim does not) need the same 0.6,
     * and two copies would drift.
     */
    public static final double STEP_UP = 0.6;

    /**
     * The topmost cell this body occupies, as an offset from its feet-cell, when its feet rest
     * {@code surface} of a block into that cell.
     *
     * <p>Why the height is a real number, not a count: a 1.8-tall Person reaches one cell above its
     * feet standing flat or on a carpet (0.0625), two on a bottom slab (0.5), because 0.5 + 1.8
     * crosses the next boundary. Counting 2 whole cells charged every raised floor an extra cell,
     * which made a carpeted two-block doorway unwalkable.
     */
    public int topCell(double surface) {
        return (int) Math.ceil(surface + this.height) - 1;
    }

    /** How many whole cells of clear column this body needs standing flat on a full block. */
    public int clearCells() {
        return topCell(0.0) + 1;
    }

    public MoveCapabilities {
        if (height <= 0) throw new IllegalArgumentException("height must be > 0: " + height);
        if (jumpHeight < 0) throw new IllegalArgumentException("jumpHeight must be >= 0: " + jumpHeight);
        if (maxDrop < 0) throw new IllegalArgumentException("maxDrop must be >= 0: " + maxDrop);
        if (maxLeap < 0) throw new IllegalArgumentException("maxLeap must be >= 0: " + maxLeap);
    }

    /** Reads one body's capabilities out of its resolved profile, here and now. */
    public static MoveCapabilities of(AgentProfile profile) {
        return new MoveCapabilities(
                profile.d(ProfileAspect.BODY_HEIGHT),
                profile.i(ProfileAspect.BODY_JUMP_HEIGHT),
                profile.i(ProfileAspect.BODY_MAX_DROP),
                profile.i(ProfileAspect.BODY_MAX_LEAP),
                profile.b(ProfileAspect.BODY_CAN_SWIM));
    }
}
