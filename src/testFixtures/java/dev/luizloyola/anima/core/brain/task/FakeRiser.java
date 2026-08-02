package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.act.RiseState;
import dev.luizloyola.anima.core.brain.act.Riser;

/**
 * Scripted {@link Riser}: {@code up} succeeds (and goes RISING) unless refused; the test plays
 * the body — flip {@link #state} to RISEN and raise the fake position, the way
 * {@code AgentRiser.tick()} lands the jump.
 */
public final class FakeRiser implements Riser {
    public RiseState state = RiseState.IDLE;
    public boolean refuse;
    public int ups;
    public String lastItem;

    @Override
    public boolean up(String itemId) {
        if (refuse || state == RiseState.RISING) {
            return false;
        }
        ups++;
        lastItem = itemId;
        state = RiseState.RISING;
        return true;
    }

    @Override
    public RiseState state() {
        return state;
    }

    @Override
    public void abort() {
        state = RiseState.IDLE;
    }
}
