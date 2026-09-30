package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.act.Gazer;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.knowledge.Region;
import dev.luizloyola.anima.core.brain.sense.Being;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.log.Category;
import dev.luizloyola.anima.core.nav.MoveCapabilities;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Go where a herd was and find it (directions spec, decision 17): walk to its ground and look
 * around, then out to four points on a ring {@link #REACQUIRE} times its bounds — the sense's own
 * re-acquire factor — looking at each. A fair animal of the species in view ends it, SUCCESS, and
 * the hunt's next round finds it there. After the last point the herd is forgotten: FAILED.
 */
public final class Scout implements PrimitiveTask {

    static final double REACQUIRE = 2.5;
    static final int MIN_RADIUS = 8;
    /** The herd's ground, then the ring's four points. */
    static final int STOPS = 5;
    /** Ticks each of a stop's three looks is held — a Person's look back. */
    static final int LOOK_TICKS = 8;
    static final int LOOKS = 3;
    /** How far out a look is aimed, so the body's own step does not swing the head. */
    private static final double LOOK_DISTANCE = 12.0;
    /** How far up or down a stop may settle to find footing. */
    private static final int FOOTING_REACH = 8;

    private final String species;
    private final Pos anchor;
    private final int radius;
    private int index;
    /** Ticks of looking left at this stop; -1 while walking. */
    private int look = -1;
    private @Nullable GoTo leg;
    private @Nullable String failure;

    /**
     * @param species the herd's species
     * @param anchor where it was remembered, which is also the memory forgotten if it is gone
     * @param radius the ring's radius — {@link #radiusOf} a memory's bounds
     */
    public Scout(String species, Pos anchor, int radius) {
        this.species = species;
        this.anchor = anchor;
        this.radius = radius;
    }

    /** The ring for a remembered herd: {@link #REACQUIRE} times its half-width, at least {@link #MIN_RADIUS}. */
    public static int radiusOf(Region bounds) {
        int half = Math.max(bounds.max().x() - bounds.min().x(), bounds.max().z() - bounds.min().z()) / 2;
        return Math.max(MIN_RADIUS, (int) Math.ceil(half * REACQUIRE));
    }

    @Override
    public TaskStatus tick(BrainContext ctx) {
        Optional<Being> found = Prey.nearest(ctx, species::equals);
        if (found.isPresent()) {
            dropLeg(ctx);
            ctx.journal().record(Category.BRAIN, "hunt", "found a " + species);
            return TaskStatus.SUCCESS;
        }
        if (look >= 0) {
            lookAround(ctx, LOOKS * LOOK_TICKS - 1 - look, 0.0);
            if (--look < 0) {
                index++;
            }
            return TaskStatus.RUNNING;
        }
        if (leg == null) {
            if (index >= STOPS) {
                ctx.knowledge().forget(PoiKind.HERD, anchor);
                failure = "no " + species + " at " + anchor.x() + ", " + anchor.y() + ", " + anchor.z()
                        + " any more";
                ctx.journal().record(Category.BRAIN, "hunt", failure);
                return TaskStatus.FAILED;
            }
            Pos stop = stop(ctx, index);
            if (stop == null) {
                index++;
                return TaskStatus.RUNNING;
            }
            leg = new GoTo(stop.x(), stop.y(), stop.z());
        }
        if (leg.tick(ctx) == TaskStatus.RUNNING) {
            return TaskStatus.RUNNING;
        }
        leg = null; // arrived or not, a look from wherever the walk ended
        look = LOOKS * LOOK_TICKS - 1;
        return TaskStatus.RUNNING;
    }

    /** Somewhere to stand at the i-th stop, or null where there is no footing to be had. */
    private @Nullable Pos stop(BrainContext ctx, int i) {
        int x = anchor.x();
        int z = anchor.z();
        switch (i) {
            case 1 -> x += radius;
            case 2 -> z += radius;
            case 3 -> x -= radius;
            case 4 -> z -= radius;
            default -> { }
        }
        return Standing.spot(ctx.percepts().terrain(), MoveCapabilities.of(ctx.profile()), x, z,
                anchor.y(), FOOTING_REACH).orElse(null);
    }

    /**
     * The look for tick {@code tick} of a stop: three looks a third of a turn apart from
     * {@code base} radians, each held {@link #LOOK_TICKS}. The sense re-checks on every head turn,
     * so each look is a real one.
     */
    static void lookAround(BrainContext ctx, int tick, double base) {
        double angle = base + (tick / LOOK_TICKS) * 2.0 * Math.PI / LOOKS;
        Pos here = ctx.percepts().position();
        ctx.actuators().gazer().lookAt(here.x() + 0.5 + LOOK_DISTANCE * Math.cos(angle), here.y() + 1.5,
                here.z() + 0.5 + LOOK_DISTANCE * Math.sin(angle), Gazer.Priority.WORK);
    }

    private void dropLeg(BrainContext ctx) {
        if (leg != null) {
            leg.cancel(ctx);
            leg = null;
        }
    }

    @Override
    public void cancel(BrainContext ctx) {
        dropLeg(ctx);
    }

    @Override
    public String describe() {
        return "scout for " + species;
    }

    @Override
    public String failureDetail() {
        return failure;
    }

    // ── continuity ───────────────────────────────────────────────────────────────────────────

    public String species() {
        return species;
    }

    public Pos anchor() {
        return anchor;
    }

    public int radius() {
        return radius;
    }

    public int index() {
        return index;
    }

    public int look() {
        return look;
    }

    public @Nullable GoTo leg() {
        return leg;
    }

    public Scout resume(int index, int look, @Nullable GoTo leg) {
        this.index = index;
        this.look = look;
        this.leg = leg;
        return this;
    }
}
