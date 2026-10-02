package dev.luizloyola.anima.core.brain.history;

import dev.luizloyola.anima.core.brain.sense.Pos;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Cells this body's legs lately found no way to: a walk there failed stranded or unreachable.
 * Struck for a while, so a stand chooser picks another instead of re-ordering the same walk: a
 * stand on a roof was searched for 26,577 times in four minutes (forest, 2026-10-02).
 *
 * <p>Body state rather than a task's: each fresh round of an achieve-goal builds its subgoals new,
 * so a strike held by one would be forgotten by the next.
 */
public final class Unreached {

    /**
     * Two minutes: past the 600-tick retry cooldown of a failed errand, so the retry finds the stand
     * still struck and fails without a walk; short enough that a door opened since is not held
     * against the place for long.
     */
    public static final long LIFETIME_TICKS = 2_400;
    /**
     * Above the 49 cells a new store's stands are drawn from (two rings of ground and the cells
     * beside them): with fewer, the oldest strike went before the last stand was tried, and the
     * walks went round again.
     */
    public static final int CAPACITY = 64;

    public record Strike(Pos cell, long tick) {
    }

    private final List<Strike> strikes = new ArrayList<>();

    public void strike(Pos cell, long now) {
        strikes.removeIf(strike -> strike.cell().equals(cell));
        strikes.add(0, new Strike(cell, now));
        prune(now);
    }

    /** A walk got there after all. */
    public void clear(Pos cell) {
        strikes.removeIf(strike -> strike.cell().equals(cell));
    }

    public boolean struck(Pos cell, long now) {
        for (Strike strike : strikes) {
            if (strike.cell().equals(cell) && now - strike.tick() < LIFETIME_TICKS) {
                return true;
            }
        }
        return false;
    }

    /** The saved form, newest first. */
    public List<Strike> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(strikes));
    }

    public void restore(List<Strike> saved) {
        strikes.clear();
        strikes.addAll(saved);
        while (strikes.size() > CAPACITY) {
            strikes.remove(strikes.size() - 1);
        }
    }

    private void prune(long now) {
        strikes.removeIf(strike -> now - strike.tick() >= LIFETIME_TICKS);
        while (strikes.size() > CAPACITY) {
            strikes.remove(strikes.size() - 1);
        }
    }
}
