package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.act.Placing;
import dev.luizloyola.anima.core.brain.knowledge.BlockProbe;
import dev.luizloyola.anima.core.brain.sense.Being;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.log.Category;
import dev.luizloyola.anima.core.nav.MoveCapabilities;
import java.util.ArrayList;
import java.util.HashSet;
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
 * <p>When the walk or the placing fails, or no stand reaches the cell, the last way says why in the
 * journal before the task fails: a builder's handed-back step names its cause.
 */
public final class PlaceFrom implements CompoundTask {

    /** The placer's: from the eye to the middle of the cell. */
    public static final double REACH = 4.5;

    private final Placing placing;
    private final List<Pos> also;
    private final @Nullable Pos stand;
    private final boolean clearing;
    private final List<Method> methods = List.of(new WalkAndPlace(), new SayWhyNot());
    /** The stand the last decomposition walked to; not saved, as only the journal line reads it. */
    private @Nullable Pos chosen;

    /**
     * @param also     the other cells the block fills — a door's upper half, a bed's head
     * @param stand    where a plan would stand, tried before any other; null for none
     */
    public PlaceFrom(Placing placing, List<Pos> also, @Nullable Pos stand, boolean clearing) {
        this.placing = placing;
        this.also = List.copyOf(also);
        this.stand = stand;
        this.clearing = clearing;
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
     * same site keeps its own stand.
     */
    private Optional<Pos> standFor(BrainContext ctx) {
        MoveCapabilities body = MoveCapabilities.of(ctx.profile());
        Set<Pos> others = new HashSet<>(also);
        for (Being being : ctx.percepts().beings()) {
            others.add(being.pos());
            others.add(new Pos(being.pos().x(), being.pos().y() + 1, being.pos().z()));
        }
        Pos here = ctx.percepts().position();
        if (Standing.reaches(ctx.percepts().terrain(), body, here, placing.cell(), others, REACH)) {
            return Optional.of(here);
        }
        return Standing.reaching(ctx.percepts().terrain(), body, placing.cell(), others, stand, REACH);
    }

    private final class WalkAndPlace implements Method {
        @Override
        public boolean applicable(BrainContext ctx) {
            return standFor(ctx).isPresent();
        }

        @Override
        public double estimateCost(BrainContext ctx) {
            return 0.0;
        }

        @Override
        public List<Task> decompose(BrainContext ctx) {
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
            return "walk to a stand that reaches, then place";
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
