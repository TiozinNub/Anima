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
 * <p>The pocket is remembered before the first block comes out ({@link ShelterNoter#dugIn}), so a
 * dig cut short, by a fight or a restart, carries on where it stopped, and the escape drive leaves a
 * body in its own pocket alone until sunrise, then climbs it out.
 *
 * <p>Refused ground ({@link #safe}): anything but solid natural ground in the column and its walls,
 * a block that falls (sand, gravel) there, anything hollow under the floor, water or lava within a
 * block of it.
 */
public final class DigIn implements CompoundTask {

    /** How far round the body, in columns, a pocket is looked for. */
    static final int AROUND = 2;

    /** Cells dug below the feet: the pocket's two, and the one the cover goes back into. */
    static final int DEPTH = 3;

    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private final List<Method> methods = List.of(new Dig());

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

    /** The pocket this body set out to dig and stands in the column of, or null. */
    public static @Nullable PoiMemory begun(BrainContext ctx) {
        Pos here = ctx.percepts().position();
        for (PoiMemory memory : ctx.knowledge().sighted(PoiKind.SHELTER)) {
            if (ShelterNoter.dug(memory) && memory.bounds().contains(here)) {
                return memory;
            }
        }
        return null;
    }

    /** Where to dig: the pocket under way, else the nearest safe column round the body. */
    static @Nullable Pocket where(BrainContext ctx) {
        @Nullable PoiMemory begun = begun(ctx);
        if (begun != null) {
            return new Pocket(begun.anchor(), ShelterNoter.cover(begun));
        }
        Pos here = ctx.percepts().position();
        NavGrid grid = ctx.percepts().terrain();
        BlockProbe probe = ctx.percepts().blocks();
        List<int[]> offsets = new ArrayList<>();
        for (int dx = -AROUND; dx <= AROUND; dx++) {
            for (int dz = -AROUND; dz <= AROUND; dz++) {
                offsets.add(new int[] {dx, dz});
            }
        }
        offsets.sort(Comparator.comparingInt(o -> o[0] * o[0] + o[1] * o[1]));
        for (int[] o : offsets) {
            int x = here.x() + o[0];
            int z = here.z() + o[1];
            if (safe(grid, probe, x, here.y(), z)) {
                return Pocket.under(x, here.y(), z);
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
            boolean there = here.x() == pocket.feet().x() && here.z() == pocket.feet().z();
            List<Task> steps = new ArrayList<>();
            if (!there) {
                begin(ctx, pocket);
                Pos top = pocket.top();
                steps.add(new GoTo(top.x(), top.y(), top.z()));
            } else if (begun(ctx) == null) {
                begin(ctx, pocket);
            }
            int from = there ? here.y() - 1 : pocket.cover().y();
            for (int y = from; y >= pocket.feet().y(); y--) {
                if (grid.cell(pocket.feet().x(), y, pocket.feet().z()) != CellType.PASSABLE) {
                    steps.add(new BreakBlock(pocket.feet().x(), y, pocket.feet().z()));
                }
            }
            Pos lid = pocket.cover();
            if (!steps.isEmpty() || grid.cell(lid.x(), lid.y(), lid.z()) != CellType.GROUND) {
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
            ctx.journal().record(Category.BRAIN, "dig in", String.format(Locale.ROOT,
                    "digging in for the night at (%d, %d, %d)", at.x(), at.y(), at.z()));
        }

        @Override
        public String describe() {
            return "dig";
        }
    }
}
