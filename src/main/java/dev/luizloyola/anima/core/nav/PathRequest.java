package dev.luizloyola.anima.core.nav;

import dev.luizloyola.anima.core.brain.sense.DangerField;

/**
 * One pathfinding question: from the start cell (where the agent's feet are) to the goal cell,
 * for an agent with the given {@link MoveCapabilities capabilities}, expanding at most
 * {@code maxNodes} cells before settling for a partial path — and, optionally, what that agent
 * would rather not walk past on the way.
 */
public record PathRequest(
        int startX, int startY, int startZ,
        int goalX, int goalY, int goalZ,
        MoveCapabilities profile, DangerField danger, NavDomain domain, int maxNodes) {

    /**
     * Default search budget. At ~8 neighbour probes per expansion this bounds worst-case work per
     * request to a few tens of thousands of grid reads (milliseconds) while comfortably covering
     * any route inside the snapshot boxes v1 uses.
     */
    public static final int DEFAULT_MAX_NODES = 4096;

    public PathRequest {
        if (maxNodes < 1) throw new IllegalArgumentException("maxNodes must be >= 1: " + maxNodes);
        if (danger == null) {
            danger = DangerField.NONE;
        }
        if (domain == null) {
            domain = NavDomain.EVERYWHERE;
        }
    }

    /** A route for a body with nothing to be afraid of — every test, and most of the world. */
    public static PathRequest of(int startX, int startY, int startZ,
                                 int goalX, int goalY, int goalZ, MoveCapabilities profile) {
        return of(startX, startY, startZ, goalX, goalY, goalZ, profile, DangerField.NONE);
    }

    /**
     * The same route, fenced: the search may only stand inside {@code domain} (see
     * {@link NavDomain} — outside it there is no world, not merely a worse one). Snapshot
     * semantics are the danger field's: built on the server thread, immutable for the worker.
     */
    public PathRequest within(NavDomain domain) {
        return new PathRequest(startX, startY, startZ, goalX, goalY, goalZ,
                profile, danger, domain, maxNodes);
    }

    /**
     * A route that would rather go round what the body knows to fear.
     *
     * <p>The field is a SNAPSHOT taken on the server thread: the search runs on a worker and cannot
     * read a live memory store, and fears changing mid-path would be worse than stale ones.
     */
    public static PathRequest of(int startX, int startY, int startZ,
                                 int goalX, int goalY, int goalZ, MoveCapabilities profile,
                                 DangerField danger) {
        return new PathRequest(startX, startY, startZ, goalX, goalY, goalZ, profile, danger,
                NavDomain.EVERYWHERE, DEFAULT_MAX_NODES);
    }
}
