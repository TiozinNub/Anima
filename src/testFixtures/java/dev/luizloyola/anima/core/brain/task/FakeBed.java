package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.act.Bed;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.LinkedHashSet;
import java.util.Set;

/** A scripted {@link Bed}: a cell in {@link #beds} can be lain in, unless it is {@link #taken}. */
public final class FakeBed implements Bed {
    public final Set<Pos> beds = new LinkedHashSet<>();
    /** Beds someone else lies in. */
    public final Set<Pos> taken = new LinkedHashSet<>();
    /** The bed lain in, or null standing. */
    public Pos in;
    public int getUps;

    @Override
    public boolean lieDown(Pos bed) {
        if (bed.equals(in)) {
            return true;
        }
        if (in != null || !beds.contains(bed) || taken.contains(bed)) {
            return false;
        }
        in = bed;
        return true;
    }

    @Override
    public boolean asleep() {
        return in != null;
    }

    @Override
    public void getUp() {
        if (in != null) {
            getUps++;
        }
        in = null;
    }
}
