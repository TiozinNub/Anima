package dev.luizloyola.anima.core.brain.task;

import dev.luizloyola.anima.core.brain.act.Hand;
import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A scripted {@link Hand}: a cell in {@link #usable} changes once when used; anything else does not.
 * Every door shuts but a {@link #jammed} one.
 */
public final class FakeHand implements Hand {
    /** Cells a use changes — each once, like a bush that has been picked. */
    public final Set<Pos> usable = new LinkedHashSet<>();
    /** Every use asked for, in order, changed or not. */
    public final List<Pos> used = new ArrayList<>();

    /** Doors a shut fails on: out of reach, or held by a button. Every other door ends shut. */
    public final Set<Pos> jammed = new LinkedHashSet<>();
    /** Every shut asked for, in order. */
    public final List<Pos> shut = new ArrayList<>();

    @Override
    public boolean shut(Pos door) {
        shut.add(door);
        return !jammed.contains(door);
    }

    @Override
    public boolean use(Pos target) {
        used.add(target);
        return usable.remove(target);
    }
}
