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
 */
public record Path(List<Waypoint> waypoints, boolean reachedGoal, boolean sealed,
                   int reachableCells) {
    public Path {
        waypoints = List.copyOf(waypoints);
    }

    /**
     * A path with nothing to say about confinement — for callers that rebuild one rather than
     * searching (a saved walk coming back, a trivially empty result). Not sealed, because nobody
     * looked.
     */
    public Path(List<Waypoint> waypoints, boolean reachedGoal) {
        this(waypoints, reachedGoal, false, 0);
    }

    public boolean isEmpty() {
        return this.waypoints.isEmpty();
    }

    public Waypoint last() {
        return this.waypoints.get(this.waypoints.size() - 1);
    }
}
