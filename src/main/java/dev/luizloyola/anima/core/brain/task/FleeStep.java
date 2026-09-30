package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.knowledge.BlockProbe;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.knowledge.PoiMemory;
import dev.luizloyola.anima.core.brain.knowledge.ShelterNoter;
import dev.luizloyola.anima.core.brain.sense.Being;
import dev.luizloyola.anima.core.brain.sense.Combatant;
import dev.luizloyola.anima.core.brain.sense.DangerField;
import dev.luizloyola.anima.core.brain.sense.Enclosure;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.brain.sense.Sides;
import dev.luizloyola.anima.core.log.Category;
import dev.luizloyola.anima.core.nav.Gait;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.random.RandomGenerator;
import org.jspecify.annotations.Nullable;

/**
 * One leg of a flight: aim away from whatever is pressing in, then run there — the
 * {@link WanderStep} pattern turned to flight, a {@link CompoundTask} with one always-applicable,
 * cost-{@code 0} method, so the roll happens at DECOMPOSE time against fresh percepts.
 *
 * <p><b>The escape vector.</b> Every perceived AGGRESSIVE being (the same set {@code FightOrFlightInstinct}
 * prices) is weighted {@code 1/distance²} (closest dominates, twice as far a quarter) into a
 * centroid; the direction is the unit vector from it through their position, the target
 * {@link #FLEE_LEG} along that, with independent {@code +/-}{@link #JITTER} per horizontal axis
 * from the shared {@link RandomGenerator} so legs do not draw a ruler-straight line, {@code y} left
 * to the pathfinder. No threats, or a centroid landing on them (surrounded), falls back to a
 * uniformly random heading of the same length.
 *
 * <p>Decomposes to {@code [RunAway(targets), LookBack]}, no {@link Idle}: flight does not pause
 * between legs except to look back, and {@link LookBack} decides for itself whether it may.
 * {@link RunAway} sprints for the best target whose route does not leave the body in a pit.
 * The look is the leg's last step rather than a grant of its own because a flight whose threats have
 * fallen out of range bids nothing at the boundary, and would never get to look.
 *
 * <p><b>In a shelter</b> (shelter spec) only the threats on this side are run from, and the leg ends
 * in the room's least frightening cell — {@link #keepAway} — holding still once it is there.
 *
 * <p><b>In a space one shut door from a shelter</b>, with everything frightening outside it, the
 * body shuts the doors first when it gets to each before anything else does — {@link #shutPlan}.
 * Outside, it runs for a shelter it remembers when it wins the race there — {@link #shelterToRunFor}.
 *
 * <p><b>SUCCESS just ends the leg.</b> While the pressure stays on top the arbiter re-grants
 * {@link dev.luizloyola.anima.core.brain.instinct.FightOrFlightInstinct}, and a fresh {@code FleeStep}
 * re-aims from the CURRENT threat positions. Escape ends by pressure decay (out of
 * {@link dev.luizloyola.anima.core.brain.instinct.FightOrFlightInstinct#RANGE}), never a scripted finish; a
 * FAILED leg retries almost immediately with a fresh roll, never a patch — see
 * {@link dev.luizloyola.anima.core.brain.instinct.FightOrFlightInstinct#failCooldown()}.
 */
public final class FleeStep implements CompoundTask {

    /** Horizontal length of one escape leg, in blocks, before jitter. */
    public static final double FLEE_LEG = 12.0;

    /** Half-width of the independent per-axis jitter applied to the leg target, in blocks. */
    public static final int JITTER = 2;

    /** Below this squared magnitude the raw escape vector counts as degenerate (surrounded). */
    private static final double DEGENERATE_EPSILON = 1e-6;

    /** Distance floor for the {@code 1/distance²} weighting — guards a threat standing on them. */
    private static final double MIN_WEIGHT_DISTANCE = 0.1;

    /**
     * How many cells a cover search may test before giving up and running. Small on purpose: each
     * one is a ray.
     */
    private static final int COVER_CANDIDATES = 8;

    /** How far either side of the escape heading the search fans, in radians (about 60 degrees). */
    private static final double COVER_ARC = Math.PI * 2.0 / 3.0;

    /**
     * The edge straight-away gets over an equally frightening cell to one side. Small: enough to
     * keep a body running in a straight line when nothing distinguishes the options, not enough
     * to override anything it actually knows.
     */
    private static final double STRAIGHT_AWAY_BONUS = 0.01;

    /** How much more frightening a cover cell may be before hiding stops being worth it. */
    private static final double COVER_TOLERANCE = 0.05;

    /** How long a body in the best corner of a shelter holds before it weighs the room again. */
    static final int HOLD_TICKS = 10;

    private final List<Method> methods;

    public FleeStep() {
        this.methods = List.of(new ShutTheDoor(), new RunForShelter(), new Escape());
    }

    @Override
    public List<Method> methods() {
        return methods;
    }

    @Override
    public String describe() {
        return "flee step";
    }

    /** The one way to flee: aim away from the current threats and run there, urgently. */
    private final class Escape implements Method {
        @Override
        public boolean applicable(BrainContext ctx) {
            return true; // there is always a direction to run, even with nothing to run from
        }

        @Override
        public double estimateCost(BrainContext ctx) {
            return 0.0; // survival is free — never priced out by the cost tolerance
        }

        @Override
        public List<Task> decompose(BrainContext ctx) {
            Pos here = ctx.percepts().position();
            List<Being> threats = new ArrayList<>();
            for (Being being : ctx.percepts().beings()) {
                if (being.aggressive() && !Sides.shutOut(ctx.percepts(), being)) {
                    threats.add(being);
                }
            }
            RandomGenerator random = ctx.random();
            double[] direction = escapeDirection(here, threats, random);
            int jitterX = random.nextInt(2 * JITTER + 1) - JITTER;
            int jitterZ = random.nextInt(2 * JITTER + 1) - JITTER;
            DangerField field = DangerField.of(ctx.danger(), ctx.percepts().beings(),
                    ctx.knowledge(), ctx.percepts().time(), DangerField.FADE_TICKS);
            Enclosure space = ctx.percepts().enclosure();
            if (space.shelter() && space.covers(here)) {
                Pos goal = keepAway(space, here, field);
                if (goal.equals(here)) {
                    // Already as far off as the room allows: hold and watch, rather than order a
                    // walk to the cell it stands on four times a second.
                    return List.of(new Idle(HOLD_TICKS), new LookBack());
                }
                return List.of(new GoTo(goal.x(), goal.y(), goal.z(), Gait.SPRINT)
                        .leavingShelter(), new LookBack());
            }
            return List.of(new RunAway(safest(ctx, here, threats, direction, jitterX, jitterZ,
                    field)), new LookBack());
        }

        @Override
        public String describe() {
            return "escape";
        }
    }

    /**
     * Shut the way in, when that is faster than anything coming through it (shelter spec, rung 4).
     * Tried before running: out of a house that can be shut is the less safe way.
     */
    private final class ShutTheDoor implements Method {
        @Override
        public boolean applicable(BrainContext ctx) {
            return shutPlan(ctx) != null;
        }

        @Override
        public double estimateCost(BrainContext ctx) {
            return 0.0;
        }

        @Override
        public List<Task> decompose(BrainContext ctx) {
            @Nullable Plan plan = shutPlan(ctx);
            if (plan == null) {
                return List.of(); // the contract says applicable was just true; nothing moved
            }
            List<Task> steps = new ArrayList<>();
            Pos at = ctx.percepts().position();
            for (Stop stop : plan.stops()) {
                if (!stop.stand().equals(at)) {
                    steps.add(new GoTo(stop.stand().x(), stop.stand().y(), stop.stand().z(),
                            Gait.SPRINT));
                }
                steps.add(new ShutDoor(stop.door().x(), stop.door().y(), stop.door().z()));
                at = stop.stand();
            }
            // Hold while the space is read again: until then it still reads as open, and a fresh
            // leg would only shut the shut door again.
            steps.add(new Idle(HOLD_TICKS));
            ctx.journal().record(Category.BRAIN, describe(), plan.describe());
            return steps;
        }

        @Override
        public String describe() {
            return "shut the door";
        }
    }

    /**
     * Run for a remembered shelter and get in (shelter spec, rung 5). The walk judges its own route
     * before committing to it ({@link RunToShelter}); once in, the body holds while the space is
     * read, and the next leg shuts whatever door is still open.
     */
    private final class RunForShelter implements Method {
        @Override
        public boolean applicable(BrainContext ctx) {
            return shelterToRunFor(ctx) != null;
        }

        @Override
        public double estimateCost(BrainContext ctx) {
            return 0.0;
        }

        @Override
        public List<Task> decompose(BrainContext ctx) {
            @Nullable PoiMemory way = shelterToRunFor(ctx);
            if (way == null) {
                return List.of();
            }
            Pos in = way.anchor();
            Pos door = ShelterNoter.door(way);
            ctx.journal().record(Category.BRAIN, describe(), String.format(Locale.ROOT,
                    "running for the shelter behind the door at (%d, %d, %d), %.0f blocks off",
                    door.x(), door.y(), door.z(),
                    ShelterRace.distance(ctx.percepts().position(), in)));
            return List.of(new RunToShelter(in.x(), in.y(), in.z()), new Idle(HOLD_TICKS));
        }

        @Override
        public String describe() {
            return "run for shelter";
        }
    }

    /**
     * The nearest shelter this body remembers that it could win the race to, or null. Within
     * {@link RunToShelter#REACH} in a straight line, every threat pressing on it one the shelter
     * keeps out and none inside it, and this body at the door a second ahead of all of them even
     * on the straight line, the best any route can do. The route itself is judged on the way.
     */
    static @Nullable PoiMemory shelterToRunFor(BrainContext ctx) {
        Enclosure space = ctx.percepts().enclosure();
        Pos here = ctx.percepts().position();
        if (space.shelter() && space.covers(here)) {
            return null;
        }
        Combatant me = ctx.percepts().selfAsCombatant().orElse(null);
        if (me == null || me.pace() <= 0.0) {
            return null;
        }
        long now = ctx.percepts().time();
        @Nullable PoiMemory best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (PoiMemory way : ctx.knowledge().sighted(PoiKind.SHELTER)) {
            Pos in = way.anchor();
            double distance = ShelterRace.distance(here, in);
            if (distance > RunToShelter.REACH || distance >= bestDistance
                    || way.bounds().contains(here) || space.covers(in)
                    || ctx.knowledge().isAvoided(PoiKind.SHELTER, in, now)) {
                continue;
            }
            Enclosure.Holes holes = ShelterNoter.smallGetsIn(way)
                    ? Enclosure.Holes.SMALL : Enclosure.Holes.NONE;
            List<ShelterRace.Runner> runners =
                    ShelterRace.shutOutBy(ctx, me.pace(), holes, way.bounds()::contains);
            if (runners == null || runners.isEmpty()
                    || ShelterRace.lead(runners, in, distance / me.pace())
                            < ShelterRace.DOOR_MARGIN_TICKS) {
                continue;
            }
            best = way;
            bestDistance = distance;
        }
        return best;
    }

    /** One door to shut, and the cell inside it to shut it from. */
    record Stop(Pos door, Pos stand, double ticks) {
    }

    /**
     * The doors to shut, nearest first, and how the race for each stands.
     *
     * @param closest the smallest lead over any threat at any door, in ticks
     */
    record Plan(List<Stop> stops, double closest) {
        String describe() {
            Stop first = stops.get(0);
            return String.format(Locale.ROOT, "shutting %s at (%d, %d, %d), there in %.1f s, "
                            + "%.1f s before anything else",
                    stops.size() == 1 ? "the door" : stops.size() + " doors",
                    first.door().x(), first.door().y(), first.door().z(), first.ticks() / 20.0,
                    closest / 20.0);
        }
    }

    /**
     * How to shut this body in, or null when it cannot, or should not. It can when the space is one
     * the check found closeable and roofed once shut, and every threat pressing on the body is
     * outside it and kept out by walls and shut doors ({@link ShelterRace#shutOutBy}). It should when
     * it gets to every door, sprinting inside the space, {@link ShelterRace#DOOR_MARGIN_TICKS} before
     * any threat could.
     */
    static @Nullable Plan shutPlan(BrainContext ctx) {
        Enclosure space = ctx.percepts().enclosure();
        Pos here = ctx.percepts().position();
        if (space.openness() != Enclosure.Openness.CLOSEABLE || !space.roofed()
                || space.doorsToShut().isEmpty() || !space.covers(here)) {
            return null;
        }
        Combatant me = ctx.percepts().selfAsCombatant().orElse(null);
        if (me == null || me.pace() <= 0.0) {
            return null;
        }
        List<ShelterRace.Runner> runners =
                ShelterRace.shutOutBy(ctx, me.pace(), space.holes(), space::covers);
        if (runners == null || runners.isEmpty()) {
            return null;
        }
        List<Pos> doors = new ArrayList<>(space.doorsToShut());
        List<Stop> stops = new ArrayList<>();
        Pos at = standFloor(space, here);
        double ticks = 0.0;
        double closest = Double.POSITIVE_INFINITY;
        while (!doors.isEmpty()) {
            Map<Pos, Integer> steps = steps(space, at);
            @Nullable Pos door = null;
            @Nullable Pos stand = null;
            int best = Integer.MAX_VALUE;
            for (Pos candidate : doors) {
                for (Pos cell : space.besideDoor(candidate)) {
                    Integer n = steps.get(cell);
                    if (n != null && n < best) {
                        best = n;
                        door = candidate;
                        stand = cell;
                    }
                }
            }
            if (door == null) {
                return null; // a door to shut with no cell beside it to shut it from
            }
            ticks += best / me.pace();
            double lead = ShelterRace.lead(runners, door, ticks);
            if (lead < ShelterRace.DOOR_MARGIN_TICKS) {
                return null;
            }
            closest = Math.min(closest, lead);
            stops.add(new Stop(door, stand, ticks));
            doors.remove(door);
            at = stand;
        }
        return new Plan(stops, closest);
    }

    /** The space's cell the body stands in: its feet, or the floor or head cell by it. */
    private static Pos standFloor(Enclosure space, Pos here) {
        if (space.contains(here)) {
            return here;
        }
        Pos up = new Pos(here.x(), here.y() + 1, here.z());
        return space.contains(up) ? up : new Pos(here.x(), here.y() - 1, here.z());
    }

    /**
     * Steps from {@code from} to every cell of the space, a cardinal step at a time, a step up or
     * down allowed. Longer than the diagonals a body takes, which only makes the race harder on it.
     */
    private static Map<Pos, Integer> steps(Enclosure space, Pos from) {
        Map<Pos, Integer> steps = new HashMap<>();
        ArrayDeque<Pos> queue = new ArrayDeque<>();
        steps.put(from, 0);
        queue.add(from);
        while (!queue.isEmpty()) {
            Pos cell = queue.poll();
            int n = steps.get(cell);
            for (int[] step : AXES) {
                for (int dy = -1; dy <= 1; dy++) {
                    Pos next = new Pos(cell.x() + step[0], cell.y() + dy, cell.z() + step[1]);
                    if (space.contains(next) && !steps.containsKey(next)) {
                        steps.put(next, n + 1);
                        queue.add(next);
                    }
                }
            }
        }
        return steps;
    }

    /**
     * Where to run, best first: the least frightening cells on the escape fan, cover first among
     * equals. {@link RunAway} takes the first whose route does not leave the body in a pit.
     *
     * <p>Away is not safe — the direction that splits two mobs points at whatever is between them,
     * and a settler fled two mobs into a creeper that way. Every cell on the fan is priced against
     * everything the body knows to fear, seen now or remembered, and the cheapest leads.
     *
     * <p>The straight-away cell keeps a small edge, so a body with nothing to weigh runs straight.
     */
    private List<Pos> safest(BrainContext ctx, Pos here, List<Being> threats, double[] direction,
            int jitterX, int jitterZ, DangerField field) {
        Pos away = new Pos(here.x() + (int) Math.round(direction[0] * FLEE_LEG) + jitterX,
                here.y(),
                here.z() + (int) Math.round(direction[1] * FLEE_LEG) + jitterZ);
        List<Pos> ranked = new ArrayList<>();
        ranked.add(away);
        ranked.addAll(fan(here, direction));
        if (!field.isEmpty()) {
            ranked.sort(Comparator.comparingDouble(
                    cell -> field.at(cell) - (cell == away ? STRAIGHT_AWAY_BONUS : 0.0)));
        }
        // Cover only among places already worth going: hiding behind a wall next to a creeper is
        // not an improvement on being shot at.
        double ceiling = field.isEmpty() ? Double.POSITIVE_INFINITY
                : field.at(ranked.get(0)) - (ranked.get(0) == away ? STRAIGHT_AWAY_BONUS : 0.0)
                        + COVER_TOLERANCE;
        takeCoverFrom(ctx, here, threats)
                .filter(cover -> field.isEmpty() || field.at(cover) <= ceiling)
                .ifPresent(cover -> ranked.add(0, cover));
        // The first of the fan is straight away too, and a pit found once is not searched twice.
        return List.copyOf(new LinkedHashSet<>(ranked));
    }

    /**
     * Where to run inside a shelter: its least frightening cell (shelter spec). The field counts
     * every threat, shut out or not, so a cell by the door with a crowd behind it is priced as one.
     * A way out is taken only when the cell past a door is quieter than every cell in here. Ties go
     * to the nearest, so the answer does not depend on the order the space was found in.
     */
    static Pos keepAway(Enclosure shelter, Pos here, DangerField field) {
        List<Pos> options = new ArrayList<>(shelter.space());
        for (Pos door : shelter.doors()) {
            for (int[] step : AXES) {
                Pos in = new Pos(door.x() + step[0], door.y(), door.z() + step[1]);
                Pos past = new Pos(door.x() - step[0], door.y(), door.z() - step[1]);
                if (shelter.covers(in) && !shelter.covers(past)) {
                    options.add(past);
                }
            }
        }
        return options.stream()
                .min(Comparator.<Pos>comparingDouble(field::at)
                        .thenComparingInt(cell -> distanceSq(cell, here))
                        .thenComparingInt(Pos::x).thenComparingInt(Pos::y).thenComparingInt(Pos::z))
                .orElse(here);
    }

    private static final int[][] AXES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private static int distanceSq(Pos a, Pos b) {
        int dx = a.x() - b.x();
        int dy = a.y() - b.y();
        int dz = a.z() - b.z();
        return dx * dx + dy * dy + dz * dz;
    }

    /** The candidate cells one leg away, fanned around the escape heading. */
    private static List<Pos> fan(Pos here, double[] direction) {
        List<Pos> cells = new ArrayList<>(COVER_CANDIDATES);
        for (int i = 0; i < COVER_CANDIDATES; i++) {
            double spread = COVER_ARC * ((i % 2 == 0 ? 1 : -1) * ((i + 1) / 2))
                    / (double) COVER_CANDIDATES;
            double cos = Math.cos(spread);
            double sin = Math.sin(spread);
            cells.add(new Pos(
                    here.x() + (int) Math.round((direction[0] * cos - direction[1] * sin) * FLEE_LEG),
                    here.y(),
                    here.z() + (int) Math.round((direction[0] * sin + direction[1] * cos) * FLEE_LEG)));
        }
        return cells;
    }

    /**
     * Somewhere along the escape heading with no line back to whatever is shooting, or empty.
     *
     * <p>Cover is a tactic, not a drive: it is <em>how</em> you flee, so it is a goal choice rather
     * than an instinct. Only worth it against something that shoots — line of sight answers a
     * skeleton and does nothing about a zombie, which walks around the wall — so a melee threat
     * returns empty and the body just runs.
     *
     * <p>Rays are the expensive channel: at most {@link #COVER_CANDIDATES}, only when a leg is
     * chosen, never per tick.
     */
    private java.util.Optional<Pos> takeCoverFrom(BrainContext ctx, Pos here, List<Being> threats) {
        List<Being> shooters = new ArrayList<>();
        for (Being threat : threats) {
            if (threat.gear().ranged() || threat.activity() == Being.Activity.AIMING
                    || ctx.danger().ranged(threat.species())) {
                shooters.add(threat);
            }
        }
        if (shooters.isEmpty()) {
            return java.util.Optional.empty();
        }
        BlockProbe probe = ctx.percepts().blocks();
        // Fan out around the escape heading rather than searching a disc: a cover spot behind you
        // is worth nothing if reaching it means running past the archer.
        for (Pos candidate : fan(here, escapeDirection(here, shooters, ctx.random()))) {
            if (hiddenFromAll(probe, candidate, shooters)) {
                return java.util.Optional.of(candidate);
            }
        }
        return java.util.Optional.empty();
    }

    /** Whether no shooter has a line to this cell. One ray each, early-out on the first that does. */
    private static boolean hiddenFromAll(BlockProbe probe, Pos candidate, List<Being> shooters) {
        for (Being shooter : shooters) {
            if (probe.sightClearBetween(shooter.pos(), candidate)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The unit direction to run: away from the proximity-weighted threat centroid, or a uniformly
     * random heading when there is nothing to run from (no threats) or nowhere is better than
     * anywhere else (surrounded — the centroid lands on them).
     */
    private double[] escapeDirection(Pos here, List<Being> threats, RandomGenerator random) {
        if (threats.isEmpty()) {
            return randomDirection(random);
        }
        double weightSum = 0.0;
        double centroidX = 0.0;
        double centroidZ = 0.0;
        for (Being threat : threats) {
            double distance = Math.max(threat.distance(), MIN_WEIGHT_DISTANCE);
            double weight = 1.0 / (distance * distance);
            weightSum += weight;
            centroidX += weight * threat.pos().x();
            centroidZ += weight * threat.pos().z();
        }
        centroidX /= weightSum;
        centroidZ /= weightSum;
        double awayX = here.x() - centroidX;
        double awayZ = here.z() - centroidZ;
        double lengthSq = awayX * awayX + awayZ * awayZ;
        if (lengthSq < DEGENERATE_EPSILON) {
            return randomDirection(random);
        }
        double length = Math.sqrt(lengthSq);
        return new double[] {awayX / length, awayZ / length};
    }

    private double[] randomDirection(RandomGenerator random) {
        double angle = random.nextDouble() * 2.0 * Math.PI;
        return new double[] {Math.cos(angle), Math.sin(angle)};
    }
}
