package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.act.LeanState;
import dev.luizloyola.anima.core.brain.act.Leaner;

/** A lean that goes where it is told, one state at a time, as a test's world advances it. */
public final class FakeLeaner implements Leaner {
    public LeanState state = LeanState.IDLE;
    public boolean refuse;
    public int leans;
    public double lastX;
    public double lastZ;

    @Override
    public boolean toward(double x, double z) {
        if (refuse || state == LeanState.LEANING || state == LeanState.RELEASING) {
            return false;
        }
        leans++;
        lastX = x;
        lastZ = z;
        state = LeanState.LEANING;
        return true;
    }

    @Override
    public LeanState state() {
        return state;
    }

    @Override
    public void release() {
        if (state == LeanState.LEANING || state == LeanState.LEANT) {
            state = LeanState.RELEASING;
        } else if (state == LeanState.FAILED) {
            state = LeanState.IDLE;
        }
    }
}
