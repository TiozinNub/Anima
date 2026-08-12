package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.sense.Being;
import dev.luizloyola.anima.core.brain.sense.DangerField;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.log.Category;
import dev.luizloyola.anima.core.nav.Gait;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * One beat of a wander: usually just stand around; sometimes amble somewhere nearby. A
 * {@link CompoundTask} rather than a bare primitive so the roll happens at DECOMPOSE time against
 * fresh percepts — offset from where they ACTUALLY stand when the executor reaches this node.
 *
 * <p>The roll (draw order is part of the test contract: walk-roll, then pause, then target): with
 * probability {@link #WALK_CHANCE} a random {@code (dx, dz)} each uniform in
 * {@code [-radius, radius]}, re-rolled while both are zero, {@code y} unchanged, decomposing to
 * {@code [GoTo(target, STROLL), Idle(pause)]}; otherwise just {@code [Idle(pause)]}. Pauses run
 * {@code IDLE_MIN + [0, IDLE_RANGE)} ticks either way.
 *
 * <p>Tuned down deliberately (Luiz): idling is the default, walking the exception, the walk a
 * {@link Gait#STROLL}.
 *
 * <p><b>Failure self-heals.</b> An unreachable roll fails the {@link GoTo} and so the step; the
 * arbiter re-grants wander and a fresh {@link WanderStep} rolls a new target. The grant loop is the
 * retry — re-plan on surprise, never patch mid-plan.
 */
public final class WanderStep implements CompoundTask {
    /** Fraction of wander beats that actually go anywhere; the rest just stand. */
    public static final double WALK_CHANCE = 0.3;
    /** Minimum pause per beat, ticks (5 s) — unhurried by construction. */
    public static final int IDLE_MIN = 100;
    /** Random extra pause, ticks ({@code [0, 200)} on top of the minimum — up to 15 s total). */
    public static final int IDLE_RANGE = 200;
    /**
     * How many spots a body considers when it has something to be wary of. One when it has not —
     * the draw order of an untroubled wander is part of the test contract, and nothing should pay
     * for a mechanism it is not using.
     */
    private static final int CAUTIOUS_ROLLS = 4;

    private final int radius;
    private final List<Method> methods;

    /**
     * @param radius half-width of the roam box in whole blocks (positive)
     */
    public WanderStep(int radius) {
        this.radius = radius;
        this.methods = List.of(new Roam());
    }

    @Override
    public List<Method> methods() {
        return methods;
    }

    @Override
    public String describe() {
        return "wander step";
    }

    /** The one way to wander: mostly stand about; occasionally stroll somewhere nearby. */
    private final class Roam implements Method {
        @Override
        public boolean applicable(BrainContext ctx) {
            return true; // there is always somewhere to drift to — or nowhere, which is also fine
        }

        @Override
        public double estimateCost(BrainContext ctx) {
            return 0.0; // idling is free — it is the floor the whole cost economy sits on
        }

        @Override
        public List<Task> decompose(BrainContext ctx) {
            RandomGenerator random = ctx.random();
            Pos here = ctx.percepts().position();
            List<Being> beings = ctx.percepts().beings();
            // The draw happens either way, before anything can change its mind: the wander stream
            // is one continuous sequence per body and its draw order is part of this class's
            // contract. Crowding overrides the ANSWER, never the roll.
            boolean rolled = random.nextDouble() < WALK_CHANCE;
            boolean walks = rolled || Comfort.crowded(here, beings);
            int pause = IDLE_MIN + random.nextInt(IDLE_RANGE);
            if (!walks) {
                return List.of(new Idle(pause));
            }
            DangerField field = DangerField.of(ctx.danger(), beings,
                    ctx.knowledge(), ctx.percepts().time(), DangerField.FADE_TICKS);
            Pos target = roll(here, beings, field, ctx, random);
            int tx = target.x();
            int ty = target.y();
            int tz = target.z();
            // BRAIN log: the "wander (10, 10, 10) - start" line — the drive plus the spot picked,
            // written on commitment to walking there (the pathfind line for that cell follows).
            ctx.journal().record(Category.BRAIN, "wander (" + tx + ", " + ty + ", " + tz + ")", "start");
            return List.of(new GoTo(tx, ty, tz, Gait.STROLL), new Idle(pause));
        }

        /**
         * Where to potter off to — a plain roll when there is nothing to think about, the most
         * comfortable of a few rolls when there is.
         *
         * <p>A remembered fright must change where a calm body chooses to be, not only how it runs;
         * elbow room and wanting company are the same kind of opinion (see {@link Comfort}). Not a
         * search: a body holding out for a perfect cell would stand still.
         *
         * <p>The leg it picks is never re-aimed — a beat is five to fifteen seconds, and biasing
         * the next roll converges just as fast and cannot thrash.
         */
        private Pos roll(Pos here, List<Being> beings, DangerField field, BrainContext ctx,
                RandomGenerator random) {
            boolean weighing = Comfort.worthWeighing(beings, field);
            Pos best = null;
            double bestCost = Double.MAX_VALUE;
            int rolls = weighing ? CAUTIOUS_ROLLS : 1;
            for (int i = 0; i < rolls; i++) {
                int dx;
                int dz;
                do {
                    dx = random.nextInt(2 * radius + 1) - radius;
                    dz = random.nextInt(2 * radius + 1) - radius;
                } while (dx == 0 && dz == 0);
                Pos candidate = new Pos(here.x() + dx, here.y(), here.z() + dz);
                double cost = weighing
                        ? Comfort.cost(candidate, beings, field, ctx.percepts().needs(),
                                ctx.profile())
                        : 0.0;
                if (cost < bestCost) {
                    bestCost = cost;
                    best = candidate;
                }
            }
            return best;
        }

        @Override
        public String describe() {
            return "roam";
        }
    }

    /** The roam radius this step was built with — the whole of what it is. */
    public int radius() {
        return radius;
    }
}
