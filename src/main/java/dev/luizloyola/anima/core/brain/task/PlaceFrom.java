package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.act.Placing;
import dev.luizloyola.anima.core.brain.knowledge.BlockProbe;
import dev.luizloyola.anima.core.brain.sense.Pos;
import dev.luizloyola.anima.core.nav.MoveCapabilities;
import java.util.ArrayList;
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
 */
public final class PlaceFrom implements CompoundTask {

    /** The placer's: from the eye to the middle of the cell. */
    public static final double REACH = 4.5;

    private final Placing placing;
    private final List<Pos> also;
    private final @Nullable Pos stand;
    private final boolean clearing;
    private final List<Method> methods = List.of(new WalkAndPlace());

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

    private Optional<Pos> standFor(BrainContext ctx) {
        MoveCapabilities body = MoveCapabilities.of(ctx.profile());
        Set<Pos> others = Set.copyOf(also);
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
}
