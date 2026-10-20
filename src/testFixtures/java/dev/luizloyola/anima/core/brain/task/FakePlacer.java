package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.act.BlockPlacer;
import dev.luizloyola.anima.core.brain.act.Placing;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Scripted {@link BlockPlacer}, refusing on demand. Does not touch the inventory —
 * the real placer consumes the item, so tests that care seed and assert it themselves.
 */
public final class FakePlacer implements BlockPlacer {
    public record Placement(String itemId, Pos cell) {
    }

    public final List<Placement> placed = new ArrayList<>();
    public final List<Placing> placings = new ArrayList<>();
    public boolean refuse;
    /** What a refusal says. */
    public Refusal refusal = Refusal.NOTHING_AGAINST;
    /** Refuses by the placing, for a test whose world decides: asked when {@link #refuse} is off. */
    public Function<Placing, Optional<Refusal>> rule = placing -> Optional.empty();
    /** Every placing asked for, placed or not. */
    public final List<Placing> attempts = new ArrayList<>();

    @Override
    public Optional<Refusal> attempt(Placing placing) {
        attempts.add(placing);
        if (refuse) {
            return Optional.of(refusal);
        }
        Optional<Refusal> ruled = rule.apply(placing);
        if (ruled.isPresent()) {
            return ruled;
        }
        placed.add(new Placement(placing.itemId(), placing.cell()));
        placings.add(placing);
        return Optional.empty();
    }
}
