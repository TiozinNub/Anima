package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.act.Striker;
import dev.luizloyola.anima.core.brain.sense.BeingId;
import java.util.ArrayList;
import java.util.List;

/**
 * Test double for the {@link Striker} port: the reach and the charge are scripted, and every swing
 * is logged. A blow does not by itself change the reach — a test kills the target by setting
 * {@link Striker.Reach#DEAD}.
 */
public final class FakeStriker implements Striker {
    public Reach reach = Reach.OUT_OF_REACH;
    public double charge = 1.0;
    public final List<BeingId> struck = new ArrayList<>();
    /** What the next {@link #draw} answers; it answers once, then the hand holds the best. */
    public boolean drawChanges;
    public int draws;

    @Override
    public Reach reach(BeingId target) {
        return reach;
    }

    @Override
    public double charge() {
        return charge;
    }

    @Override
    public boolean strike(BeingId target) {
        struck.add(target);
        return reach == Reach.IN_REACH;
    }

    @Override
    public boolean draw() {
        draws++;
        boolean changed = drawChanges;
        drawChanges = false;
        return changed;
    }
}
