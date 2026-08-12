package dev.luizloyola.anima.core.nav;

/**
 * One cell a path leg's completion depends on: for the plan to hold, the live world at
 * {@code (x, y, z)} must still satisfy {@link #need()}. {@link PathIntegrity} derives a handful per
 * {@link Waypoint} and the follower re-checks those a few nodes ahead as it steps, so terrain
 * edited out from under a plan (a floor mined away, a swim lane drained) triggers a re-path before
 * the body walks into it.
 */
public record CellNeed(int x, int y, int z, Need need) {

    /**
     * What the world has to keep providing at a cell. Demands, not classifications:
     * {@link #FOOTING} takes a full block underfoot <em>or</em> a slab in the cell itself, so
     * laying a carpet along a route does not read as the route breaking. Pinning a
     * {@link CellType} would re-path on any change at all.
     */
    public enum Need {
        /** A body can still stand in this feet-cell — however the floor under it is built. */
        FOOTING,
        /** Nothing has moved into this part of the body's column. */
        CLEAR,
        /** Still swimmable: the lane has not been drained or filled. */
        WATER,
        /**
         * The body still fits here, wet or dry — {@link #CLEAR}'s twin for the stretch of a route
         * that runs under the surface. A swimmer's own column is water, so demanding air of it
         * re-planned on the first tick of every submerged leg.
         */
        ROOM
    }
}
