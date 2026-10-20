package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.knowledge.PoiMemory;
import dev.luizloyola.anima.core.brain.knowledge.ShelterNoter;
import dev.luizloyola.anima.core.brain.sense.Enclosure;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.log.Category;
import dev.luizloyola.anima.core.nav.Gait;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Getting under cover for the night, the night shelter instinct's root: stay in a shelter the body
 * stands in, shut the doors of one it is in that stand open, walk in to one it remembers within
 * {@link RunToShelter#REACH}, or else {@link DigIn dig in} where it stands. A flight's moves without
 * the race: nothing is coming, so every door is shut and the walk does not hurry.
 *
 * <p>With none of those, or every one fallen through, it waits the night out where it stands: a
 * settler whose dig failed went off exploring at night (2026-10-20).
 */
public final class NightShelterStep implements CompoundTask {

    /**
     * How long one stay lasts before the night is weighed again — short, so sunrise lets the body
     * go within two seconds.
     */
    static final int HOLD_TICKS = 40;

    private final List<Method> methods = List.of(new StayIn(), new ShutIn(), new WalkIn(), new NoWayIn(),
            new DigInHere(), new WaitOut());

    /** The shelter this root set out for, left alone a while if the way there fails. Not saved. */
    private @Nullable Pos headedFor;

    @Override
    public List<Method> methods() {
        return methods;
    }

    @Override
    public String describe() {
        return "shelter for the night";
    }

    static boolean inShelter(BrainContext ctx) {
        Enclosure space = ctx.percepts().enclosure();
        return space.shelter() && space.covers(ctx.percepts().position());
    }

    private static @Nullable List<FleeStep.Stop> shutOrder(BrainContext ctx) {
        Enclosure space = ctx.percepts().enclosure();
        Pos here = ctx.percepts().position();
        if (space.openness() != Enclosure.Openness.CLOSEABLE || !space.roofed()
                || space.doorsToShut().isEmpty() || !space.covers(here)) {
            return null;
        }
        return FleeStep.shutOrder(space, here, 1.0);
    }

    /** The nearest remembered way into a shelter within reach in a straight line, or null. */
    static @Nullable PoiMemory shelterToWalkTo(BrainContext ctx) {
        Pos here = ctx.percepts().position();
        long now = ctx.percepts().time();
        @Nullable PoiMemory best = null;
        double bestDistance = RunToShelter.REACH;
        for (PoiMemory way : ctx.knowledge().sighted(PoiKind.SHELTER)) {
            double distance = ShelterRace.distance(here, way.anchor());
            if (!ShelterNoter.dug(way) && distance <= bestDistance
                    && !ctx.knowledge().isAvoided(PoiKind.SHELTER, way.anchor(), now)) {
                best = way;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static final class StayIn implements Method {
        @Override
        public boolean applicable(BrainContext ctx) {
            return inShelter(ctx);
        }

        @Override
        public double estimateCost(BrainContext ctx) {
            return 0.0;
        }

        @Override
        public List<Task> decompose(BrainContext ctx) {
            return List.of(new Idle(HOLD_TICKS));
        }

        @Override
        public String describe() {
            return "stay in";
        }
    }

    private static final class ShutIn implements Method {
        @Override
        public boolean applicable(BrainContext ctx) {
            return shutOrder(ctx) != null;
        }

        @Override
        public double estimateCost(BrainContext ctx) {
            return 0.0;
        }

        @Override
        public List<Task> decompose(BrainContext ctx) {
            @Nullable List<FleeStep.Stop> stops = shutOrder(ctx);
            if (stops == null) {
                return List.of();
            }
            return FleeStep.shutSteps(stops, ctx.percepts().position(), Gait.WALK);
        }

        @Override
        public String describe() {
            return "shut the doors";
        }
    }

    private final class WalkIn implements Method {
        @Override
        public boolean applicable(BrainContext ctx) {
            return shelterToWalkTo(ctx) != null;
        }

        @Override
        public double estimateCost(BrainContext ctx) {
            return 0.0;
        }

        @Override
        public List<Task> decompose(BrainContext ctx) {
            @Nullable PoiMemory way = shelterToWalkTo(ctx);
            if (way == null) {
                return List.of();
            }
            Pos in = way.anchor();
            headedFor = in;
            ctx.journal().record(Category.BRAIN, describe(), String.format(Locale.ROOT,
                    "going in for the night at (%d, %d, %d), %.0f blocks off", in.x(), in.y(),
                    in.z(), ShelterRace.distance(ctx.percepts().position(), in)));
            // The hold lets the space be read again before the next root weighs it.
            return List.of(new RunToShelter(in.x(), in.y(), in.z(), Gait.WALK),
                    new Idle(FleeStep.HOLD_TICKS));
        }

        @Override
        public String describe() {
            return "walk in";
        }
    }

    private static final class DigInHere implements Method {
        @Override
        public boolean applicable(BrainContext ctx) {
            return DigIn.possible(ctx) && DigIn.where(ctx) != null;
        }

        @Override
        public double estimateCost(BrainContext ctx) {
            return 0.0;
        }

        @Override
        public List<Task> decompose(BrainContext ctx) {
            return List.of(new DigIn());
        }

        @Override
        public String describe() {
            return "dig in";
        }
    }

    /** The walk in fell through: that shelter is left alone a while, as a flight would. */
    private final class NoWayIn implements Method {
        @Override
        public boolean applicable(BrainContext ctx) {
            return headedFor != null;
        }

        @Override
        public double estimateCost(BrainContext ctx) {
            return 0.0;
        }

        @Override
        public List<Task> decompose(BrainContext ctx) {
            Pos at = headedFor;
            return at == null ? List.of() : List.of(new GiveUpShelter(at.x(), at.y(), at.z()));
        }

        @Override
        public String describe() {
            return "no way in";
        }
    }

    private static final class WaitOut implements Method {
        @Override
        public boolean applicable(BrainContext ctx) {
            return true;
        }

        @Override
        public double estimateCost(BrainContext ctx) {
            return 0.0;
        }

        @Override
        public List<Task> decompose(BrainContext ctx) {
            return List.of(new Idle(HOLD_TICKS));
        }

        @Override
        public String describe() {
            return "wait the night out";
        }
    }
}
