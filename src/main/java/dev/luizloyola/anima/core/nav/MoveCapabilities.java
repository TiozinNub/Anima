package dev.luizloyola.anima.core.nav;

import dev.luizloyola.anima.core.agent.AgentProfile;
import dev.luizloyola.anima.core.agent.ProfileAspect;

/**
 * What the body a path is computed for can physically do, as plain data — the neighbour model is
 * parameterized by it, so a one-cell-tall wolf routes under what a settler walks around.
 *
 * <p><b>A snapshot.</b> These are {@link ProfileAspect}s — per species, shiftable by
 * a modifier — but the A* runs OFF the server thread, where a live view could change under it
 * mid-path. {@link #of} takes the reading once, on the calling thread.
 *
 * @param height     body height in blocks — the real hitbox (1.8 for a Person), not a cell count;
 *                   what it costs in cells depends on how high the floor sits inside its own cell,
 *                   which is what {@link #topCell} works out
 * @param jumpHeight how many cells it can jump straight up (only 0 and 1 are modelled)
 * @param maxDrop    how many cells it will willingly fall <em>onto ground</em>; deeper is a hole to
 *                   route around. Water cancels it — the search prices plunges by depth instead
 * @param maxLeap    widest gap (in cells) it can jump at the same level. Gaps of 2+ need a sprint
 *                   run-up: the search sources them a cell further back and walks the approach as a
 *                   {@link MoveType#RUNUP} leg — see {@code Pathfinder.LEAP_RUN_UP}
 * @param canSwim    whether it may enter and cross water; false keeps {@link CellType#WATER}
 *                   impassable. Wading is not swimming — water shallow enough to stand up in is
 *                   ground to the search
 * @param maxSubmerged cells of travel it may make with its head under water before reaching air.
 *                   Body STATE, not shape — read off the breath gauge at request time, so a
 *                   half-drowned body plans a shorter tunnel. Zero refuses submerged travel
 * @param canOpenDoors whether it has a hand to swing a door or a gate with; false leaves a shut
 *                   one a wall — see {@link Doorway}
 * @param canClimb   whether it climbs ladders and vines; false leaves a ladder shaft a hole
 * @param maxLaid    blocks it may lay on one route, to bridge a gap or pillar up. Body STATE, like
 *                   {@code maxSubmerged}: read off the pocket at request time, and zero for a walk
 *                   that did not ask to build. Zero refuses building
 * @param canScale   whether this walk may scale a soft step — cut its lip, climb into the notch,
 *                   put the lip back underfoot. Needs an arm and a hand, and a walk that allows it;
 *                   read at request time like {@code maxLaid}
 * @param treadsFarmland whether this walk may stand on farmland ({@link NavGrid#farmland}). False for
 *                   every walk that did not ask: a body landing on farmland tramples it and pops the
 *                   crop, so only work in the field itself (sowing, harvesting) sets it — see
 *                   {@link #withFarmland}
 * @param canBuild   whether this walk may lay blocks at all — it asked to, and the body has a hand —
 *                   whatever the pocket holds. With {@code maxLaid} short, the search can still say
 *                   how many blocks a route would need ({@link Path#blocksNeeded})
 */
public record MoveCapabilities(double height, int jumpHeight, int maxDrop, int maxLeap,
                               boolean canSwim, int maxSubmerged, boolean canOpenDoors,
                               boolean canClimb, int maxLaid, boolean canScale,
                               boolean treadsFarmland, boolean canBuild) {

    /** A body that may build exactly when it carries something to lay. */
    public MoveCapabilities(double height, int jumpHeight, int maxDrop, int maxLeap,
                            boolean canSwim, int maxSubmerged, boolean canOpenDoors,
                            boolean canClimb, int maxLaid, boolean canScale,
                            boolean treadsFarmland) {
        this(height, jumpHeight, maxDrop, maxLeap, canSwim, maxSubmerged, canOpenDoors, canClimb,
                maxLaid, canScale, treadsFarmland, maxLaid > 0);
    }

    /** A body kept off farmland — every caller that has not said it works the field. */
    public MoveCapabilities(double height, int jumpHeight, int maxDrop, int maxLeap,
                            boolean canSwim, int maxSubmerged, boolean canOpenDoors,
                            boolean canClimb, int maxLaid, boolean canScale) {
        this(height, jumpHeight, maxDrop, maxLeap, canSwim, maxSubmerged, canOpenDoors, canClimb,
                maxLaid, canScale, false);
    }

    /** A body that moves no ground — every caller that has not asked to build or scale. */
    public MoveCapabilities(double height, int jumpHeight, int maxDrop, int maxLeap,
                            boolean canSwim, int maxSubmerged, boolean canOpenDoors,
                            boolean canClimb) {
        this(height, jumpHeight, maxDrop, maxLeap, canSwim, maxSubmerged, canOpenDoors, canClimb,
                0, false);
    }

    /**
     * A body with no hands and no climbing — what a caller asking only about shape and water gets.
     * Neither is assumed: a body that did not declare it cannot do it.
     */
    public MoveCapabilities(double height, int jumpHeight, int maxDrop, int maxLeap,
                            boolean canSwim, int maxSubmerged) {
        this(height, jumpHeight, maxDrop, maxLeap, canSwim, maxSubmerged, false, false);
    }

    /**
     * A body that will not put its head under — the shape numbers plus swimming, and no breath to
     * spend. Most callers ask only about shape, and zero submerged travel cannot drown anybody.
     */
    public MoveCapabilities(double height, int jumpHeight, int maxDrop, int maxLeap,
                            boolean canSwim) {
        this(height, jumpHeight, maxDrop, maxLeap, canSwim, 0);
    }

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
        if (maxSubmerged < 0) {
            throw new IllegalArgumentException("maxSubmerged must be >= 0: " + maxSubmerged);
        }
        if (maxLaid < 0) {
            throw new IllegalArgumentException("maxLaid must be >= 0: " + maxLaid);
        }
    }

    /** The same body with {@code blocks} it may lay on this route, and building only if any. */
    public MoveCapabilities withLaid(int blocks) {
        return new MoveCapabilities(this.height, this.jumpHeight, this.maxDrop, this.maxLeap,
                this.canSwim, this.maxSubmerged, this.canOpenDoors, this.canClimb, blocks,
                this.canScale, this.treadsFarmland, blocks > 0);
    }

    /** The same body on a walk that may build, carrying {@code blocks} it may lay — none included. */
    public MoveCapabilities building(int blocks) {
        return new MoveCapabilities(this.height, this.jumpHeight, this.maxDrop, this.maxLeap,
                this.canSwim, this.maxSubmerged, this.canOpenDoors, this.canClimb, blocks,
                this.canScale, this.treadsFarmland, true);
    }

    /** The same body, allowed or not to scale a soft step on this route. */
    public MoveCapabilities withScaling(boolean scale) {
        return new MoveCapabilities(this.height, this.jumpHeight, this.maxDrop, this.maxLeap,
                this.canSwim, this.maxSubmerged, this.canOpenDoors, this.canClimb, this.maxLaid,
                scale, this.treadsFarmland, this.canBuild);
    }

    /**
     * The same body, allowed or not onto farmland on this route. Work in a field passes
     * {@code true} for the walks inside it; nothing else should (Luiz, 2026-10-01: "any farmland
     * should be avoided unless actively allowed").
     */
    public MoveCapabilities withFarmland(boolean tread) {
        return new MoveCapabilities(this.height, this.jumpHeight, this.maxDrop, this.maxLeap,
                this.canSwim, this.maxSubmerged, this.canOpenDoors, this.canClimb, this.maxLaid,
                this.canScale, tread, this.canBuild);
    }

    /** Reads one body's capabilities out of its resolved profile, here and now. */
    public static MoveCapabilities of(AgentProfile profile) {
        return of(profile, 0);
    }

    /** As {@link #of(AgentProfile)}, with the submerged budget its breath currently affords. */
    public static MoveCapabilities of(AgentProfile profile, int maxSubmerged) {
        return new MoveCapabilities(
                profile.d(ProfileAspect.BODY_HEIGHT),
                profile.i(ProfileAspect.BODY_JUMP_HEIGHT),
                profile.i(ProfileAspect.BODY_MAX_DROP),
                profile.i(ProfileAspect.BODY_MAX_LEAP),
                profile.b(ProfileAspect.BODY_CAN_SWIM),
                maxSubmerged,
                profile.b(ProfileAspect.BODY_CAN_OPEN_DOORS),
                profile.b(ProfileAspect.BODY_CAN_CLIMB));
    }
}
