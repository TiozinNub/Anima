package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.act.BlockBreaker;
import dev.luizloyola.anima.core.brain.act.BreakState;
import dev.luizloyola.anima.core.brain.sense.Pos;
import org.jspecify.annotations.Nullable;

/**
 * Scripted {@link BlockBreaker} for task tests: tests advance the "arm" by writing
 * {@link #state} directly, the way the body would.
 */
public final class FakeBreaker implements BlockBreaker {
    public @Nullable Pos target;
    /** Every begin target in order — the choreography assertions read this. */
    public final java.util.List<Pos> targets = new java.util.ArrayList<>();
    public boolean refuseBegin;
    /** Targets begin() refuses (simulated out-of-reach); refuseBegin refuses everything. */
    public final java.util.Set<Pos> refuse = new java.util.HashSet<>();
    public BreakState state = BreakState.IDLE;
    public int begins;
    public int aborts;

    @Override
    public boolean begin(Pos target) {
        this.begins++;
        if (refuseBegin || refuse.contains(target)) {
            return false;
        }
        this.targets.add(target);
        this.target = target;
        this.state = BreakState.BREAKING;
        return true;
    }

    /** Scripted arm-path answers: cell -> the obstruction the fake arm reports for it. */
    public final java.util.Map<Pos, Pos> obstructions = new java.util.HashMap<>();

    @Override
    public @Nullable Pos obstruction(Pos target) {
        return obstructions.get(target);
    }

    @Override
    public BreakState state() {
        return state;
    }

    @Override
    public void abort() {
        aborts++;
        state = BreakState.IDLE;
    }
}
