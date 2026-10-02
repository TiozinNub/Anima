package dev.luizloyola.anima.core.nav;

import java.util.List;

/**
 * The pathfinder's answer: the waypoints to walk, in order, <em>excluding</em> the start cell.
 *
 * <p>A path may be partial (goal unreachable, or budget spent) routing to the reachable cell
 * closest to the goal rather than nothing, so an agent still makes visible progress, like vanilla.
 * Empty means no progress toward the goal was possible at all.
 *
 * <p>A search that runs out of anywhere to go, rather than out of budget, has enumerated every cell
 * this body can reach: confinement is proved rather than inferred, and carried on every path for
 * free.
 *
 * @param waypoints   steps to take, in order; empty when start == goal or nothing was reachable
 * @param reachedGoal whether the last waypoint is the requested goal cell
 * @param sealed      whether the search proved this body cannot leave the region it is in — the
 *                    open set emptied and nothing but the world itself stopped it (see the guards
 *                    in {@code Pathfinder}). Never true alongside {@code reachedGoal}
 * @param reachableCells how many cells the search closed. A statement about the whole reachable
 *                    region only when {@code sealed}; otherwise just how far the search got
 * @param restCells   how many of those the body could stop in — on its feet, not afloat and not
 *                    hanging on a ladder. A search over a flooded channel closes a thousand cells and
 *                    finds three of these
 * @param taken       blocks the route puts in the hand: a carved lip, a pillar block gone down or
 *                    taken to climb beside it — see {@link #spent()}
 * @param trapped     whether the route leaves the body somewhere walled in: it drops further than
 *                    the body climbs, and where it ends is proven to have no way out. A pit, a
 *                    moat — see {@link Pathfinder#find}
 * @param blocksNeeded on a route that left a body that may build stranded: the blocks in hand the
 *                    route a fuller pocket would walk needs at its start, more than it carries. Zero
 *                    when that was not asked, or no number of blocks would get it there
 */
public record Path(List<Waypoint> waypoints, boolean reachedGoal, boolean sealed,
                   int reachableCells, int restCells, int taken, boolean trapped,
                   int blocksNeeded) {
    public Path {
        waypoints = List.copyOf(waypoints);
    }

    /** A searched path, asked whether it traps the body, with no word on blocks. */
    public Path(List<Waypoint> waypoints, boolean reachedGoal, boolean sealed, int reachableCells,
                int restCells, int taken, boolean trapped) {
        this(waypoints, reachedGoal, sealed, reachableCells, restCells, taken, trapped, 0);
    }

    /** A searched path, not yet asked whether it traps the body. */
    public Path(List<Waypoint> waypoints, boolean reachedGoal, boolean sealed, int reachableCells,
                int restCells, int taken) {
        this(waypoints, reachedGoal, sealed, reachableCells, restCells, taken, false);
    }

    /** A searched path that takes nothing into the hand. */
    public Path(List<Waypoint> waypoints, boolean reachedGoal, boolean sealed, int reachableCells,
                int restCells) {
        this(waypoints, reachedGoal, sealed, reachableCells, restCells, 0);
    }

    /**
     * A path with nothing to say about confinement — for callers that rebuild one rather than
     * searching (a saved walk coming back, a trivially empty result). Not sealed, because nobody
     * looked.
     */
    public Path(List<Waypoint> waypoints, boolean reachedGoal) {
        this(waypoints, reachedGoal, false, 0, 0);
    }

    /** This path, found to leave the body somewhere it cannot walk out of. */
    public Path trap() {
        return new Path(this.waypoints, this.reachedGoal, this.sealed, this.reachableCells,
                this.restCells, this.taken, true, this.blocksNeeded);
    }

    /** This path, saying how many blocks would have got the body there — see {@link #blocksNeeded}. */
    public Path needing(int blocks) {
        return new Path(this.waypoints, this.reachedGoal, this.sealed, this.reachableCells,
                this.restCells, this.taken, this.trapped, blocks);
    }

    public boolean isEmpty() {
        return this.waypoints.isEmpty();
    }

    public Waypoint last() {
        return this.waypoints.get(this.waypoints.size() - 1);
    }

    /**
     * What walking this route costs the pocket: the blocks it lays less the blocks it takes into the
     * hand. Below zero when it gains, as a carve or a pillar gone down does.
     */
    public int spent() {
        return laid() - this.taken;
    }

    /** How many blocks walking this route lays — see {@link MoveType#lays()}. */
    public int laid() {
        int laid = 0;
        for (Waypoint waypoint : this.waypoints) {
            if (waypoint.move().lays()) {
                laid++;
            }
        }
        return laid;
    }
}
