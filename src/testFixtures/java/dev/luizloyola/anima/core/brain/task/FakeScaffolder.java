package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.act.ScaffoldState;
import dev.luizloyola.anima.core.brain.act.Scaffolder;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Scripted {@link Scaffolder}. The test plays the body — flip {@link #state} to RISEN, raise the
 * fake position, and {@code placed.push(...)} the cell the "block" landed in, the way
 * {@code AgentScaffolder.tick()} ledgers at the actual placement.
 */
public final class FakeScaffolder implements Scaffolder {
    public ScaffoldState state = ScaffoldState.IDLE;
    public boolean refuse;
    public int ups;
    public String lastItem;
    /** The standing ledger, newest first — tests seed it (a leftover tower) or fill it as the body. */
    public final Deque<Pos> placed = new ArrayDeque<>();

    @Override
    public boolean up(String itemId) {
        if (refuse || state == ScaffoldState.RISING || placed.size() >= PILLAR_MAX) {
            return false;
        }
        ups++;
        lastItem = itemId;
        state = ScaffoldState.RISING;
        return true;
    }

    @Override
    public List<Pos> placed() {
        return List.copyOf(placed);
    }

    @Override
    public void reclaim(Pos cell) {
        placed.remove(cell);
    }

    @Override
    public ScaffoldState state() {
        return state;
    }

    @Override
    public void abort() {
        state = ScaffoldState.IDLE;
    }
}
