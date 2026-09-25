package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.BrainContext;
import dev.luizloyola.anima.core.brain.knowledge.PoiKind;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Set a carried station on the nearest ground to a place that will hold it, and claim it for the
 * party. The half of {@link PlaceStation} that runs once the block is in the pack: a compound so
 * that the cell is chosen when this expands, not when the plan was made.
 */
public final class PutDown implements CompoundTask {

    /**
     * How far from the asked-for cell a station may go. Wider than a yard chest's two: a base is
     * set up before its ground is cleared, and the first free cell may be past a trunk or two.
     */
    static final int RINGS = 3;

    private final PoiKind kind;
    private final String itemId;
    private final Pos near;
    private final List<Method> methods = List.of(new WalkPlaceClaim());

    public PutDown(PoiKind kind, String itemId, Pos near) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.itemId = Objects.requireNonNull(itemId, "itemId");
        this.near = Objects.requireNonNull(near, "near");
    }

    public PoiKind kind() {
        return kind;
    }

    public String itemId() {
        return itemId;
    }

    public Pos near() {
        return near;
    }

    @Override
    public List<Method> methods() {
        return methods;
    }

    @Override
    public String describe() {
        return "put " + itemId + " down";
    }

    private final class WalkPlaceClaim implements Method {
        @Override
        public boolean applicable(BrainContext ctx) {
            return ctx.percepts().inventory().count(itemId::equals) > 0;
        }

        @Override
        public double estimateCost(BrainContext ctx) {
            return 0.0;
        }

        @Override
        public List<Task> decompose(BrainContext ctx) {
            Pos spot = Ground.near(ctx, near, RINGS);
            Pos stand = EnsureTable.WalkToKnown.standableBeside(spot, ctx);
            List<Task> steps = new ArrayList<>();
            Pos feet = ctx.percepts().position();
            // Never walk to the cell you are standing in: the navigator answers PATHING to its own
            // cell and never arrives (EnsureStore's lesson, 2026-08-20).
            if (!(feet.x() == stand.x() && feet.y() == stand.y() && feet.z() == stand.z())) {
                steps.add(new GoTo(stand.x(), stand.y(), stand.z()));
            }
            steps.add(new PlaceBlock(itemId, spot.x(), spot.y(), spot.z()));
            steps.add(new FoundPlace(kind, spot.x(), spot.y(), spot.z()));
            return steps;
        }

        @Override
        public String describe() {
            return "walk over, place it, claim it";
        }
    }
}
