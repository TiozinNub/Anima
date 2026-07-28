package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.BrainContext;

/**
 * Un-build whatever the body's standing ledger holds ({@code Scaffolder.placed()}) — the
 * {@link dev.luizloyola.anima.core.brain.instinct.DescendInstinct}'s root: whoever built the pillar
 * and however that task died, this brings them and the blocks back down. Mechanics live in the
 * shared {@link PillarDescent}. Never FAILED — an unreachable cell is struck from the ledger.
 */
public final class UnbuildPillar implements PrimitiveTask {
    private final PillarDescent descent = new PillarDescent();

    @Override
    public TaskStatus tick(BrainContext ctx) {
        return descent.tick(ctx);
    }

    @Override
    public void cancel(BrainContext ctx) {
        descent.cancel(ctx);
    }

    @Override
    public String describe() {
        return "un-build pillar";
    }
}
