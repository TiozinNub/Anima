package dev.luizloyola.anima.core.nav;

import dev.luizloyola.anima.core.brain.sense.DangerField;
import dev.luizloyola.anima.core.brain.sense.SetbackField;

/**
 * One pathfinding question: from the start cell (where the agent's feet are) to the goal cell,
 * for an agent with the given {@link MoveCapabilities capabilities}, expanding at most
 * {@code maxNodes} cells before settling for a partial path — and, optionally, what that agent
 * would rather not walk past on the way.
 *
 * @param variety which of the equally cheap routes this agent prefers — see {@link #variety()}.
 * @param pillars the recorded pillar blocks around the route ({@link LaidBlocks}), as packed cells:
 *                a body that may scale climbs beside one or goes down one, spending nothing
 * @param caution how the walk weighs ground that could hurt it — see {@link Caution}
 */
public record PathRequest(
        int startX, int startY, int startZ,
        int goalX, int goalY, int goalZ,
        MoveCapabilities profile, DangerField danger, NavDomain domain, int maxNodes,
        long variety, SetbackField setbacks, java.util.Set<Long> pillars, HandsOff handsOff,
        Caution caution) {

    /** A request that knows of no recorded pillars — every caller that has not been told of any. */
    public PathRequest(int startX, int startY, int startZ, int goalX, int goalY, int goalZ,
                       MoveCapabilities profile, DangerField danger, NavDomain domain, int maxNodes,
                       long variety, SetbackField setbacks) {
        this(startX, startY, startZ, goalX, goalY, goalZ, profile, danger, domain, maxNodes, variety,
                setbacks, java.util.Set.of(), HandsOff.NONE, Caution.NONE);
    }

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
        if (setbacks == null) {
            setbacks = SetbackField.NONE;
        }
        pillars = pillars == null ? java.util.Set.of() : java.util.Set.copyOf(pillars);
        if (handsOff == null) {
            handsOff = HandsOff.NONE;
        }
        if (caution == null) {
            caution = Caution.NONE;
        }
    }

    /** The same route, weighing harm as {@code caution} says. */
    public PathRequest cautious(Caution caution) {
        return new PathRequest(startX, startY, startZ, goalX, goalY, goalZ,
                profile, danger, domain, maxNodes, variety, setbacks, pillars, handsOff, caution);
    }

    /** The same route, laying and cutting nothing in these columns — see {@link HandsOff}. */
    public PathRequest keepingOff(HandsOff handsOff) {
        return new PathRequest(startX, startY, startZ, goalX, goalY, goalZ,
                profile, danger, domain, maxNodes, variety, setbacks, pillars, handsOff, caution);
    }

    /** The same route, knowing of these recorded pillar blocks — see {@link #pillars()}. */
    public PathRequest near(java.util.Set<Long> pillars) {
        return new PathRequest(startX, startY, startZ, goalX, goalY, goalZ,
                profile, danger, domain, maxNodes, variety, setbacks, pillars, handsOff, caution);
    }

    /**
     * The same route, computed by a body that remembers where it has lately been beaten — a retry
     * is a different question, not the same one again. A SNAPSHOT like the danger field: server
     * thread, immutable for the worker.
     */
    public PathRequest avoiding(SetbackField setbacks) {
        return new PathRequest(startX, startY, startZ, goalX, goalY, goalZ,
                profile, danger, domain, maxNodes, variety, setbacks, pillars, handsOff, caution);
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
                profile, danger, domain, maxNodes, variety, setbacks, pillars, handsOff, caution);
    }

    /**
     * The same route in this agent's own voice: two bodies sent to one place walk different lines
     * instead of wearing one rut. Zero (the default, and every test) is the canonical search, bit
     * for bit.
     *
     * <p>The seed is an opinion about ground, not routes: it slightly shifts what each patch of
     * terrain costs this body ({@code Pathfinder.roughness}), so the route stays the cheapest
     * <em>this</em> body knows of and within a guaranteed 3% of the cheapest there is — an order of
     * magnitude better in practice.
     *
     * <p>Seed from something <em>permanent</em> about the agent, never per request: a body re-plans
     * halfway to the market, and one re-drawing its opinion of the ground would dither.
     */
    public PathRequest varying(long variety) {
        return new PathRequest(startX, startY, startZ, goalX, goalY, goalZ,
                profile, danger, domain, maxNodes, variety, setbacks, pillars, handsOff, caution);
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
                NavDomain.EVERYWHERE, DEFAULT_MAX_NODES, 0L, SetbackField.NONE);
    }
}
