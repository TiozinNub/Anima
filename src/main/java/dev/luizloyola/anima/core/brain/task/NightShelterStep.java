package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.instinct.NightShelterInstinct;
import dev.luizloyola.anima.core.brain.knowledge.AgentKnowledge;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.knowledge.PoiMemory;
import dev.luizloyola.anima.core.brain.knowledge.ShelterNoter;
import dev.luizloyola.anima.core.brain.sense.Enclosure;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.log.Category;
import dev.luizloyola.anima.core.nav.Gait;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.WeakHashMap;
import org.jspecify.annotations.Nullable;

/**
 * Getting under cover for the night, the night shelter instinct's root: stay in a shelter the body
 * stands in, shut the doors of one it is in that stand open, walk in to one it remembers within
 * {@link #reach}, or else {@link DigIn dig in} where it stands. A flight's moves without the race:
 * nothing is coming, so every door is shut and the walk does not hurry.
 *
 * <p>The walk home is weighed against the dark (Luiz, 2026-10-21): at dusk a body walks to a
 * shelter it would reach by nightfall, or near enough by then to press on through the dark; at dark
 * the walk left is weighed again ({@link RunToShelter}), and one too long stops for a dig. A body
 * away on purpose ({@link BrainContext#awayOnPurpose}) digs in where it is, walking only into a
 * shelter within {@link RunToShelter#REACH}, as before.
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

    /** A player's walking pace, the gait {@link Gait#WALK} — 4.3 blocks a second. */
    static final double WALK_PACE = 0.216;

    /**
     * How long a walk home may still take once it is dark: 15 s, about 65 blocks at a walk. As far
     * as a flight runs for a shelter with something behind it ({@link RunToShelter#REACH}), so the
     * dark alone never sends a body further than fear does; past it, a pocket dug in a few seconds
     * is the shorter time in the open.
     */
    static final int PRESS_ON_TICKS = 300;

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

    /** How far a walk home may still be at dark — see {@link #PRESS_ON_TICKS}. */
    public static double pressOn() {
        return WALK_PACE * PRESS_ON_TICKS;
    }

    /** Ticks of dusk left before nightfall; 0 by day, at night, and where the sun keeps no hours. */
    static long untilDark(BrainContext ctx) {
        OptionalLong time = ctx.percepts().timeOfDay();
        if (time.isEmpty()) {
            return 0L;
        }
        long t = Math.floorMod(time.getAsLong(), 24_000L);
        return t >= NightShelterInstinct.SUNSET && t < NightShelterInstinct.NIGHTFALL
                ? NightShelterInstinct.NIGHTFALL - t : 0L;
    }

    /** Whether it is dark: nightfall to sunrise. */
    static boolean dark(BrainContext ctx) {
        OptionalLong time = ctx.percepts().timeOfDay();
        return time.isPresent() && Math.floorMod(time.getAsLong(), 24_000L) >= NightShelterInstinct.NIGHTFALL;
    }

    /**
     * How far a walk in for the night may be, by the way there: what a walk covers before dark and
     * what it would press on through after; at dark, the second alone. A body away on purpose,
     * {@link RunToShelter#REACH}.
     */
    static double reach(BrainContext ctx) {
        return ctx.awayOnPurpose() ? RunToShelter.REACH : WALK_PACE * untilDark(ctx) + pressOn();
    }

    /** The nearest remembered way into a shelter within {@link #reach} in a straight line, or null. */
    static @Nullable PoiMemory shelterToWalkTo(BrainContext ctx) {
        return nearestShelter(ctx, reach(ctx));
    }

    /** The nearest remembered way into a shelter within {@code limit} in a straight line, or null. */
    private static @Nullable PoiMemory nearestShelter(BrainContext ctx, double limit) {
        Pos here = ctx.percepts().position();
        long now = ctx.percepts().time();
        @Nullable PoiMemory best = null;
        double bestDistance = limit;
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
            double distance = ShelterRace.distance(ctx.percepts().position(), in);
            boolean homeward = !ctx.awayOnPurpose();
            double byDark = WALK_PACE * untilDark(ctx);
            @Nullable String line;
            if (!homeward) {
                line = String.format(Locale.ROOT, "going in for the night at (%d, %d, %d), %.0f blocks off",
                        in.x(), in.y(), in.z(), distance);
            } else if (byDark <= 0.0) {
                line = null; // the walk says it is pressing on once its route is known
            } else if (distance <= byDark) {
                line = String.format(Locale.ROOT, "walking home for the night, %.0f blocks, there by dark",
                        distance);
            } else {
                line = String.format(Locale.ROOT,
                        "walking home for the night, %.0f blocks, about %.0f left at dark", distance,
                        distance - byDark);
            }
            if (line != null) {
                ctx.journal().record(Category.BRAIN, describe(), line);
            }
            // The hold lets the space be read again before the next root weighs it.
            return List.of(new RunToShelter(in.x(), in.y(), in.z(), Gait.WALK, homeward),
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
            if (!DigIn.possible(ctx)) {
                return false;
            }
            DigIn.Pocket pocket = DigIn.where(ctx);
            return pocket != null && DigIn.closable(ctx, pocket);
        }

        @Override
        public double estimateCost(BrainContext ctx) {
            return 0.0;
        }

        @Override
        public List<Task> decompose(BrainContext ctx) {
            @Nullable PoiMemory home = DigIn.tonight(ctx) == null
                    ? nearestShelter(ctx, Double.POSITIVE_INFINITY) : null;
            if (home != null) {
                double distance = ShelterRace.distance(ctx.percepts().position(), home.anchor());
                ctx.journal().record(Category.BRAIN, "shelter", ctx.awayOnPurpose()
                        ? String.format(Locale.ROOT, "away on purpose, digging in where it stands, %.0f blocks "
                                + "from shelter", distance)
                        : String.format(Locale.ROOT, "too far to make it home, digging in: %.0f blocks", distance));
            }
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

    /**
     * When each body last said it was waiting the night out, so it says so once a night, not every
     * hold: a settler spent a whole night in the open with nothing in its journal (2026-10-21). Not
     * saved; a restart says it once more.
     */
    private static final Map<AgentKnowledge, Long> SAID = new WeakHashMap<>();

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
            long now = ctx.percepts().time();
            Long said = SAID.get(ctx.knowledge());
            if (said == null || now - said >= DigIn.NIGHT_TICKS) {
                SAID.put(ctx.knowledge(), now);
                Pos here = ctx.percepts().position();
                ctx.journal().record(Category.BRAIN, "shelter", String.format(Locale.ROOT,
                        "waiting the night out at (%d, %d, %d): %s", here.x(), here.y(), here.z(), why(ctx)));
            }
            return List.of(new Idle(HOLD_TICKS));
        }

        /** Why there is nothing better than waiting. */
        private static String why(BrainContext ctx) {
            String none = "no shelter in reach";
            if (!ctx.profile().b(ProfileAspect.NIGHT_DIG_IN)) {
                return none;
            }
            if (!DigIn.possible(ctx)) {
                return none + ", and no way to dig";
            }
            if (DigIn.coverItem(ctx.percepts().inventory()) == null) {
                return none + ", and no safe ground nearby whose own blocks would close a pocket";
            }
            return none + ", and no safe ground to dig in nearby";
        }

        @Override
        public String describe() {
            return "wait the night out";
        }
    }
}
