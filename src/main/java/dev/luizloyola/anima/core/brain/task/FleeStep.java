package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.knowledge.BlockProbe;
import dev.luizloyola.anima.core.brain.sense.Being;
import dev.luizloyola.anima.core.brain.sense.DangerField;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.nav.Gait;
import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * One leg of a flight: aim away from whatever is pressing in, then run there — the
 * {@link WanderStep} pattern turned to flight, a {@link CompoundTask} with one always-applicable,
 * cost-{@code 0} method, so the roll happens at DECOMPOSE time against fresh percepts.
 *
 * <p><b>The escape vector.</b> Every perceived AGGRESSIVE being (the same set {@code FleeInstinct}
 * prices) is weighted {@code 1/distance²} (closest dominates, twice as far a quarter) into a
 * centroid; the direction is the unit vector from it through their position, the target
 * {@link #FLEE_LEG} along that, with independent {@code +/-}{@link #JITTER} per horizontal axis
 * from the shared {@link RandomGenerator} so legs do not draw a ruler-straight line, {@code y} left
 * to the pathfinder. No threats, or a centroid landing on them (surrounded), falls back to a
 * uniformly random heading of the same length.
 *
 * <p>Decomposes to one {@code [GoTo(target, Gait.SPRINT)]}, no {@link Idle}: flight does not pause
 * between legs.
 *
 * <p><b>SUCCESS just ends the leg.</b> While the pressure stays on top the arbiter re-grants
 * {@link dev.luizloyola.anima.core.brain.instinct.FleeInstinct}, and a fresh {@code FleeStep}
 * re-aims from the CURRENT threat positions. Escape ends by pressure decay (out of
 * {@link dev.luizloyola.anima.core.brain.instinct.FleeInstinct#RANGE}), never a scripted finish; a
 * FAILED leg retries almost immediately with a fresh roll, never a patch — see
 * {@link dev.luizloyola.anima.core.brain.instinct.FleeInstinct#failCooldown()}.
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

    private final List<Method> methods;

    public FleeStep() {
        this.methods = List.of(new Escape());
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
                if (being.aggressive()) {
                    threats.add(being);
                }
            }
            RandomGenerator random = ctx.random();
            double[] direction = escapeDirection(here, threats, random);
            int jitterX = random.nextInt(2 * JITTER + 1) - JITTER;
            int jitterZ = random.nextInt(2 * JITTER + 1) - JITTER;
            DangerField field = DangerField.of(ctx.danger(), ctx.percepts().beings(),
                    ctx.knowledge(), ctx.percepts().time(), DangerField.FADE_TICKS);
            Pos goal = safest(ctx, here, threats, direction, jitterX, jitterZ, field);
            return List.of(new GoTo(goal.x(), goal.y(), goal.z(), Gait.SPRINT));
        }

        @Override
        public String describe() {
            return "escape";
        }
    }

    /**
     * Where to run: the least frightening cell on the escape fan, cover preferred among equals.
     *
     * <p>Away is not safe — the direction that splits two mobs points at whatever is between them,
     * and a settler fled two mobs into a creeper that way. Every cell on the fan is priced against
     * everything the body knows to fear, seen now or remembered, and the cheapest wins.
     *
     * <p>The straight-away cell keeps a small edge, so a body with nothing to weigh runs straight.
     */
    private Pos safest(BrainContext ctx, Pos here, List<Being> threats, double[] direction,
            int jitterX, int jitterZ, DangerField field) {
        Pos away = new Pos(here.x() + (int) Math.round(direction[0] * FLEE_LEG) + jitterX,
                here.y(),
                here.z() + (int) Math.round(direction[1] * FLEE_LEG) + jitterZ);
        if (field.isEmpty()) {
            return takeCoverFrom(ctx, here, threats).orElse(away);
        }
        Pos best = away;
        double bestDanger = field.at(away) - STRAIGHT_AWAY_BONUS;
        for (Pos candidate : fan(here, direction)) {
            double danger = field.at(candidate);
            if (danger < bestDanger) {
                bestDanger = danger;
                best = candidate;
            }
        }
        // Cover only among places already worth going: hiding behind a wall next to a creeper is
        // not an improvement on being shot at.
        double ceiling = bestDanger + COVER_TOLERANCE;
        return takeCoverFrom(ctx, here, threats)
                .filter(cover -> field.at(cover) <= ceiling)
                .orElse(best);
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
