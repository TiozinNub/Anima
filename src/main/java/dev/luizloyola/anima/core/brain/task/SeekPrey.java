package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.sense.Being;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.log.Category;
import dev.luizloyola.anima.core.nav.MoveCapabilities;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Go looking for something to hunt when no herd is known (directions spec, decision 17): legs of
 * {@link #LEG} blocks on one heading, a look around at the end of each. Prey in view ends it,
 * SUCCESS, and the hunt's next round goes for it. After {@link #LEGS} legs, or with nowhere left to
 * walk, it FAILS and rests the ground it started and ended on for {@link #REST_TICKS}.
 *
 * <p>A leg that cannot be walked turns the heading a quarter. Deliberately small beside the home
 * search's explore: a hungry body looks about, it does not travel.
 */
public final class SeekPrey implements PrimitiveTask {

    static final int LEG = 32;
    static final int LEGS = 4;
    static final long REST_TICKS = 2400;
    /** The grain a search rests: a body that walked elsewhere may look again there. */
    static final int REST_CELL = 64;
    private static final int FOOTING_REACH = 16;
    private static final String[] HEADINGS = {"E", "SE", "S", "SW", "W", "NW", "N", "NE"};

    private final ItemSpec wanted;
    private @Nullable Pos origin;
    /** Eighths of a turn from east, toward south; -1 until the first tick draws one. */
    private int heading = -1;
    private int legs;
    /** Ticks of looking left at this stop; -1 while walking. */
    private int look = -1;
    private @Nullable GoTo leg;
    private @Nullable String failure;

    public SeekPrey(ItemSpec wanted) {
        this.wanted = wanted;
    }

    /**
     * The avoid-mark a search leaves, one per {@link #REST_CELL} square. Its y is never a block's,
     * so it cannot rest a herd remembered at the same spot.
     */
    public static Pos restKey(Pos at) {
        return new Pos(Math.floorDiv(at.x(), REST_CELL), Integer.MIN_VALUE, Math.floorDiv(at.z(), REST_CELL));
    }

    @Override
    public TaskStatus tick(BrainContext ctx) {
        Pos here = ctx.percepts().position();
        if (origin == null) {
            origin = here;
            heading = ctx.random().nextInt(HEADINGS.length);
            ctx.journal().record(Category.BRAIN, "hunt",
                    "looking for something to hunt, heading " + HEADINGS[heading]);
        }
        Optional<Being> found = Prey.nearest(ctx, species -> Prey.yields(ctx, species, wanted));
        if (found.isPresent()) {
            dropLeg(ctx);
            Pos at = found.get().pos();
            ctx.journal().record(Category.BRAIN, "hunt", "found a " + found.get().species() + " at "
                    + at.x() + ", " + at.y() + ", " + at.z());
            return TaskStatus.SUCCESS;
        }
        if (look >= 0) {
            Scout.lookAround(ctx, Scout.LOOKS * Scout.LOOK_TICKS - 1 - look, heading * Math.PI / 4.0);
            if (--look < 0 && ++legs >= LEGS) {
                return giveUp(ctx, "found nothing to hunt");
            }
            return TaskStatus.RUNNING;
        }
        if (leg == null) {
            Pos end = legEnd(ctx, here);
            if (end == null) {
                return giveUp(ctx, "nowhere to look for something to hunt");
            }
            leg = new GoTo(end.x(), end.y(), end.z());
        }
        TaskStatus walked = leg.tick(ctx);
        if (walked == TaskStatus.RUNNING) {
            return TaskStatus.RUNNING;
        }
        if (walked == TaskStatus.FAILED) {
            heading = (heading + 2) % HEADINGS.length;
        }
        leg = null;
        look = Scout.LOOKS * Scout.LOOK_TICKS - 1;
        return TaskStatus.RUNNING;
    }

    /** Where this leg ends: the heading, else the nearest heading either side with footing. */
    private @Nullable Pos legEnd(BrainContext ctx, Pos here) {
        MoveCapabilities body = MoveCapabilities.of(ctx.profile());
        for (int turn : new int[] {0, 1, -1, 2, -2, 3, -3, 4}) {
            int h = Math.floorMod(heading + turn, HEADINGS.length);
            double angle = h * Math.PI / 4.0;
            int x = here.x() + (int) Math.round(LEG * Math.cos(angle));
            int z = here.z() + (int) Math.round(LEG * Math.sin(angle));
            Optional<Pos> spot = Standing.spot(ctx.percepts().terrain(), body, x, z, here.y(), FOOTING_REACH);
            if (spot.isPresent()) {
                heading = h;
                return spot.get();
            }
        }
        return null;
    }

    private TaskStatus giveUp(BrainContext ctx, String why) {
        dropLeg(ctx);
        long until = ctx.percepts().time() + REST_TICKS;
        ctx.knowledge().avoid(PoiKind.HERD, restKey(origin), until);
        ctx.knowledge().avoid(PoiKind.HERD, restKey(ctx.percepts().position()), until);
        failure = why;
        ctx.journal().record(Category.BRAIN, "hunt", why);
        return TaskStatus.FAILED;
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
        return "look for something to hunt";
    }

    @Override
    public String failureDetail() {
        return failure;
    }

    // ── continuity ───────────────────────────────────────────────────────────────────────────

    public ItemSpec wanted() {
        return wanted;
    }

    public @Nullable Pos origin() {
        return origin;
    }

    public int heading() {
        return heading;
    }

    public int legs() {
        return legs;
    }

    public int look() {
        return look;
    }

    public @Nullable GoTo leg() {
        return leg;
    }

    public SeekPrey resume(@Nullable Pos origin, int heading, int legs, int look, @Nullable GoTo leg) {
        this.origin = origin;
        this.heading = heading;
        this.legs = legs;
        this.look = look;
        this.leg = leg;
        return this;
    }
}
