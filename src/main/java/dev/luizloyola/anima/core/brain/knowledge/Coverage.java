package dev.luizloyola.anima.core.brain.knowledge;

import dev.luizloyola.anima.core.brain.sense.Pos;

/**
 * Where ground somebody covered gets banked. Told as it happens, so whatever OWNS a sweep keeps it
 * across a re-grant and a reload — a task is rebuilt fresh on every grant, which is why a preempted
 * sweep used to start its box over.
 *
 * <p>Two verbs because there are two kinds of evidence. {@link #near} is what a body individuates
 * by being there, and it is partial: a near field can only ever cover part of a cell at a time.
 * {@link #settled} is a whole cell written off — a look that found nothing in it, or ground no walk
 * can reach.
 */
public interface Coverage {

    /** A sweep nobody is tracking, which is every sweep outside a project. */
    Coverage NONE = new Coverage() {
        @Override
        public void near(Pos here, int radius) {
        }

        @Override
        public void settled(Pos corner) {
        }
    };

    /** The ground a near field of {@code radius} around {@code here} individuates. */
    void near(Pos here, int radius);

    /** This whole cell is known, named by its min corner. */
    void settled(Pos corner);
}
