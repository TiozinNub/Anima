package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.act.Gazer;

/**
 * A head that remembers what it was last asked to look at, and how many times. The live organ
 * resolves claims by rank; a test only ever asks what was claimed.
 */
public final class FakeGazer implements Gazer {
    public boolean asked;
    public double x;
    public double y;
    public double z;
    public Priority priority;
    public int holdTicks;
    /** How many claims have been made — a look re-asked every tick is not one look. */
    public int claims;

    @Override
    public void lookAt(double x, double y, double z, Priority priority, int holdTicks) {
        this.asked = true;
        this.x = x;
        this.y = y;
        this.z = z;
        this.priority = priority;
        this.holdTicks = holdTicks;
        this.claims++;
    }
}
