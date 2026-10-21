package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.agent.ProfileAspect;
import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.knowledge.AgentKnowledge;
import dev.luizloyola.anima.core.brain.knowledge.BlockProbe;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.knowledge.PoiMemory;
import dev.luizloyola.anima.core.brain.knowledge.ShelterNoter;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.inv.Inventory;
import dev.luizloyola.anima.core.log.Category;
import dev.luizloyola.anima.core.nav.CellType;
import dev.luizloyola.anima.core.nav.NavGrid;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Digging into the ground for a night with no shelter in reach (Luiz, 2026-10-20): three blocks
 * straight down from where the body stands, then a carried block put back over its head, which
 * leaves it in a sealed pocket two high, roofed and shut, that nothing walking gets into.
 *
 * <p>Three rather than two because a block goes in only against a face: on a plain the cell the
 * body stood in has none, and the cell one down has the ground on all four sides.
 *
 * <p>The body steps to the middle of its cell first: off-centre its box rests on the rim, and a
 * settler dug her pocket out from under her, put the lid on it and stood on top all night
 * (2026-10-20). The pocket counts as dug only with her feet in it and the lid in ({@link SealIn});
 * a dig that falls short leaves that pocket alone a while ({@link GiveUpShelter}).
 *
 * <p>The pocket is remembered before the first block comes out ({@link ShelterNoter#dugIn}), for
 * the night it is dug ({@link #NIGHT_TICKS}): a dig cut short, by a fight or a restart, carries on
 * where it stopped, a body that left it goes back to it before digging another, and the escape
 * drive leaves a body in its own pocket alone until sunrise, then climbs it out.
 *
 * <p>Refused ground ({@link #safe}): anything but solid ground in the column and its walls, a block
 * that falls (sand, gravel) there, anything hollow under the floor, water or lava within a block of
 * it, and anywhere within a column of ground the consuming mod keeps a body off ({@link Fence}).
 */
public final class DigIn implements CompoundTask {

    /** How far round the body, in columns, a pocket is looked for. */
    static final int AROUND = 2;

    /**
     * How far a body standing on ground kept off looks for ground that is not: a settler who stood
     * on the hut's pad at dusk dug nothing, and spent the night in the open (2026-10-21).
     */
    static final int OFF_THE_FENCE = 24;

    /** How far up or down the ground a pocket is dug in off the fence may be from the feet. */
    static final int OFF_THE_FENCE_RISE = 4;

    /** Cells dug below the feet: the pocket's two, and the one the cover goes back into. */
    static final int DEPTH = 3;

    /** How long a dug pocket is this night's: a night is 10,500 ticks, sunset to sunrise. */
    public static final long NIGHT_TICKS = 12_000L;

    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    /**
     * Ground a body may not dig into, nor beside, since climbing out at sunrise cuts the column next
     * door: the consuming mod's buildings, standing or planned. Anima knows none.
     */
    @FunctionalInterface
    public interface Fence {
        boolean keepsOff(int x, int z);
    }

    private static volatile Fence fence = (x, z) -> false;

    /** Installed once by the consuming mod. */
    public static void keepOffBy(Fence rule) {
        fence = rule;
    }

    private final List<Method> methods = List.of(new Dig(), new GiveUp());

    /** Where the pocket is: its floor cell, and the cell over the head the cover goes into. */
    public record Pocket(Pos feet, Pos cover) {
        static Pocket under(int x, int standing, int z) {
            return new Pocket(new Pos(x, standing - DEPTH, z), new Pos(x, standing - 1, z));
        }

        /** The cell a body stands in over the pocket before digging it. */
        Pos top() {
            return new Pos(cover.x(), cover.y() + 1, cover.z());
        }
    }

    @Override
    public List<Method> methods() {
        return methods;
    }

    /** Yes, unlike escaping: the body is putting itself in a pit on purpose. */
    @Override
    public boolean reshapesGround() {
        return true;
    }

    @Override
    public String describe() {
        return "dig in for the night";
    }

    /** Whether this body could dig in at all — the cheap half, asked every tick at night. */
    public static boolean possible(BrainContext ctx) {
        return ctx.profile().b(ProfileAspect.NIGHT_DIG_IN)
                && ctx.profile().b(ProfileAspect.BODY_CAN_DIG)
                && ctx.profile().b(ProfileAspect.BODY_CAN_BUILD)
                && coverItem(ctx.percepts().inventory()) != null;
    }

    /** The pocket this body set out to dig tonight and stands in the column of, or null. */
    public static @Nullable PoiMemory begun(BrainContext ctx) {
        @Nullable PoiMemory tonight = tonight(ctx);
        return tonight != null && tonight.bounds().contains(ctx.percepts().position()) ? tonight : null;
    }

    /** The pocket this body set out to dig tonight, wherever it stands, or null. */
    static @Nullable PoiMemory tonight(BrainContext ctx) {
        long now = ctx.percepts().time();
        for (PoiMemory memory : ctx.knowledge().sighted(PoiKind.SHELTER)) {
            if (ShelterNoter.dug(memory) && now - memory.lastSeenTick() < NIGHT_TICKS
                    && !ctx.knowledge().isAvoided(PoiKind.SHELTER, memory.anchor(), now)) {
                return memory;
            }
        }
        return null;
    }

    /**
     * Where to dig: tonight's pocket, under way or to go back to within {@link RunToShelter#REACH};
     * else the nearest safe column round the body, on ground nobody keeps; a body on or beside
     * ground kept off looks as far as {@link #OFF_THE_FENCE} for some. A building that gives cover
     * is stayed in before this is asked.
     */
    static @Nullable Pocket where(BrainContext ctx) {
        Pos here = ctx.percepts().position();
        @Nullable PoiMemory tonight = tonight(ctx);
        if (tonight != null && ShelterRace.distance(here, tonight.anchor()) <= RunToShelter.REACH) {
            return new Pocket(tonight.anchor(), ShelterNoter.cover(tonight));
        }
        boolean fenced = keptOffNear(here.x(), here.z());
        int around = fenced ? OFF_THE_FENCE : AROUND;
        NavGrid grid = ctx.percepts().terrain();
        BlockProbe probe = ctx.percepts().blocks();
        List<int[]> offsets = new ArrayList<>();
        for (int dx = -around; dx <= around; dx++) {
            for (int dz = -around; dz <= around; dz++) {
                offsets.add(new int[] {dx, dz});
            }
        }
        offsets.sort(Comparator.comparingInt(o -> o[0] * o[0] + o[1] * o[1]));
        for (int[] o : offsets) {
            int x = here.x() + o[0];
            int z = here.z() + o[1];
            if (keptOffNear(x, z)) {
                continue; // no block read for ground kept off, most of a search off a building
            }
            int standing = fenced ? probe.groundY(x, z) + 1 : here.y();
            if (fenced && Math.abs(standing - here.y()) > OFF_THE_FENCE_RISE) {
                continue;
            }
            Pocket pocket = Pocket.under(x, standing, z);
            if (!ctx.knowledge().isAvoided(PoiKind.SHELTER, pocket.feet(), ctx.percepts().time())
                    && safe(grid, probe, x, standing, z)) {
                return pocket;
            }
        }
        return null;
    }

    /**
     * Whether a pocket may be dug under a body standing at {@code (x, y, z)}: room to stand there;
     * the three cells down and the walls beside them solid natural ground that stays put; solid
     * ground two more down, so the floor is not the roof of a cave; and no water or lava in the
     * block round any of it, which a dig would let in.
     */
    static boolean safe(NavGrid grid, BlockProbe probe, int x, int y, int z) {
        if (grid.cell(x, y, z) != CellType.PASSABLE || grid.cell(x, y + 1, z) != CellType.PASSABLE) {
            return false;
        }
        for (int d = 1; d <= DEPTH; d++) {
            if (!firm(grid, probe, x, y - d, z) || !probe.at(x, y - d, z).ground()) {
                return false;
            }
            for (int[] side : SIDES) {
                if (!firm(grid, probe, x + side[0], y - d, z + side[1])) {
                    return false;
                }
            }
        }
        for (int d = DEPTH + 1; d <= DEPTH + 2; d++) {
            if (grid.cell(x, y - d, z) != CellType.GROUND) {
                return false;
            }
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -DEPTH - 2; dy <= 1; dy++) {
                    CellType cell = grid.cell(x + dx, y + dy, z + dz);
                    if (cell == CellType.WATER || cell == CellType.DANGER) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** Whether the column or one beside it is ground the consuming mod keeps a body off. */
    static boolean keptOffNear(int x, int z) {
        Fence rule = fence;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (rule.keepsOff(x + dx, z + dz)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean firm(NavGrid grid, BlockProbe probe, int x, int y, int z) {
        return grid.cell(x, y, z) == CellType.GROUND && !probe.loose(x, y, z);
    }

    /** A carried block a walk may lay — what goes back over the head. */
    static @Nullable String coverItem(Inventory inventory) {
        for (Inventory.Entry entry : inventory.occupied()) {
            String id = entry.stack().id();
            if (BlocksToCross.SPEC.matches(id)) {
                return id;
            }
        }
        return null;
    }

    private final class Dig implements Method {
        @Override
        public boolean applicable(BrainContext ctx) {
            return possible(ctx) && where(ctx) != null;
        }

        @Override
        public double estimateCost(BrainContext ctx) {
            return 0.0;
        }

        @Override
        public List<Task> decompose(BrainContext ctx) {
            @Nullable Pocket pocket = where(ctx);
            @Nullable String cover = coverItem(ctx.percepts().inventory());
            if (pocket == null || cover == null) {
                return List.of();
            }
            Pos here = ctx.percepts().position();
            NavGrid grid = ctx.percepts().terrain();
            Pos lid = pocket.cover();
            boolean lidIn = grid.cell(lid.x(), lid.y(), lid.z()) == CellType.GROUND;
            if (here.equals(pocket.feet()) && lidIn) {
                return List.of(new Idle(NightShelterStep.HOLD_TICKS)); // in already
            }
            boolean there = here.x() == pocket.feet().x() && here.z() == pocket.feet().z()
                    && here.y() >= pocket.feet().y() && here.y() <= pocket.top().y();
            if (tonight(ctx) == null) {
                begin(ctx, pocket);
            }
            List<Task> steps = new ArrayList<>();
            if (!there) {
                Pos top = pocket.top();
                steps.add(new GoTo(top.x(), top.y(), top.z()));
            }
            steps.add(new Centre());
            int from = there ? here.y() - 1 : pocket.cover().y();
            for (int y = from; y >= pocket.feet().y(); y--) {
                if (grid.cell(pocket.feet().x(), y, pocket.feet().z()) != CellType.PASSABLE) {
                    steps.add(new BreakBlock(pocket.feet().x(), y, pocket.feet().z()));
                }
            }
            if (!lidIn || from >= lid.y()) {
                steps.add(new PlaceBlock(cover, lid.x(), lid.y(), lid.z()));
            }
            steps.add(new SealIn(pocket.feet().x(), pocket.feet().y(), pocket.feet().z()));
            // The hold lets the space be read again before the next root weighs it.
            steps.add(new Idle(FleeStep.HOLD_TICKS));
            return steps;
        }

        /** Writes the pocket down before the first block comes out, and drops any older one. */
        private void begin(BrainContext ctx, Pocket pocket) {
            AgentKnowledge knowledge = ctx.knowledge();
            for (PoiMemory old : List.copyOf(knowledge.sighted(PoiKind.SHELTER))) {
                if (ShelterNoter.dug(old)) {
                    knowledge.forget(PoiKind.SHELTER, old.anchor());
                }
            }
            knowledge.note(ShelterNoter.dugIn(pocket.feet(), pocket.cover(), ctx.percepts().time()),
                    AgentKnowledge.maxPerKind(ctx.profile()));
            Pos at = pocket.top();
            Pos here = ctx.percepts().position();
            ctx.journal().record(Category.BRAIN, "dig in", String.format(Locale.ROOT,
                    "digging in for the night at (%d, %d, %d)%s", at.x(), at.y(), at.z(),
                    keptOffNear(here.x(), here.z()) ? ", off the ground kept round a building" : ""));
        }

        @Override
        public String describe() {
            return "dig";
        }
    }

    /** The dig fell short: that pocket is left alone a while, and the next root looks again. */
    private static final class GiveUp implements Method {
        @Override
        public boolean applicable(BrainContext ctx) {
            return tonight(ctx) != null;
        }

        @Override
        public double estimateCost(BrainContext ctx) {
            return 0.0;
        }

        @Override
        public List<Task> decompose(BrainContext ctx) {
            @Nullable PoiMemory pocket = tonight(ctx);
            if (pocket == null) {
                return List.of();
            }
            Pos at = pocket.anchor();
            return List.of(new GiveUpShelter(at.x(), at.y(), at.z()));
        }

        @Override
        public String describe() {
            return "give the pocket up";
        }
    }
}
