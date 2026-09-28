package dev.luizloyola.anima.core.nav;

import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.ArrayList;
import java.util.List;

/**
 * Where a bridge would fit, read off a building route (docs/superpowers/specs/2026-09-28-bridging-design.md):
 * each run of decks with the ground it leaves from and the ground it reaches. The builder asks it
 * between two of a party's places to learn where a proper bridge would pay; the record of laid
 * blocks says which crude ones are walked.
 */
public final class Crossings {

    /**
     * One crossing: the natural ground at each end, the height of its deck, how many decks, and
     * what building it saves over the way round ({@code 0} when read off a route alone).
     */
    public record Crossing(Pos from, Pos to, int deckY, int span, double saves) {
    }

    private Crossings() {
    }

    /** The runs of {@link MoveType#BRIDGE} waypoints in a route, starting from {@code start}. */
    public static List<Crossing> of(Path path, Pos start) {
        List<Crossing> out = new ArrayList<>();
        List<Waypoint> steps = path.waypoints();
        Pos before = start;
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).move() != MoveType.BRIDGE) {
                Waypoint w = steps.get(i);
                before = new Pos(w.x(), w.y(), w.z());
                continue;
            }
            int first = i;
            while (i + 1 < steps.size() && steps.get(i + 1).move() == MoveType.BRIDGE) {
                i++;
            }
            Waypoint last = steps.get(i);
            Pos after = i + 1 < steps.size()
                    ? new Pos(steps.get(i + 1).x(), steps.get(i + 1).y(), steps.get(i + 1).z())
                    : new Pos(last.x(), last.y(), last.z());
            out.add(new Crossing(before, after, steps.get(first).y() - 1, i - first + 1, 0.0));
            before = new Pos(last.x(), last.y(), last.z());
        }
        return out;
    }

    /**
     * The crossings a body carrying {@code budget} blocks would lay between two places, and what
     * they save: the route without building against the route with it. Empty when building buys
     * nothing — no gap on the way, or a way round as good.
     */
    public static List<Crossing> between(NavGrid grid, PathRequest request, int budget) {
        MoveCapabilities walker = request.profile().withLaid(0);
        MoveCapabilities builder = request.profile().withLaid(budget);
        Path round = Pathfinder.find(grid, withBody(request, walker));
        Path built = Pathfinder.find(grid, withBody(request, builder));
        if (!built.reachedGoal() || built.laid() == 0) {
            return List.of();
        }
        double saves = round.reachedGoal() ? length(round) - length(built) : Double.POSITIVE_INFINITY;
        List<Crossing> out = new ArrayList<>();
        Pos start = new Pos(request.startX(), request.startY(), request.startZ());
        for (Crossing c : of(built, start)) {
            out.add(new Crossing(c.from(), c.to(), c.deckY(), c.span(), saves));
        }
        return out;
    }

    private static PathRequest withBody(PathRequest request, MoveCapabilities body) {
        return new PathRequest(request.startX(), request.startY(), request.startZ(), request.goalX(),
                request.goalY(), request.goalZ(), body, request.danger(), request.domain(),
                request.maxNodes(), request.variety(), request.setbacks(), request.pillars(),
                request.handsOff());
    }

    /** A route's walked length, waypoint to waypoint — the builder's measure, not the search's cost. */
    private static double length(Path path) {
        double total = 0.0;
        List<Waypoint> steps = path.waypoints();
        for (int i = 1; i < steps.size(); i++) {
            Waypoint a = steps.get(i - 1);
            Waypoint b = steps.get(i);
            double dx = b.x() - a.x();
            double dy = b.y() - a.y();
            double dz = b.z() - a.z();
            total += Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
        return total;
    }
}
