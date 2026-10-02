package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.act.Placing;
import dev.luizloyola.anima.core.brain.knowledge.BlockProbe;
import dev.luizloyola.anima.core.brain.sense.Being;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.inv.ItemSpec;
import dev.luizloyola.anima.core.log.Category;
import dev.luizloyola.anima.core.nav.CellType;
import dev.luizloyola.anima.core.nav.MoveCapabilities;
import dev.luizloyola.anima.core.nav.NavGrid;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Place one block from somewhere that reaches it: where the body stands if that reaches, else the
 * stand a plan chose, else the one nearest it that does (builder spec, *The body*, 2). The stand is
 * chosen when this is reached, against the world as it is then, so what was placed before it
 * counts.
 *
 * <p>With {@code clearing}, what stands in the cells first is broken — grass where a floor goes —
 * unless it is already the block wanted.
 *
 * <p>When no stand on the ground reaches, or the walks to them fail, a body given a {@code scaffold}
 * rises on a pillar of it beside the work, places, and breaks its way back down; every pillar block
 * is recorded as laid, so one left standing can be found and taken down (builder spec, *The body*,
 * 3).
 *
 * <p>When the walk or the placing fails, or no stand reaches the cell, the last way says why in the
 * journal before the task fails: a builder's handed-back step names its cause.
 */
public final class PlaceFrom implements CompoundTask {

    /** The placer's: from the eye to the middle of the cell. */
    public static final double REACH = 4.5;

    /**
     * How near a stand must be, its cell's middle to the cell's: the placer's reach less half a
     * block, for the body never stands on its cell's middle and the placer measures from its eye.
     * Stands chosen at the full 4.5 were refused from 4.27 on (2026-10-01 probe).
     */
    public static final double STAND_REACH = REACH - 0.5;

    private final Placing placing;
    private final List<Pos> also;
    private final @Nullable Pos stand;
    private final boolean clearing;
    /** The most a scaffold pillar rises: its top block stays in reach from the ground. */
    public static final int MAX_PILLAR = 5;

    private final @Nullable ItemSpec scaffold;
    private final List<Method> methods = List.of(new WalkAndPlace(false), new WalkAndPlace(true), new FromAPillar(),
            new SayWhyNot());
    /** The stand the last decomposition walked to. */
    private @Nullable Pos chosen;
    /** Stands a walk did not get to: never chosen again for this block. */
    private final Set<Pos> walkedOff = new LinkedHashSet<>();

    /**
     * @param also     the other cells the block fills — a door's upper half, a bed's head
     * @param stand    where a plan would stand, tried before any other; null for none
     */
    public PlaceFrom(Placing placing, List<Pos> also, @Nullable Pos stand, boolean clearing) {
        this(placing, also, stand, clearing, null, null, List.of());
    }

    /** @param scaffold what a pillar is laid of when no stand on the ground will do; null for none */
    public PlaceFrom(Placing placing, List<Pos> also, @Nullable Pos stand, boolean clearing,
            @Nullable ItemSpec scaffold) {
        this(placing, also, stand, clearing, scaffold, null, List.of());
    }

    /** One restored mid-way: the stand last walked to, and those a walk did not get to. */
    public PlaceFrom(Placing placing, List<Pos> also, @Nullable Pos stand, boolean clearing,
            @Nullable ItemSpec scaffold, @Nullable Pos chosen, List<Pos> walkedOff) {
        this.placing = placing;
        this.scaffold = scaffold;
        this.also = List.copyOf(also);
        this.stand = stand;
        this.clearing = clearing;
        this.chosen = chosen;
        this.walkedOff.addAll(walkedOff);
    }

    public Optional<ItemSpec> scaffold() {
        return Optional.ofNullable(scaffold);
    }

    public Optional<Pos> chosen() {
        return Optional.ofNullable(chosen);
    }

    public List<Pos> walkedOff() {
        return List.copyOf(walkedOff);
    }

    public Placing placing() {
        return placing;
    }

    public List<Pos> also() {
        return also;
    }

    public Optional<Pos> stand() {
        return Optional.ofNullable(stand);
    }

    public boolean clearing() {
        return clearing;
    }

    @Override
    public List<Method> methods() {
        return methods;
    }

    @Override
    public String describe() {
        return "place " + placing + " from where it reaches";
    }

    /** The block the cell should end up holding. */
    public String wanted() {
        return placing.block().isEmpty() ? placing.itemId() : placing.block();
    }

    /**
     * Where to stand, never where another body stands, or under its head: a second builder on the
     * same site keeps its own stand. Nor where a walk did not get to.
     */
    private Optional<Pos> standFor(BrainContext ctx) {
        MoveCapabilities body = MoveCapabilities.of(ctx.profile());
        Set<Pos> others = notToStandIn(ctx);
        others.addAll(walkedOff);
        Pos here = ctx.percepts().position();
        if (Standing.reaches(ctx.percepts().terrain(), body, here, placing.cell(), others, STAND_REACH)) {
            return Optional.of(here);
        }
        return Standing.reaching(ctx.percepts().terrain(), body, placing.cell(), others, stand, STAND_REACH);
    }

    /** The block's other cells, and where other bodies stand: their feet and the cell over them. */
    private Set<Pos> notToStandIn(BrainContext ctx) {
        Set<Pos> others = new HashSet<>(also);
        for (Being being : ctx.percepts().beings()) {
            others.add(being.pos());
            others.add(new Pos(being.pos().x(), being.pos().y() + 1, being.pos().z()));
        }
        return others;
    }

    /** A pillar: where it stands on the ground, and how many blocks it rises. */
    private record Pillar(Pos base, int height) {
    }

    /**
     * The lowest pillar of what the body carries that puts a stand in reach: on a cell a body could
     * stand on, its column clear for the body to rise through, nearest the body at that height.
     */
    private Optional<Pillar> pillarFor(BrainContext ctx) {
        if (scaffold == null) {
            return Optional.empty();
        }
        int carried = ctx.percepts().inventory().count(scaffold::matches);
        NavGrid grid = ctx.percepts().terrain();
        MoveCapabilities body = MoveCapabilities.of(ctx.profile());
        double eye = body.height() * Standing.EYE;
        int head = (int) Math.ceil(body.height()) - 1;
        Set<Pos> others = notToStandIn(ctx);
        others.add(placing.cell());
        Pos cell = placing.cell();
        Pos here = ctx.percepts().position();
        int span = (int) Math.ceil(STAND_REACH);
        for (int height = 1; height <= Math.min(MAX_PILLAR, carried); height++) {
            Pillar best = null;
            long bestDistance = Long.MAX_VALUE;
            for (int y = cell.y() - span - height - 1; y <= cell.y() - height + span; y++) {
                for (int z = cell.z() - span; z <= cell.z() + span; z++) {
                    for (int x = cell.x() - span; x <= cell.x() + span; x++) {
                        double dx = cell.x() - x;
                        double dy = cell.y() + 0.5 - (y + height + eye);
                        double dz = cell.z() - z;
                        if (dx * dx + dy * dy + dz * dz > STAND_REACH * STAND_REACH) {
                            continue;
                        }
                        Pos base = new Pos(x, y, z);
                        if (others.contains(base) || !Standing.standable(grid, body, x, y, z)
                                || !clear(grid, x, y, z, height + head, others)) {
                            continue;
                        }
                        long distance = (long) (x - here.x()) * (x - here.x()) + (long) (y - here.y()) * (y - here.y())
                                + (long) (z - here.z()) * (z - here.z());
                        if (distance < bestDistance) {
                            best = new Pillar(base, height);
                            bestDistance = distance;
                        }
                    }
                }
            }
            if (best != null) {
                return Optional.of(best);
            }
        }
        return Optional.empty();
    }

    /** The {@code cells} over the base clear to rise through, and none of them taken. */
    private static boolean clear(NavGrid grid, int x, int y, int z, int cells, Set<Pos> others) {
        for (int up = 1; up <= cells; up++) {
            if (grid.cell(x, y + up, z) != CellType.PASSABLE || others.contains(new Pos(x, y + up, z))) {
                return false;
            }
        }
        return true;
    }

    /** After a walk that did not get to its stand, that stand is off the list. */
    private void noteWalk(BrainContext ctx) {
        if (chosen != null && !chosen.equals(ctx.percepts().position())) {
            walkedOff.add(chosen);
        }
    }

    /**
     * Walk to a stand and place. {@code again} is the second try after a walk that did not get
     * there: another stand, the nearest still on the list.
     */
    private final class WalkAndPlace implements Method {
        private final boolean again;

        WalkAndPlace(boolean again) {
            this.again = again;
        }

        @Override
        public boolean applicable(BrainContext ctx) {
            if (again) {
                if (chosen == null || chosen.equals(ctx.percepts().position())) {
                    return false;
                }
                noteWalk(ctx);
            }
            return standFor(ctx).isPresent();
        }

        @Override
        public double estimateCost(BrainContext ctx) {
            return again ? 0.5 : 0.0;
        }

        @Override
        public List<Task> decompose(BrainContext ctx) {
            if (again) {
                noteWalk(ctx);
            }
            List<Task> steps = new ArrayList<>();
            Pos at = standFor(ctx).orElseThrow();
            chosen = at;
            if (!at.equals(ctx.percepts().position())) {
                steps.add(new GoTo(at.x(), at.y(), at.z()));
            }
            if (clearing) {
                BlockProbe probe = ctx.percepts().blocks();
                List<Pos> cells = new ArrayList<>();
                cells.add(placing.cell());
                cells.addAll(also);
                for (Pos cell : cells) {
                    if (!probe.empty(cell.x(), cell.y(), cell.z())
                            && !wanted().equals(probe.idAt(cell.x(), cell.y(), cell.z()))) {
                        steps.add(new BreakBlock(cell.x(), cell.y(), cell.z()));
                    }
                }
            }
            steps.add(new PlaceBlock(placing));
            return steps;
        }

        @Override
        public String describe() {
            return again ? "walk to another stand, then place" : "walk to a stand that reaches, then place";
        }
    }

    /** Rise on a recorded pillar beside the work, place, and break back down to the ground. */
    private final class FromAPillar implements Method {
        @Override
        public boolean applicable(BrainContext ctx) {
            return pillarFor(ctx).isPresent();
        }

        @Override
        public double estimateCost(BrainContext ctx) {
            return 0.75;
        }

        @Override
        public List<Task> decompose(BrainContext ctx) {
            Pillar pillar = pillarFor(ctx).orElseThrow();
            Pos base = pillar.base();
            chosen = new Pos(base.x(), base.y() + pillar.height(), base.z());
            List<Task> steps = new ArrayList<>();
            if (!base.equals(ctx.percepts().position())) {
                steps.add(new GoTo(base.x(), base.y(), base.z()));
            }
            for (int i = 0; i < pillar.height(); i++) {
                steps.add(new Rise(scaffold, true));
            }
            if (clearing) {
                BlockProbe probe = ctx.percepts().blocks();
                List<Pos> cells = new ArrayList<>();
                cells.add(placing.cell());
                cells.addAll(also);
                for (Pos cell : cells) {
                    if (!probe.empty(cell.x(), cell.y(), cell.z())
                            && !wanted().equals(probe.idAt(cell.x(), cell.y(), cell.z()))) {
                        steps.add(new Try(new BreakBlock(cell.x(), cell.y(), cell.z())));
                    }
                }
            }
            // Down again whatever the placing did: a pillar is not left for a refused block.
            steps.add(new Try(new PlaceBlock(placing)));
            for (int i = pillar.height() - 1; i >= 0; i--) {
                steps.add(new BreakBlock(base.x(), base.y() + i, base.z()));
            }
            return steps;
        }

        @Override
        public String describe() {
            return "rise on a pillar beside it, place, and come down";
        }
    }

    /** The way after every other: what went wrong, in the journal, and then a failure. */
    private final class SayWhyNot implements Method {
        @Override
        public boolean applicable(BrainContext ctx) {
            return true;
        }

        @Override
        public double estimateCost(BrainContext ctx) {
            return 1.0;
        }

        @Override
        public List<Task> decompose(BrainContext ctx) {
            noteWalk(ctx);
            return List.of(new WhyNot(placing, stand, chosen));
        }

        @Override
        public String describe() {
            return "say why it would not go in";
        }
    }

    /**
     * Writes why a placing failed and fails: no stand reached the cell, the walk did not get to the
     * one chosen, the cell holds something else, or the placer refused it from where the body stood.
     */
    public static final class WhyNot implements PrimitiveTask {

        private final Placing placing;
        private final @Nullable Pos planned;
        private final @Nullable Pos chosen;
        private String reason = "";

        public WhyNot(Placing placing, @Nullable Pos planned, @Nullable Pos chosen) {
            this.placing = placing;
            this.planned = planned;
            this.chosen = chosen;
        }

        public Placing placing() {
            return placing;
        }

        public Optional<Pos> planned() {
            return Optional.ofNullable(planned);
        }

        public Optional<Pos> chosen() {
            return Optional.ofNullable(chosen);
        }

        @Override
        public TaskStatus tick(BrainContext ctx) {
            reason = reason(ctx);
            ctx.journal().record(Category.BODY, "place", "gave up on " + placing + ": " + reason);
            return TaskStatus.FAILED;
        }

        private String reason(BrainContext ctx) {
            MoveCapabilities body = MoveCapabilities.of(ctx.profile());
            Pos here = ctx.percepts().position();
            Pos cell = placing.cell();
            if (chosen == null) {
                if (planned == null) {
                    return "no stand reaches it";
                }
                String planNot = Standing.whyNot(ctx.percepts().terrain(), body, planned.x(), planned.y(), planned.z());
                return "no stand reaches it; the planned one at " + at(planned) + " — "
                        + (planNot.isEmpty() ? "stands, but not in reach or in the work" : planNot);
            }
            if (!chosen.equals(here)) {
                String standNot = Standing.whyNot(ctx.percepts().terrain(), body, chosen.x(), chosen.y(), chosen.z());
                return "never got to the stand at " + at(chosen) + ", stood at " + at(here)
                        + (standNot.isEmpty() ? "" : " — the stand: " + standNot);
            }
            String there = ctx.percepts().blocks().idAt(cell.x(), cell.y(), cell.z());
            if (!ctx.percepts().blocks().empty(cell.x(), cell.y(), cell.z()) && !there.isEmpty()) {
                return "the cell holds " + there;
            }
            return "the placer refused it from " + at(here);
        }

        private static String at(Pos pos) {
            return "(" + pos.x() + ", " + pos.y() + ", " + pos.z() + ")";
        }

        @Override
        public void cancel(BrainContext ctx) {
        }

        @Override
        public String describe() {
            return "say why " + placing + " would not go in";
        }

        @Override
        public String failureDetail() {
            return reason.isEmpty() ? "could not place " + placing : reason;
        }
    }
}
